package com.afklive.streamer.repository;

import com.afklive.streamer.model.ScheduledStream;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.ZonedDateTime;
import java.util.List;

@Repository
public interface ScheduledStreamRepository extends JpaRepository<ScheduledStream, Long> {
    List<ScheduledStream> findByUserEmail(String email);
    List<ScheduledStream> findByStatusAndScheduledTimeLessThanEqual(ScheduledStream.StreamStatus status, ZonedDateTime time);

    /** Atomically claims a pending stream so it's started once even if checks overlap. Returns 1 if claimed. */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query("UPDATE ScheduledStream s SET s.status = com.afklive.streamer.model.ScheduledStream.StreamStatus.RUNNING WHERE s.id = :id AND s.status = com.afklive.streamer.model.ScheduledStream.StreamStatus.PENDING")
    int claim(@org.springframework.data.repository.query.Param("id") Long id);
}
