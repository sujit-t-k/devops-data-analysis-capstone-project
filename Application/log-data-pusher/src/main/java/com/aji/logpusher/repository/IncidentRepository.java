package com.aji.logpusher.repository;

import com.aji.logpusher.entity.Incident;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface IncidentRepository extends JpaRepository<Incident, Long> {

    @Query("SELECT COUNT(i) FROM Incident i WHERE i.updatedAt >= :watermark")
    long countByUpdatedAtGreaterThanEqual(@Param("watermark") LocalDateTime watermark);

    @Query("SELECT i FROM Incident i WHERE i.updatedAt >= :watermark ORDER BY i.updatedAt ASC, i.id ASC")
    List<Incident> findByUpdatedAtGreaterThanEqualOrderByUpdatedAtAscIdAsc(
            @Param("watermark") LocalDateTime watermark,
            Pageable pageable);
}

