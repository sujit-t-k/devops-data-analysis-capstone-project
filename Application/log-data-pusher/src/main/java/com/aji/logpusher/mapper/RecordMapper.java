package com.aji.logpusher.mapper;

import com.aji.logpusher.entity.Incident;
import com.aji.logpusher.entity.LogRecord;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class RecordMapper {

    private final ObjectMapper objectMapper;
    private static final String SOURCE_SYSTEM = "SNOWPIPE_STREAMING";

    /**
     * Maps a MySQL LogRecord entity to a Map<String, Object> matching Snowflake BRONZE.LOG_RECORDS columns.
     */
    public Map<String, Object> toSnowflakeLogRecord(LogRecord record, String batchId) {
        Map<String, Object> map = new HashMap<>();

        map.put("ID", String.valueOf(record.getId()));
        map.put("EVENT_TIMESTAMP", record.getEventTimestamp());
        map.put("CORRELATION_ID", record.getCorrelationId());
        map.put("SERVICE_NAME", record.getServiceName());
        map.put("LOG_SOURCE", record.getLogSource());
        map.put("LOG_LEVEL", record.getLogLevel());
        map.put("MESSAGE", record.getMessage());
        map.put("LOGGER_NAME", record.getLoggerName());
        map.put("THREAD_NAME", truncate(record.getThreadName(), 100));
        map.put("OPERATION", record.getOperation());
        map.put("HTTP_METHOD", record.getHttpMethod());
        map.put("REQUEST_URI", truncate(record.getRequestUri(), 4000));
        map.put("HTTP_STATUS", record.getHttpStatus());
        map.put("REQUEST_TIME", record.getRequestTime());
        map.put("UPSTREAM_RESPONSE_TIME", record.getUpstreamResponseTime());
        map.put("CLIENT_IP", record.getClientIp());
        map.put("USER_AGENT", truncate(record.getUserAgent(), 1000));

        // Parse JSON raw_log into Map so Snowflake stores it as true VARIANT JSON object
        map.put("RAW_LOG", parseJsonVariant(record.getRawLog()));

        // Audit metadata
        map.put("INGESTED_AT", LocalDateTime.now());
        map.put("BATCH_ID", batchId);
        map.put("SOURCE_SYSTEM", SOURCE_SYSTEM);

        return map;
    }

    /**
     * Maps a MySQL Incident entity to a Map<String, Object> matching Snowflake BRONZE.INCIDENTS columns.
     */
    public Map<String, Object> toSnowflakeIncident(Incident incident, String batchId) {
        Map<String, Object> map = new HashMap<>();

        map.put("ID", String.valueOf(incident.getId()));
        map.put("CORRELATION_ID", incident.getCorrelationId());
        map.put("TITLE", incident.getTitle());
        map.put("DESCRIPTION", incident.getDescription());
        map.put("REMEDIATION_PLAN", incident.getRemediationPlan());
        map.put("STATUS", incident.getStatus());
        map.put("CREATED_AT", incident.getCreatedAt());
        map.put("UPDATED_AT", incident.getUpdatedAt());
        map.put("RESOLVED_AT", incident.getResolvedAt());

        // Audit metadata
        map.put("INGESTED_AT", LocalDateTime.now());
        map.put("BATCH_ID", batchId);
        map.put("SOURCE_SYSTEM", SOURCE_SYSTEM);

        return map;
    }

    private Object parseJsonVariant(String json) {
        if (json == null || json.trim().isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse raw_log JSON, falling back to raw string: {}", e.getMessage());
            return json;
        }
    }

    private String truncate(String val, int maxLength) {
        if (val != null && val.length() > maxLength) {
            return val.substring(0, maxLength);
        }
        return val;
    }
}

