package com.afklive.streamer.repository;

import com.afklive.streamer.model.StreamJobDestination;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;

public interface StreamJobDestinationRepository extends JpaRepository<StreamJobDestination, Long> {

    @Modifying
    @Transactional
    @Query("UPDATE StreamJobDestination d SET d.status = :status, d.restarts = :restarts, d.lastError = :error, d.updatedAt = :now WHERE d.job.id = :jobId AND d.position = :position")
    int updateState(@Param("jobId") Long jobId, @Param("position") int position, @Param("status") String status,
                    @Param("restarts") int restarts, @Param("error") String error, @Param("now") ZonedDateTime now);

    /** RE-8: whether the platform is receiving, and why not. */
    @Modifying
    @Transactional
    @Query("UPDATE StreamJobDestination d SET d.reach = :reach, d.reachReason = :reason WHERE d.job.id = :jobId AND d.position = :position")
    int updateReach(@Param("jobId") Long jobId, @Param("position") int position, @Param("reach") String reach,
                    @Param("reason") String reason);

    /** FO-3: written with the heartbeat; never goes down (a takeover's worker carries on from what was recorded). */
    @Modifying
    @Transactional
    @Query("UPDATE StreamJobDestination d SET d.bytesSent = :bytes WHERE d.job.id = :jobId AND d.position = :position AND d.bytesSent < :bytes")
    int updateBytesSent(@Param("jobId") Long jobId, @Param("position") int position, @Param("bytes") long bytes);
}
