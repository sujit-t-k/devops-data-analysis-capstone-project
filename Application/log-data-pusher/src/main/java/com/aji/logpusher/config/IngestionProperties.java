package com.aji.logpusher.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "ingestion")
public class IngestionProperties {

    private int batchSize = 500;
    private int commitTimeoutSeconds = 30;
    private int maxRetries = 3;

    private Scheduler scheduler = new Scheduler();
    private Incidents incidents = new Incidents();

    @Getter
    @Setter
    public static class Scheduler {
        private boolean enabled = true;
        private long fixedDelayMs = 300000; // 5 minutes
    }

    @Getter
    @Setter
    public static class Incidents {
        private int overlapMinutes = 5;
    }
}

