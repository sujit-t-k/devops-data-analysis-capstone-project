package com.aji.logpusher.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnowflakeConfigValidationTest {

    @Test
    void testRsaPrivateKeyLoadingAndParsing() throws Exception {
        Path keyPath = Paths.get("../snowflake/keys/rsa_key.p8");
        assertTrue(Files.exists(keyPath), "RSA private key file must exist at ../snowflake/keys/rsa_key.p8");

        String rawContent = Files.readString(keyPath);
        String cleanedKey = rawContent
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");

        byte[] keyBytes = Base64.getDecoder().decode(cleanedKey);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        PrivateKey privateKey = kf.generatePrivate(spec);

        assertNotNull(privateKey);
        assertTrue(privateKey.getAlgorithm().equalsIgnoreCase("RSA"));
    }
}

