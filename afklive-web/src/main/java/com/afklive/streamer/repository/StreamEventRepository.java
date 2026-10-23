package com.afklive.streamer.repository;

import com.afklive.streamer.model.StreamEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;

public interface StreamEventRepository extends JpaRepository<StreamEvent, Long> {

    /** The same failure again: one more repeat on the existing line. */
    @Modifying
    @Transactional
    @Query("UPDATE StreamEvent e SET e.repeats = e.repeats + 1, e.lastAt = :at WHERE e.id = :id")
    int bump(@Param("id") Long id, @Param("at") ZonedDateTime at);

    @Query("SELECT e FROM StreamEvent e WHERE e.jobId IN :jobIds ORDER BY e.id DESC")
    List<StreamEvent> findRecent(@Param("jobIds") Collection<Long> jobIds, Pageable page);

    List<StreamEvent> findAllByJobIdOrderByIdAsc(Long jobId);
}
