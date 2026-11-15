package com.aji.logpusher.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "ingestion_checkpoints")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IngestionCheckpoint {

    @Id
    @Column(name = "pipeline_name", length = 50, nullable = false)
    private String pipelineName;

    @Column(name = "last_processed_id")
    private Long lastProcessedId;

    @Column(name = "last_processed_timestamp")
    private LocalDateTime lastProcessedTimestamp;

    @Column(name = "last_batch_id", length = 64)
    private String lastBatchId;

    @Column(name = "records_processed_total")
    private Long recordsProcessedTotal;

    @Column(name = "last_success_at")
    private LocalDateTime lastSuccessAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}

