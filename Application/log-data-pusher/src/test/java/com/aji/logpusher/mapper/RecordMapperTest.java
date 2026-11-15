package com.aji.logpusher.mapper;

import com.aji.logpusher.entity.Incident;
import com.aji.logpusher.entity.LogRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecordMapperTest {

    private RecordMapper recordMapper;

    @BeforeEach
    void setUp() {
        recordMapper = new RecordMapper(new ObjectMapper());
    }

    @Test
    void testToSnowflakeLogRecord() {
        LogRecord record = new LogRecord();
        record.setId(101L);
        record.setEventTimestamp(LocalDateTime.of(2026, 11, 15, 10, 0, 0));
        record.setCorrelationId("CORR-TEST-123");
        record.setServiceName("order-service");
        record.setLogSource("SPRING_BOOT");
        record.setLogLevel("INFO");
        record.setMessage("Test message");
        record.setLoggerName("com.aji.TestLogger");
        record.setThreadName("main");
        record.setOperation("TEST_OP");
        record.setHttpMethod("GET");
        record.setRequestUri("/api/v1/test");
        record.setHttpStatus(200);
        record.setRequestTime(new BigDecimal("0.025000"));
        record.setUpstreamResponseTime(new BigDecimal("0.015000"));
        record.setClientIp("127.0.0.1");
        record.setUserAgent("TestAgent/1.0");
        record.setRawLog("{\"key\":\"value\",\"count\":42}");

        Map<String, Object> map = recordMapper.toSnowflakeLogRecord(record, "BATCH-001");

        assertEquals("101", map.get("ID"));
        assertEquals(record.getEventTimestamp(), map.get("EVENT_TIMESTAMP"));
        assertEquals("CORR-TEST-123", map.get("CORRELATION_ID"));
        assertEquals("order-service", map.get("SERVICE_NAME"));
        assertEquals("SPRING_BOOT", map.get("LOG_SOURCE"));
        assertEquals("INFO", map.get("LOG_LEVEL"));
        assertEquals("BATCH-001", map.get("BATCH_ID"));
        assertEquals("SNOWPIPE_STREAMING", map.get("SOURCE_SYSTEM"));

        // VARIANT column should be parsed as a Map
        Object rawLog = map.get("RAW_LOG");
        assertNotNull(rawLog);
        assertTrue(rawLog instanceof Map, "RAW_LOG must be deserialized to a Map for Snowflake VARIANT columns");
        @SuppressWarnings("unchecked")
        Map<String, Object> rawMap = (Map<String, Object>) rawLog;
        assertEquals("value", rawMap.get("key"));
        assertEquals(42, rawMap.get("count"));
    }

    @Test
    void testToSnowflakeIncident() {
        Incident incident = new Incident();
        incident.setId(501L);
        incident.setCorrelationId("CORR-INC-501");
        incident.setTitle("High latency in payment gateway");
        incident.setDescription("Upstream latency exceeded 3000ms");
        incident.setRemediationPlan("Scale out payment pods");
        incident.setStatus("INCIDENT_IDENTIFIED");
        incident.setCreatedAt(LocalDateTime.of(2026, 11, 15, 10, 30, 0));
        incident.setUpdatedAt(LocalDateTime.of(2026, 11, 15, 10, 35, 0));
        incident.setResolvedAt(null);

        Map<String, Object> map = recordMapper.toSnowflakeIncident(incident, "BATCH-INC-001");

        assertEquals("501", map.get("ID"));
        assertEquals("CORR-INC-501", map.get("CORRELATION_ID"));
        assertEquals("High latency in payment gateway", map.get("TITLE"));
        assertEquals("INCIDENT_IDENTIFIED", map.get("STATUS"));
        assertEquals("BATCH-INC-001", map.get("BATCH_ID"));
        assertEquals("SNOWPIPE_STREAMING", map.get("SOURCE_SYSTEM"));
        assertNotNull(map.get("INGESTED_AT"));
    }
}

