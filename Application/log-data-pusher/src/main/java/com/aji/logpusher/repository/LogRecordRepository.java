package com.aji.logpusher.repository;

import com.aji.logpusher.entity.LogRecord;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LogRecordRepository extends JpaRepository<LogRecord, Long> {

    @Query("SELECT COUNT(l) FROM LogRecord l WHERE l.id > :lastId")
    long countByIdGreaterThan(@Param("lastId") Long lastId);

    @Query("SELECT l FROM LogRecord l WHERE l.id > :lastId ORDER BY l.id ASC")
    List<LogRecord> findByIdGreaterThanOrderByIdAsc(@Param("lastId") Long lastId, Pageable pageable);
}

