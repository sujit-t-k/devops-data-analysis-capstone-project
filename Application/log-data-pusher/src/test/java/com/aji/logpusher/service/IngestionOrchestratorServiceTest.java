package com.aji.logpusher.service;

import com.aji.logpusher.config.IngestionProperties;
import com.aji.logpusher.config.SnowflakeProperties;
import com.aji.logpusher.entity.IngestionCheckpoint;
import com.aji.logpusher.entity.LogRecord;
import com.aji.logpusher.mapper.RecordMapper;
import com.aji.logpusher.repository.IncidentRepository;
import com.aji.logpusher.repository.IngestionCheckpointRepository;
import com.aji.logpusher.repository.LogRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IngestionOrchestratorServiceTest {

    @Mock
    private LogRecordRepository logRecordRepository;

    @Mock
    private IncidentRepository incidentRepository;

    @Mock
    private IngestionCheckpointRepository checkpointRepository;

    @Mock
    private SnowflakeStreamingService snowflakeStreamingService;

    @Mock
    private RecordMapper recordMapper;

    @Mock
    private SnowflakeProperties snowflakeProperties;

    private IngestionProperties ingestionProperties;

    private IngestionOrchestratorService orchestratorService;

    @BeforeEach
    void setUp() {
        ingestionProperties = new IngestionProperties();
        ingestionProperties.setBatchSize(100);
        ingestionProperties.setMaxRetries(2);

        orchestratorService = new IngestionOrchestratorService(
                logRecordRepository,
                incidentRepository,
                checkpointRepository,
                snowflakeStreamingService,
                recordMapper,
                snowflakeProperties,
                ingestionProperties
        );
    }

    @Test
    void testIngestLogRecords_NoNewRecords_DoesNothing() {
        when(checkpointRepository.findById("LOG_RECORDS")).thenReturn(Optional.of(
                IngestionCheckpoint.builder().pipelineName("LOG_RECORDS").lastProcessedId(50L).build()));
        when(logRecordRepository.countByIdGreaterThan(50L)).thenReturn(0L);

        orchestratorService.ingestLogRecords();

        verify(snowflakeStreamingService, never()).ingestRows(any(), any(), any());
        verify(checkpointRepository, never()).save(any());
    }

    @Test
    void testIngestLogRecords_Success_PersistsCheckpoint() {
        when(checkpointRepository.findById("LOG_RECORDS")).thenReturn(Optional.of(
                IngestionCheckpoint.builder().pipelineName("LOG_RECORDS").lastProcessedId(10L).recordsProcessedTotal(10L).build()));
        when(logRecordRepository.countByIdGreaterThan(10L)).thenReturn(1L);

        LogRecord record = new LogRecord();
        record.setId(11L);
        record.setEventTimestamp(LocalDateTime.now());

        when(logRecordRepository.findByIdGreaterThanOrderByIdAsc(eq(10L), any(Pageable.class)))
                .thenReturn(List.of(record));
        when(logRecordRepository.findByIdGreaterThanOrderByIdAsc(eq(11L), any(Pageable.class)))
                .thenReturn(Collections.emptyList());

        when(recordMapper.toSnowflakeLogRecord(eq(record), anyString())).thenReturn(Map.of("ID", "11"));
        when(snowflakeProperties.getLogRecordsTable()).thenReturn("LOG_RECORDS");

        doNothing().when(snowflakeStreamingService).ingestRows(eq("LOG_RECORDS"), any(), eq("11"));

        orchestratorService.ingestLogRecords();

        verify(snowflakeStreamingService, times(1)).ingestRows(eq("LOG_RECORDS"), any(), eq("11"));
        verify(checkpointRepository, times(1)).save(any(IngestionCheckpoint.class));
    }

    @Test
    void testIngestLogRecords_Failure_RetriesAndDoesNotAdvanceCheckpoint() {
        when(checkpointRepository.findById("LOG_RECORDS")).thenReturn(Optional.of(
                IngestionCheckpoint.builder().pipelineName("LOG_RECORDS").lastProcessedId(10L).recordsProcessedTotal(10L).build()));
        when(logRecordRepository.countByIdGreaterThan(10L)).thenReturn(1L);

        LogRecord record = new LogRecord();
        record.setId(11L);

        when(logRecordRepository.findByIdGreaterThanOrderByIdAsc(eq(10L), any(Pageable.class)))
                .thenReturn(List.of(record));
        when(recordMapper.toSnowflakeLogRecord(eq(record), anyString())).thenReturn(Map.of("ID", "11"));
        when(snowflakeProperties.getLogRecordsTable()).thenReturn("LOG_RECORDS");

        // Simulate permanent Snowflake ingestion failure
        doThrow(new RuntimeException("Simulated Snowflake connection reset"))
                .when(snowflakeStreamingService).ingestRows(eq("LOG_RECORDS"), any(), eq("11"));

        assertThrows(RuntimeException.class, () -> orchestratorService.ingestLogRecords());

        // Verified retried exactly 2 times
        verify(snowflakeStreamingService, times(2)).ingestRows(eq("LOG_RECORDS"), any(), eq("11"));
        // Checkpoint must NOT be saved on failure
        verify(checkpointRepository, never()).save(any());
    }
}

