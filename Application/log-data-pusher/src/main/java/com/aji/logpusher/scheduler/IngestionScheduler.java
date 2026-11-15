package com.aji.logpusher.scheduler;

import com.aji.logpusher.service.IngestionOrchestratorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "ingestion.scheduler", name = "enabled", havingValue = "true", matchIfMissing = true)
public class IngestionScheduler {

    private final IngestionOrchestratorService ingestionOrchestratorService;

    /**
     * Executes every 5 minutes (default: 300000 ms), with an initial delay of 10 seconds to allow clean startup.
     */
    @Scheduled(
            fixedDelayString = "${ingestion.scheduler.fixed-delay-ms:300000}",
            initialDelayString = "${ingestion.scheduler.initial-delay-ms:10000}"
    )
    public void scheduleIngestion() {
        log.info("Triggered scheduled Snowflake ingestion run...");
        try {
            ingestionOrchestratorService.runIngestionCycle();
        } catch (Exception e) {
            log.error("Scheduled ingestion failed: {}", e.getMessage(), e);
        }
    }
}

