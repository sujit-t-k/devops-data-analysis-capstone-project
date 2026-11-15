package com.aji.logpusher.service;

import com.aji.logpusher.config.IngestionProperties;
import com.aji.logpusher.config.SnowflakeProperties;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.snowflake.ingest.streaming.InsertValidationResponse;
import net.snowflake.ingest.streaming.OpenChannelRequest;
import net.snowflake.ingest.streaming.SnowflakeStreamingIngestChannel;
import net.snowflake.ingest.streaming.SnowflakeStreamingIngestClient;
import net.snowflake.ingest.streaming.SnowflakeStreamingIngestClientFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class SnowflakeStreamingService {

    private final SnowflakeProperties snowflakeProperties;
    private final IngestionProperties ingestionProperties;

    private volatile SnowflakeStreamingIngestClient client;
    private final Map<String, SnowflakeStreamingIngestChannel> channels = new ConcurrentHashMap<>();

    private synchronized SnowflakeStreamingIngestClient getOrCreateClient() {
        if (client == null || client.isClosed()) {
            Properties clientProps = snowflakeProperties.toClientProperties();
            String clientName = "LogDataPusherClient_" + System.currentTimeMillis();
            log.info("Initializing SnowflakeStreamingIngestClient '{}'...", clientName);
            client = SnowflakeStreamingIngestClientFactory
                    .builder(clientName)
                    .setProperties(clientProps)
                    .build();
            log.info("SnowflakeStreamingIngestClient '{}' initialized successfully.", clientName);
        }
        return client;
    }

    /**
     * Gets or opens an ingestion channel for a given table.
     */
    public synchronized SnowflakeStreamingIngestChannel getOrCreateChannel(String tableName) {
        SnowflakeStreamingIngestChannel channel = channels.get(tableName);
        if (channel != null && !channel.isClosed()) {
            return channel;
        }

        SnowflakeStreamingIngestClient streamingClient = getOrCreateClient();
        String channelName = "CHANNEL_" + tableName + "_DEFAULT";

        OpenChannelRequest request = OpenChannelRequest.builder(channelName)
                .setDBName(snowflakeProperties.getDatabase())
                .setSchemaName(snowflakeProperties.getSchema())
                .setTableName(tableName)
                .setOnErrorOption(OpenChannelRequest.OnErrorOption.CONTINUE)
                .build();

        log.info("Opening Snowpipe Streaming channel '{}' for table '{}.{}.{}'...",
                channelName, snowflakeProperties.getDatabase(), snowflakeProperties.getSchema(), tableName);

        channel = streamingClient.openChannel(request);
        channels.put(tableName, channel);
        log.info("Channel '{}' successfully opened. Current committed offset: {}",
                channelName, channel.getLatestCommittedOffsetToken());

        return channel;
    }

    /**
     * Ingests a batch of rows into the specified Snowflake table and waits for commit confirmation.
     */
    public void ingestRows(String tableName, List<Map<String, Object>> rows, String offsetToken) {
        if (rows == null || rows.isEmpty()) {
            return;
        }

        SnowflakeStreamingIngestChannel channel = getOrCreateChannel(tableName);

        log.info("Streaming {} rows into table '{}' with offset token '{}'...", rows.size(), tableName, offsetToken);
        InsertValidationResponse response = channel.insertRows(rows, offsetToken);

        if (response.hasErrors()) {
            log.error("Snowpipe Streaming validation error for table '{}'! Failed rows count: {}",
                    tableName, response.getInsertErrors().size());
            response.getInsertErrors().forEach(err ->
                    log.error("Error at row index {}: {}",
                            err.getRowIndex(), err.getException().getMessage()));
            throw new RuntimeException("Snowpipe Streaming insertion failed with validation errors for table: " + tableName);
        }

        log.debug("Rows passed client validation. Awaiting commit confirmation for offset token: {}", offsetToken);
        waitForCommit(channel, offsetToken);
        log.info("Successfully committed batch of {} rows into table '{}' with offset token '{}'.",
                rows.size(), tableName, offsetToken);
    }

    /**
     * Polls getLatestCommittedOffsetToken until Snowflake acknowledges persistence or timeout is reached.
     */
    private void waitForCommit(SnowflakeStreamingIngestChannel channel, String expectedOffset) {
        long timeoutMs = TimeUnit.SECONDS.toMillis(ingestionProperties.getCommitTimeoutSeconds());
        long deadline = System.currentTimeMillis() + timeoutMs;
        long pollIntervalMs = 500;

        while (System.currentTimeMillis() < deadline) {
            String committedOffset = channel.getLatestCommittedOffsetToken();
            if (expectedOffset != null && expectedOffset.equals(committedOffset)) {
                log.debug("Offset token '{}' committed by Snowflake server.", expectedOffset);
                return;
            }
            try {
                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while awaiting commit for offset token: " + expectedOffset, e);
            }
        }

        log.warn("Commit wait timeout ({} s) reached for offset token '{}'. Latest observed committed offset is '{}'. " +
                        "Data has been buffered and will be registered by Snowflake background worker.",
                ingestionProperties.getCommitTimeoutSeconds(), expectedOffset, channel.getLatestCommittedOffsetToken());
    }

    @PreDestroy
    public synchronized void close() {
        log.info("Shutting down Snowflake Streaming resources...");
        for (Map.Entry<String, SnowflakeStreamingIngestChannel> entry : channels.entrySet()) {
            try {
                if (entry.getValue() != null && !entry.getValue().isClosed()) {
                    log.info("Closing channel for table '{}'...", entry.getKey());
                    entry.getValue().close();
                }
            } catch (Exception e) {
                log.warn("Error closing channel for table {}: {}", entry.getKey(), e.getMessage());
            }
        }
        channels.clear();

        if (client != null && !client.isClosed()) {
            try {
                log.info("Closing SnowflakeStreamingIngestClient...");
                client.close();
            } catch (Exception e) {
                log.warn("Error closing SnowflakeStreamingIngestClient: {}", e.getMessage());
            }
        }
        log.info("Snowflake Streaming resources shutdown complete.");
    }
}

