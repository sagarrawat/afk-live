package com.afklive.streamer.repository;

import com.afklive.streamer.model.StreamIncident;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.List;

public interface StreamIncidentRepository extends JpaRepository<StreamIncident, Long> {

    /** Opens an incident unless one is already open (unique index): 1 means this caller opened it and alerts. */
    @Modifying
    @Transactional
    @Query(value = "INSERT INTO stream_incidents(job_id, kind, target, started_at) VALUES (:jobId, :kind, :target, :at) "
            + "ON CONFLICT (job_id, kind, target) WHERE resolved_at IS NULL DO NOTHING", nativeQuery = true)
    int open(@Param("jobId") Long jobId, @Param("kind") String kind, @Param("target") String target, @Param("at") ZonedDateTime at);

    /** Closes the open incident; 1 means this caller closed it and sends the recovery message. */
    @Modifying
    @Transactional
    @Query(value = "UPDATE stream_incidents SET resolved_at = :at WHERE job_id = :jobId AND kind = :kind AND target = :target "
            + "AND resolved_at IS NULL", nativeQuery = true)
    int resolve(@Param("jobId") Long jobId, @Param("kind") String kind, @Param("target") String target, @Param("at") ZonedDateTime at);

    List<StreamIncident> findAllByJobIdAndResolvedAtIsNull(Long jobId);

    /** RE-10: every open incident of a kind (e.g. runs slower than real time), for the admin console. */
    List<StreamIncident> findAllByKindAndResolvedAtIsNull(String kind);

    List<StreamIncident> findAllByJobIdInAndKindOrderByStartedAtDesc(java.util.Collection<Long> jobIds, String kind);

    List<StreamIncident> findAllByJobIdInAndKindInOrderByStartedAtDesc(java.util.Collection<Long> jobIds, java.util.Collection<String> kinds);
}
