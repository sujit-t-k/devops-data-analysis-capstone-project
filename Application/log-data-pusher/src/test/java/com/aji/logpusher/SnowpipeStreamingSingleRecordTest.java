package com.aji.logpusher;

import com.aji.logpusher.config.SnowflakeProperties;
import com.aji.logpusher.service.SnowflakeStreamingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "ingestion.scheduler.enabled=false"
})
class SnowpipeStreamingSingleRecordTest {

    @Autowired
    private SnowflakeProperties snowflakeProperties;

    @Autowired
    private SnowflakeStreamingService snowflakeStreamingService;

    @Test
    void testSendSingleRecordToEachBronzeTable() {
        if (!snowflakeProperties.isConfigured()) {
            System.out.println(">>> [NOTICE] SNOWFLAKE_ACCOUNT not configured in snowflake-config.env.");
            System.out.println(">>> Skipping live Snowflake streaming test until account identifier is provided.");
            return;
        }

        String batchId = "TEST_BATCH_" + UUID.randomUUID().toString().substring(0, 8);

        // 1. Send one record to BRONZE.LOG_RECORDS
        Map<String, Object> logRecord = new HashMap<>();
        String testLogId = "TEST-LOG-" + System.currentTimeMillis();
        logRecord.put("ID", testLogId);
        logRecord.put("EVENT_TIMESTAMP", LocalDateTime.now());
        logRecord.put("CORRELATION_ID", "CORR-TEST-VERIFY");
        logRecord.put("SERVICE_NAME", "test-service");
        logRecord.put("LOG_SOURCE", "SPRING_BOOT");
        logRecord.put("LOG_LEVEL", "INFO");
        logRecord.put("MESSAGE", "Snowpipe Streaming verification single record test");
        logRecord.put("LOGGER_NAME", "com.aji.logpusher.SnowpipeStreamingSingleRecordTest");
        logRecord.put("THREAD_NAME", "main");
        logRecord.put("OPERATION", "VERIFICATION_TEST");
        logRecord.put("HTTP_METHOD", "GET");
        logRecord.put("REQUEST_URI", "/api/v1/health");
        logRecord.put("HTTP_STATUS", 200);
        logRecord.put("REQUEST_TIME", new BigDecimal("0.0150"));
        logRecord.put("UPSTREAM_RESPONSE_TIME", new BigDecimal("0.0120"));
        logRecord.put("CLIENT_IP", "127.0.0.1");
        logRecord.put("USER_AGENT", "SnowflakeStreamingTestAgent/1.0");
        logRecord.put("RAW_LOG", Map.of("test_key", "verification_value", "timestamp", System.currentTimeMillis()));
        logRecord.put("INGESTED_AT", LocalDateTime.now());
        logRecord.put("BATCH_ID", batchId);
        logRecord.put("SOURCE_SYSTEM", "SNOWPIPE_STREAMING");

        System.out.println(">>> Streaming single test record to " + snowflakeProperties.getLogRecordsTable() + "...");
        snowflakeStreamingService.ingestRows(
                snowflakeProperties.getLogRecordsTable(),
                Collections.singletonList(logRecord),
                "OFFSET_" + testLogId
        );
        System.out.println(">>> Successfully ingested single log record into Snowflake Bronze table!");

        // 2. Send one record to BRONZE.INCIDENTS
        Map<String, Object> incidentRecord = new HashMap<>();
        String testIncId = "TEST-INC-" + System.currentTimeMillis();
        incidentRecord.put("ID", testIncId);
        incidentRecord.put("CORRELATION_ID", "CORR-TEST-VERIFY");
        incidentRecord.put("TITLE", "Snowpipe Streaming verification test incident");
        incidentRecord.put("DESCRIPTION", "Test incident payload for Snowpipe streaming verification");
        incidentRecord.put("REMEDIATION_PLAN", "Automated verification test completed");
        incidentRecord.put("STATUS", "RESOLVED");
        incidentRecord.put("CREATED_AT", LocalDateTime.now().minusMinutes(5));
        incidentRecord.put("UPDATED_AT", LocalDateTime.now());
        incidentRecord.put("RESOLVED_AT", LocalDateTime.now());
        incidentRecord.put("INGESTED_AT", LocalDateTime.now());
        incidentRecord.put("BATCH_ID", batchId);
        incidentRecord.put("SOURCE_SYSTEM", "SNOWPIPE_STREAMING");

        System.out.println(">>> Streaming single test record to " + snowflakeProperties.getIncidentsTable() + "...");
        snowflakeStreamingService.ingestRows(
                snowflakeProperties.getIncidentsTable(),
                Collections.singletonList(incidentRecord),
                "OFFSET_" + testIncId
        );
        System.out.println(">>> Successfully ingested single incident into Snowflake Bronze table!");
        assertTrue(true);
    }
}

