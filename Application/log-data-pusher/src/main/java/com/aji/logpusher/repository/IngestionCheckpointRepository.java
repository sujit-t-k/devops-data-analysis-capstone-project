package com.aji.logpusher.repository;

import com.aji.logpusher.entity.IngestionCheckpoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface IngestionCheckpointRepository extends JpaRepository<IngestionCheckpoint, String> {
}

