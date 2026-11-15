package com.aji.logpusher.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Properties;

@Slf4j
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "snowflake.streaming")
public class SnowflakeProperties {

    private String account;
    private String user;
    private String role;
    private String warehouse;
    private String database;
    private String schema;
    private String logRecordsTable;
    private String incidentsTable;
    private String privateKeyPath;
    private String privateKey;
    private String privateKeyPassphrase;

    private String cleanedPrivateKey;

    public String getMissingConfigurationReason() {
        if (!StringUtils.hasText(account) || "YOUR_SNOWFLAKE_ACCOUNT_IDENTIFIER".equalsIgnoreCase(account.trim())) {
            return "SNOWFLAKE_ACCOUNT is missing or set to placeholder";
        }
        if (!StringUtils.hasText(user)) {
            return "SNOWFLAKE_USER is missing";
        }
        if (!StringUtils.hasText(database)) {
            return "SNOWFLAKE_DATABASE is missing";
        }
        if (!StringUtils.hasText(schema)) {
            return "SNOWFLAKE_SCHEMA is missing";
        }
        if (!StringUtils.hasText(cleanedPrivateKey)) {
            return "RSA private key could not be resolved or loaded (check SNOWFLAKE_PRIVATE_KEY_PATH or SNOWFLAKE_PRIVATE_KEY)";
        }
        return null;
    }

    public boolean isConfigured() {
        return getMissingConfigurationReason() == null;
    }

    @PostConstruct
    public void validateAndInitialize() {
        log.info("Validating Snowflake Snowpipe Streaming configuration...");

        // Resolve and clean private key if present
        try {
            this.cleanedPrivateKey = resolveAndCleanPrivateKey();
        } catch (Exception e) {
            log.warn("Snowflake private key not yet loaded: {}", e.getMessage());
        }

        if (!StringUtils.hasText(account) || "YOUR_SNOWFLAKE_ACCOUNT_IDENTIFIER".equalsIgnoreCase(account.trim())) {
            log.warn("SNOWFLAKE_ACCOUNT is not yet set in snowflake-config.env. " +
                    "Set your account identifier to enable Snowpipe Streaming ingestion.");
            return;
        }

        log.info("Snowflake configuration successfully validated for account: {}, user: {}, database: {}, schema: {}",
                account, user, database, schema);
    }

    private String resolveAndCleanPrivateKey() {
        String rawKey = null;

        if (StringUtils.hasText(privateKey)) {
            String cleanedCandidate = cleanPemContent(privateKey);
            if (StringUtils.hasText(cleanedCandidate)) {
                rawKey = cleanedCandidate;
                log.info("Loaded Snowflake RSA private key directly from SNOWFLAKE_PRIVATE_KEY environment variable.");
            }
        }

        if (!StringUtils.hasText(rawKey) && StringUtils.hasText(privateKeyPath)) {
            Path path = Paths.get(privateKeyPath.trim());
            if (Files.exists(path)) {
                try {
                    rawKey = Files.readString(path, StandardCharsets.UTF_8);
                    log.info("Loaded Snowflake RSA private key from file path: {}", path.toAbsolutePath());
                } catch (IOException e) {
                    log.warn("Failed reading private key from {}: {}", path, e.getMessage());
                }
            } else {
                // Check relative to working dir
                Path resolved = Paths.get("").toAbsolutePath().resolve(path).normalize();
                if (Files.exists(resolved)) {
                    try {
                        rawKey = Files.readString(resolved, StandardCharsets.UTF_8);
                        log.info("Loaded Snowflake RSA private key from resolved path: {}", resolved);
                    } catch (IOException e) {
                        log.warn("Failed reading private key from {}: {}", resolved, e.getMessage());
                    }
                }
            }

            // Fallback: Check classpath if not found on filesystem
            if (!StringUtils.hasText(rawKey)) {
                String cpPath = privateKeyPath.replace("classpath:", "").trim();
                // If path had directory navigation like "../snowflake/keys/rsa_key.p8", take just the filename
                String filename = Paths.get(cpPath).getFileName().toString();
                try (var is = getClass().getClassLoader().getResourceAsStream(filename)) {
                    if (is != null) {
                        rawKey = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                        log.info("Loaded Snowflake RSA private key from classpath resource: {}", filename);
                    }
                } catch (Exception e) {
                    log.warn("Failed reading private key from classpath resource {}: {}", filename, e.getMessage());
                }
            }
        }

        // Automatic fallback: check if rsa_key.p8 exists in working directory or classpath
        if (!StringUtils.hasText(rawKey)) {
            Path localFile = Paths.get("rsa_key.p8");
            if (Files.exists(localFile)) {
                try {
                    rawKey = Files.readString(localFile, StandardCharsets.UTF_8);
                    log.info("Loaded Snowflake RSA private key from local working directory: rsa_key.p8");
                } catch (IOException ignored) {}
            }
        }

        if (!StringUtils.hasText(rawKey)) {
            throw new IllegalStateException(
                    "Snowflake private key could not be found. Provide a valid single-line SNOWFLAKE_PRIVATE_KEY " +
                    "or mount/supply the key file via SNOWFLAKE_PRIVATE_KEY_PATH.");
        }

        String cleaned = cleanPemContent(rawKey);

        // Sanity check that it's a valid PKCS#8 key
        try {
            byte[] keyBytes = Base64.getDecoder().decode(cleaned);
            PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(keyBytes);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            PrivateKey pk = keyFactory.generatePrivate(keySpec);
            log.info("RSA Private Key validated successfully. Algorithm: {}, Format: {}", pk.getAlgorithm(), pk.getFormat());
        } catch (Exception e) {
            log.warn("Note: Validating PKCS#8 key bytes directly failed (might be encrypted or proprietary): {}. Passing string directly to SDK.", e.getMessage());
        }

        return cleaned;
    }

    private String cleanPemContent(String pem) {
        return pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");
    }

    /**
     * Builds Properties object expected by SnowflakeStreamingIngestClientFactory.
     */
    public Properties toClientProperties() {
        if (!isConfigured()) {
            throw new IllegalStateException(
                    "Snowflake configuration is incomplete in snowflake-config.env. " +
                    "Please specify SNOWFLAKE_ACCOUNT, SNOWFLAKE_USER, SNOWFLAKE_DATABASE, SNOWFLAKE_SCHEMA and SNOWFLAKE_PRIVATE_KEY_PATH.");
        }
        Properties props = new Properties();
        // Construct full URL if not full url
        String host = account;
        if (!host.contains(".snowflakecomputing.com")) {
            host = host + ".snowflakecomputing.com";
        }
        props.put("url", "https://" + host);
        props.put("account", account);
        props.put("user", user);
        if (StringUtils.hasText(role)) {
            props.put("role", role);
        }
        if (StringUtils.hasText(warehouse)) {
            props.put("warehouse", warehouse);
        }
        props.put("database", database);
        props.put("schema", schema);
        props.put("private_key", cleanedPrivateKey);
        props.put("authorization_type", "JWT");

        if (StringUtils.hasText(privateKeyPassphrase)) {
            props.put("snowflake.private.key.passphrase", privateKeyPassphrase);
        }

        return props;
    }
}

