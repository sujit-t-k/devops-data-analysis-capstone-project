package com.aji.logpusher.service;

import com.aji.logpusher.config.IngestionProperties;
import com.aji.logpusher.config.SnowflakeProperties;
import com.aji.logpusher.entity.Incident;
import com.aji.logpusher.entity.IngestionCheckpoint;
import com.aji.logpusher.entity.LogRecord;
import com.aji.logpusher.mapper.RecordMapper;
import com.aji.logpusher.repository.IncidentRepository;
import com.aji.logpusher.repository.IngestionCheckpointRepository;
import com.aji.logpusher.repository.LogRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class IngestionOrchestratorService {

    public static final String PIPELINE_LOG_RECORDS = "LOG_RECORDS";
    public static final String PIPELINE_INCIDENTS = "INCIDENTS";

    private final LogRecordRepository logRecordRepository;
    private final IncidentRepository incidentRepository;
    private final IngestionCheckpointRepository checkpointRepository;
    private final SnowflakeStreamingService snowflakeStreamingService;
    private final RecordMapper recordMapper;
    private final SnowflakeProperties snowflakeProperties;
    private final IngestionProperties ingestionProperties;

    /**
     * Executes end-to-end ingestion cycle for both log records and incidents.
     */
    public void runIngestionCycle() {
        if (!snowflakeProperties.isConfigured()) {
            log.warn("Snowflake configuration is incomplete: {}. Skipping ingestion cycle.",
                    snowflakeProperties.getMissingConfigurationReason());
            return;
        }

        log.info("========== Starting Ingestion Cycle ==========");
        try {
            ingestLogRecords();
        } catch (Exception e) {
            log.error("Error occurred during Log Records ingestion: {}", e.getMessage(), e);
        }

        try {
            ingestIncidents();
        } catch (Exception e) {
            log.error("Error occurred during Incidents ingestion: {}", e.getMessage(), e);
        }
        log.info("========== Ingestion Cycle Completed ==========");
    }

    /**
     * Incremental ingestion of log_records using ID watermark.
     */
    public void ingestLogRecords() {
        IngestionCheckpoint checkpoint = checkpointRepository.findById(PIPELINE_LOG_RECORDS)
                .orElseGet(() -> IngestionCheckpoint.builder()
                        .pipelineName(PIPELINE_LOG_RECORDS)
                        .lastProcessedId(0L)
                        .recordsProcessedTotal(0L)
                        .build());

        Long lastProcessedId = checkpoint.getLastProcessedId() != null ? checkpoint.getLastProcessedId() : 0L;

        // Check for new records before initiating ingestion
        long newRecordCount = logRecordRepository.countByIdGreaterThan(lastProcessedId);
        if (newRecordCount == 0) {
            log.info("[LogRecords] No new records found beyond ID {}. Ingestion skipped.", lastProcessedId);
            return;
        }

        log.info("[LogRecords] Found {} new log records to ingest (current watermark ID: {}).", newRecordCount, lastProcessedId);

        int batchSize = ingestionProperties.getBatchSize();
        long currentWatermark = lastProcessedId;
        long totalIngestedThisRun = 0;

        while (true) {
            List<LogRecord> batch = logRecordRepository.findByIdGreaterThanOrderByIdAsc(
                    currentWatermark, PageRequest.of(0, batchSize));

            if (batch.isEmpty()) {
                break;
            }

            String batchId = "BATCH_LOG_" + UUID.randomUUID().toString().substring(0, 8);
            List<Map<String, Object>> rows = batch.stream()
                    .map(record -> recordMapper.toSnowflakeLogRecord(record, batchId))
                    .toList();

            Long maxIdInBatch = batch.get(batch.size() - 1).getId();
            String offsetToken = String.valueOf(maxIdInBatch);

            // Stream to Snowflake Bronze table with retries
            streamWithRetry(snowflakeProperties.getLogRecordsTable(), rows, offsetToken);

            // Persist checkpoint only after successful ingestion
            currentWatermark = maxIdInBatch;
            totalIngestedThisRun += batch.size();

            checkpoint.setLastProcessedId(currentWatermark);
            checkpoint.setLastBatchId(batchId);
            checkpoint.setRecordsProcessedTotal(
                    (checkpoint.getRecordsProcessedTotal() != null ? checkpoint.getRecordsProcessedTotal() : 0L) + batch.size());
            checkpoint.setLastSuccessAt(LocalDateTime.now());
            checkpoint.setUpdatedAt(LocalDateTime.now());
            checkpointRepository.save(checkpoint);

            log.info("[LogRecords] Batch {} ({} records) committed successfully. Advanced watermark ID to {}.",
                    batchId, batch.size(), currentWatermark);
        }

        log.info("[LogRecords] Ingestion run completed. Total records streamed: {}.", totalIngestedThisRun);
    }

    /**
     * Incremental ingestion of incidents using updated_at with an overlap window.
     */
    public void ingestIncidents() {
        IngestionCheckpoint checkpoint = checkpointRepository.findById(PIPELINE_INCIDENTS)
                .orElseGet(() -> IngestionCheckpoint.builder()
                        .pipelineName(PIPELINE_INCIDENTS)
                        .lastProcessedTimestamp(LocalDateTime.of(1970, 1, 1, 0, 0, 0))
                        .recordsProcessedTotal(0L)
                        .build());

        LocalDateTime lastWatermark = checkpoint.getLastProcessedTimestamp() != null
                ? checkpoint.getLastProcessedTimestamp()
                : LocalDateTime.of(1970, 1, 1, 0, 0, 0);

        int overlapMinutes = ingestionProperties.getIncidents().getOverlapMinutes();
        LocalDateTime effectiveWatermark = lastWatermark.minusMinutes(overlapMinutes);

        // Check for new or modified records before initiating ingestion
        long candidateCount = incidentRepository.countByUpdatedAtGreaterThanEqual(effectiveWatermark);
        if (candidateCount == 0) {
            log.info("[Incidents] No new or modified incidents found since effective watermark {}. Ingestion skipped.",
                    effectiveWatermark);
            return;
        }

        log.info("[Incidents] Found {} candidate incident records (effective watermark: {} with {}m overlap).",
                candidateCount, effectiveWatermark, overlapMinutes);

        int batchSize = ingestionProperties.getBatchSize();
        List<Incident> batch = incidentRepository.findByUpdatedAtGreaterThanEqualOrderByUpdatedAtAscIdAsc(
                effectiveWatermark, PageRequest.of(0, batchSize));

        if (batch.isEmpty()) {
            return;
        }

        String batchId = "BATCH_INC_" + UUID.randomUUID().toString().substring(0, 8);
        List<Map<String, Object>> rows = batch.stream()
                .map(incident -> recordMapper.toSnowflakeIncident(incident, batchId))
                .toList();

        LocalDateTime maxUpdatedAt = batch.stream()
                .map(Incident::getUpdatedAt)
                .max(LocalDateTime::compareTo)
                .orElse(lastWatermark);

        String offsetToken = batchId + "_" + System.currentTimeMillis();

        // Stream to Snowflake Bronze table with retries
        streamWithRetry(snowflakeProperties.getIncidentsTable(), rows, offsetToken);

        // Persist checkpoint only after successful ingestion
        checkpoint.setLastProcessedTimestamp(maxUpdatedAt.isAfter(lastWatermark) ? maxUpdatedAt : lastWatermark);
        checkpoint.setLastBatchId(batchId);
        checkpoint.setRecordsProcessedTotal(
                (checkpoint.getRecordsProcessedTotal() != null ? checkpoint.getRecordsProcessedTotal() : 0L) + batch.size());
        checkpoint.setLastSuccessAt(LocalDateTime.now());
        checkpoint.setUpdatedAt(LocalDateTime.now());
        checkpointRepository.save(checkpoint);

        log.info("[Incidents] Ingestion completed. Streamed {} records (batch: {}). Advanced watermark timestamp to {}.",
                batch.size(), batchId, checkpoint.getLastProcessedTimestamp());
    }

    /**
     * Executes Snowflake streaming insertion with automatic exponential backoff retry.
     */
    private void streamWithRetry(String tableName, List<Map<String, Object>> rows, String offsetToken) {
        int maxRetries = ingestionProperties.getMaxRetries();
        long backoffMs = 1000;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                snowflakeStreamingService.ingestRows(tableName, rows, offsetToken);
                return;
            } catch (Exception e) {
                log.warn("[Retry {}/{}] Ingestion failed for table '{}' (offset: {}): {}",
                        attempt, maxRetries, tableName, offsetToken, e.getMessage());
                if (attempt == maxRetries) {
                    log.error("Exhausted all {} retry attempts for table '{}'. Checkpoint will NOT be advanced.",
                            maxRetries, tableName);
                    throw new RuntimeException("Snowpipe Streaming ingestion permanently failed after " + maxRetries + " attempts", e);
                }
                try {
                    Thread.sleep(backoffMs);
                    backoffMs *= 2;
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Retry wait interrupted", ie);
                }
            }
        }
    }
}

