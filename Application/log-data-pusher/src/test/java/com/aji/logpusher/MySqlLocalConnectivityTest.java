package com.aji.logpusher;

import com.aji.logpusher.entity.IngestionCheckpoint;
import com.aji.logpusher.repository.IncidentRepository;
import com.aji.logpusher.repository.IngestionCheckpointRepository;
import com.aji.logpusher.repository.LogRecordRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "ingestion.scheduler.enabled=false"
})
class MySqlLocalConnectivityTest {

    @Autowired
    private LogRecordRepository logRecordRepository;

    @Autowired
    private IncidentRepository incidentRepository;

    @Autowired
    private IngestionCheckpointRepository checkpointRepository;

    @Test
    void testMySqlConnectivityAndRepositories() {
        // Verify reading from MySQL log_records table
        long logCount = logRecordRepository.count();
        System.out.println(">>> Verified MySQL connection! Total log_records: " + logCount);
        assertTrue(logCount >= 0);

        // Verify reading from MySQL incidents table
        long incidentCount = incidentRepository.count();
        System.out.println(">>> Total incidents in MySQL: " + incidentCount);
        assertTrue(incidentCount >= 0);

        // Verify creating / updating checkpoint in the new ingestion_checkpoints table
        IngestionCheckpoint checkpoint = IngestionCheckpoint.builder()
                .pipelineName("TEST_PIPELINE")
                .lastProcessedId(100L)
                .lastProcessedTimestamp(LocalDateTime.now())
                .lastBatchId("TEST_BATCH_001")
                .recordsProcessedTotal(50L)
                .lastSuccessAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        checkpointRepository.save(checkpoint);

        IngestionCheckpoint loaded = checkpointRepository.findById("TEST_PIPELINE").orElse(null);
        assertNotNull(loaded);
        System.out.println(">>> Verified ingestion_checkpoints table created and functional! Loaded pipeline: " + loaded.getPipelineName());

        // Cleanup test checkpoint
        checkpointRepository.deleteById("TEST_PIPELINE");
    }
}

