package com.afklive.streamer.repository;

import com.afklive.streamer.model.StreamJob;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface StreamJobRepository extends JpaRepository<StreamJob, Long> {
    // Find the active job for a specific user
    @Query("SELECT j FROM StreamJob j JOIN FETCH j.user WHERE j.user.email = :email AND j.isLive = true")
    Optional<StreamJob> findByUserEmailAndIsLiveTrue(String email);

    @Query("SELECT j FROM StreamJob j JOIN FETCH j.user WHERE j.user.email = :email AND j.isLive = true")
    List<StreamJob> findAllByUserEmailAndIsLiveTrue(String email);

    @Query("SELECT count(j) FROM StreamJob j WHERE j.user.email = :email AND j.isLive = true")
    long countByUserEmailAndIsLiveTrue(String email);

    // Admin: Find ALL live jobs
    @Query("SELECT j FROM StreamJob j JOIN FETCH j.user WHERE j.isLive = true")
    List<StreamJob> findAllByIsLiveTrue();

    @Query(value = "SELECT j FROM StreamJob j JOIN FETCH j.user WHERE j.isLive = true",
           countQuery = "SELECT count(j) FROM StreamJob j WHERE j.isLive = true")
    Page<StreamJob> findAllByIsLiveTrue(Pageable pageable);

    long countByIsLiveTrue();

    // Auto-Reply
    @Query("SELECT j FROM StreamJob j JOIN FETCH j.user WHERE j.isLive = true AND j.autoReplyEnabled = true")
    List<StreamJob> findByIsLiveTrueAndAutoReplyEnabledTrue();

    // History
    Page<StreamJob> findByUserEmailOrderByStartTimeDesc(String email, Pageable pageable);

    List<StreamJob> findAllByIsLiveTrueAndStopAtLessThanEqual(java.time.ZonedDateTime time);

    /**
     * Marks a live job ended. Returns 1 only for the single caller that actually ended it, so the
     * final charge and cleanup run exactly once even if a stop and a process exit race.
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query("UPDATE StreamJob j SET j.isLive = false, j.endTime = :endTime, j.endReason = :reason, j.status = :status, j.desiredState = 'STOP' WHERE j.id = :id AND j.isLive = true")
    int markEnded(@org.springframework.data.repository.query.Param("id") Long id,
                  @org.springframework.data.repository.query.Param("endTime") java.time.ZonedDateTime endTime,
                  @org.springframework.data.repository.query.Param("reason") String reason,
                  @org.springframework.data.repository.query.Param("status") String status);

    default int markEnded(Long id, java.time.ZonedDateTime endTime, String reason) {
        return markEnded(id, endTime, reason, com.afklive.streamer.streaming.StreamStates.ENDED);
    }

    /**
     * Adds a billing increment only if the job is still live and nobody billed it since {@code previous}.
     * Returns 1 when applied; then (and only then) the user's balance should be charged.
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query("UPDATE StreamJob j SET j.accumulatedCost = j.accumulatedCost + :increment, j.lastBillingTime = :now WHERE j.id = :id AND j.isLive = true AND j.lastBillingTime = :previous")
    int addBilling(@org.springframework.data.repository.query.Param("id") Long id,
                   @org.springframework.data.repository.query.Param("increment") double increment,
                   @org.springframework.data.repository.query.Param("now") java.time.ZonedDateTime now,
                   @org.springframework.data.repository.query.Param("previous") java.time.ZonedDateTime previous);

    /** The last charge when a job ends (it is no longer live); also records the total as the job's cost. */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query("UPDATE StreamJob j SET j.accumulatedCost = j.accumulatedCost + :increment, j.cost = j.accumulatedCost + :increment, j.lastBillingTime = :now WHERE j.id = :id AND j.lastBillingTime = :previous")
    int addFinalBilling(@org.springframework.data.repository.query.Param("id") Long id,
                        @org.springframework.data.repository.query.Param("increment") double increment,
                        @org.springframework.data.repository.query.Param("now") java.time.ZonedDateTime now,
                        @org.springframework.data.repository.query.Param("previous") java.time.ZonedDateTime previous);

    // ---- worker coordination (all conditional, so concurrent workers never both own a job) ----

    /** Web asks for a stop; the reason is kept so the worker records it. */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query("UPDATE StreamJob j SET j.desiredState = 'STOP', j.endReason = :reason WHERE j.id = :id AND j.isLive = true")
    int requestStop(@org.springframework.data.repository.query.Param("id") Long id,
                    @org.springframework.data.repository.query.Param("reason") String reason);

    /** Jobs no worker has; a job just moved off {@code worker} is left to the others for a minute (AD-1). */
    @org.springframework.data.jpa.repository.Query("SELECT j.id FROM StreamJob j WHERE j.isLive = true AND j.desiredState = 'RUN' AND j.workerId IS NULL "
            + "AND (j.avoidWorker IS NULL OR j.avoidWorker <> :worker OR j.moveRequestedAt IS NULL OR j.moveRequestedAt < :avoidUntil) ORDER BY j.startTime")
    List<Long> findUnclaimedIds(@org.springframework.data.repository.query.Param("worker") String worker,
                                @org.springframework.data.repository.query.Param("avoidUntil") java.time.ZonedDateTime avoidUntil);

    /** AD-1: releases a job from its worker so another one picks it up (a reconnect of a few seconds). */
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true, clearAutomatically = true)
    @org.springframework.data.jpa.repository.Query("UPDATE StreamJob j SET j.workerId = NULL, j.heartbeatAt = NULL, j.avoidWorker = :worker, "
            + "j.moveRequestedAt = :now WHERE j.id = :id AND j.isLive = true AND j.desiredState = 'RUN' AND j.workerId = :worker")
    int release(@org.springframework.data.repository.query.Param("id") Long id,
                @org.springframework.data.repository.query.Param("worker") String worker,
                @org.springframework.data.repository.query.Param("now") java.time.ZonedDateTime now);

    /** Other workers' jobs that stopped reporting: candidates for takeover. */
    @org.springframework.data.jpa.repository.Query("SELECT j.id FROM StreamJob j WHERE j.isLive = true AND j.desiredState = 'RUN' AND j.workerId IS NOT NULL AND j.workerId <> :worker AND (j.heartbeatAt IS NULL OR j.heartbeatAt < :cutoff)")
    List<Long> findOrphanIds(@org.springframework.data.repository.query.Param("cutoff") java.time.ZonedDateTime cutoff,
                             @org.springframework.data.repository.query.Param("worker") String worker);

    /** Stop requested but no worker is alive to carry it out: the web side ends these. */
    @org.springframework.data.jpa.repository.Query("SELECT j.id FROM StreamJob j WHERE j.isLive = true AND j.desiredState = 'STOP' AND (j.workerId IS NULL OR j.heartbeatAt IS NULL OR j.heartbeatAt < :cutoff)")
    List<Long> findAbandonedStopIds(@org.springframework.data.repository.query.Param("cutoff") java.time.ZonedDateTime cutoff);

    /** A restarted worker resumes its own jobs (same worker id). */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query("UPDATE StreamJob j SET j.heartbeatAt = :now WHERE j.id = :id AND j.workerId = :worker AND j.isLive = true AND j.desiredState = 'RUN'")
    int resumeOwn(@org.springframework.data.repository.query.Param("id") Long id,
                  @org.springframework.data.repository.query.Param("worker") String worker,
                  @org.springframework.data.repository.query.Param("now") java.time.ZonedDateTime now);

    /** First time the stream is actually on air: billing starts now (not while files were downloading). */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query("UPDATE StreamJob j SET j.status = 'LIVE', j.lastBillingTime = :now WHERE j.id = :id AND j.isLive = true AND j.status = 'STARTING'")
    int markFirstLive(@org.springframework.data.repository.query.Param("id") Long id,
                      @org.springframework.data.repository.query.Param("now") java.time.ZonedDateTime now);

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query("UPDATE StreamJob j SET j.stopAt = :stopAt WHERE j.id = :id AND j.isLive = true")
    int updateStopAt(@org.springframework.data.repository.query.Param("id") Long id,
                     @org.springframework.data.repository.query.Param("stopAt") java.time.ZonedDateTime stopAt);

    /** Jobs that are on air (billable). */
    @org.springframework.data.jpa.repository.Query("SELECT j FROM StreamJob j WHERE j.isLive = true AND j.status IN ('LIVE', 'RECONNECTING')")
    List<StreamJob> findAllOnAir();

    List<StreamJob> findAllByWorkerIdAndIsLiveTrue(String workerId);

    /** Studio state for the health panel and remote: what's on screen and playing. Only the owning worker writes it. */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query("UPDATE StreamJob j SET j.scene = :scene, j.inputLive = :inputLive, j.nowPlaying = :nowPlaying WHERE j.id = :id AND j.workerId = :worker AND j.isLive = true")
    int reportStudio(@org.springframework.data.repository.query.Param("id") Long id,
                     @org.springframework.data.repository.query.Param("worker") String worker,
                     @org.springframework.data.repository.query.Param("scene") String scene,
                     @org.springframework.data.repository.query.Param("inputLive") Boolean inputLive,
                     @org.springframework.data.repository.query.Param("nowPlaying") String nowPlaying);

    List<StreamJob> findAllByStreamIdAndIsLiveTrue(Long streamId);

    java.util.Optional<StreamJob> findFirstByStreamIdOrderByStartTimeDesc(Long streamId);

    List<StreamJob> findTop20ByStreamIdOrderByStartTimeDesc(Long streamId);

    /** RE-4: runs of a stream that were on air at some point after {@code from}. */
    @Query("SELECT j FROM StreamJob j WHERE j.streamId = :streamId AND j.startTime IS NOT NULL AND (j.endTime IS NULL OR j.endTime > :from)")
    List<StreamJob> findRunsSince(@org.springframework.data.repository.query.Param("streamId") Long streamId, @org.springframework.data.repository.query.Param("from") java.time.ZonedDateTime from);

    /** Free-tier capacity is platform-wide: starts that count it are serialized with this lock. */
    @org.springframework.data.jpa.repository.Query(value = "SELECT pg_advisory_xact_lock(7391001)", nativeQuery = true)
    Object lockFreeCapacity();

    /** The web app keeps the run's spec current with live edits, so a takeover or restart builds the latest. */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query("UPDATE StreamJob j SET j.spec = :spec WHERE j.id = :id AND j.isLive = true")
    int updateSpec(@org.springframework.data.repository.query.Param("id") Long id,
                   @org.springframework.data.repository.query.Param("spec") String spec);

    /** Highest command id this run has applied (worker, fenced by ownership). */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query("UPDATE StreamJob j SET j.appliedCommandId = :applied WHERE j.id = :id AND j.workerId = :worker AND j.appliedCommandId < :applied")
    int saveAppliedCommand(@org.springframework.data.repository.query.Param("id") Long id,
                           @org.springframework.data.repository.query.Param("worker") String worker,
                           @org.springframework.data.repository.query.Param("applied") long applied);

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query("UPDATE StreamJob j SET j.workerId = :worker, j.heartbeatAt = :now WHERE j.id = :id AND j.isLive = true AND j.desiredState = 'RUN' AND j.workerId IS NULL")
    int claim(@org.springframework.data.repository.query.Param("id") Long id,
              @org.springframework.data.repository.query.Param("worker") String worker,
              @org.springframework.data.repository.query.Param("now") java.time.ZonedDateTime now);

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query("UPDATE StreamJob j SET j.workerId = :worker, j.heartbeatAt = :now, j.restarts = j.restarts + 1 WHERE j.id = :id AND j.isLive = true AND j.desiredState = 'RUN' AND (j.heartbeatAt IS NULL OR j.heartbeatAt < :cutoff)")
    int takeOver(@org.springframework.data.repository.query.Param("id") Long id,
                 @org.springframework.data.repository.query.Param("worker") String worker,
                 @org.springframework.data.repository.query.Param("now") java.time.ZonedDateTime now,
                 @org.springframework.data.repository.query.Param("cutoff") java.time.ZonedDateTime cutoff);

    /**
     * Worker reports health. Fenced: it only succeeds if the row still holds the heartbeat this worker
     * last wrote, so if anything else (a takeover, or a duplicate process with the same id) touched the
     * job, this returns 0 and the worker must stop its copy.
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query("UPDATE StreamJob j SET j.heartbeatAt = :now, j.status = :status, j.bitrateKbps = :bitrate, j.fps = :fps, j.speed = :speed, j.droppedFrames = :drops, j.lastError = :error WHERE j.id = :id AND j.workerId = :worker AND j.isLive = true AND j.heartbeatAt = :expected")
    int heartbeat(@org.springframework.data.repository.query.Param("id") Long id,
                  @org.springframework.data.repository.query.Param("worker") String worker,
                  @org.springframework.data.repository.query.Param("expected") java.time.ZonedDateTime expected,
                  @org.springframework.data.repository.query.Param("now") java.time.ZonedDateTime now,
                  @org.springframework.data.repository.query.Param("status") String status,
                  @org.springframework.data.repository.query.Param("bitrate") Double bitrate,
                  @org.springframework.data.repository.query.Param("fps") Double fps,
                  @org.springframework.data.repository.query.Param("speed") Double speed,
                  @org.springframework.data.repository.query.Param("drops") Long drops,
                  @org.springframework.data.repository.query.Param("error") String error);

    /**
     * RE-7: streams that should be running but no worker is looking after: assigned to a worker that
     * stopped reporting, or waiting for a worker since before {@code waitingSince}.
     */
    @Query("SELECT count(j) FROM StreamJob j WHERE j.isLive = true AND j.desiredState = 'RUN' AND ("
            + "(j.workerId IS NOT NULL AND (j.heartbeatAt IS NULL OR j.heartbeatAt < :staleBefore)) "
            + "OR (j.workerId IS NULL AND j.startTime < :waitingSince AND (j.moveRequestedAt IS NULL OR j.moveRequestedAt < :waitingSince)))")
    long countUnattended(@org.springframework.data.repository.query.Param("staleBefore") java.time.ZonedDateTime staleBefore,
                         @org.springframework.data.repository.query.Param("waitingSince") java.time.ZonedDateTime waitingSince);

    /** RE-7: whether a worker is receiving a phone's feed from the ingest server right now. */
    boolean existsByIsLiveTrueAndInputLiveTrueAndHeartbeatAtAfter(java.time.ZonedDateTime since);

    /** AD-2: streaming seconds inside [from, to) across every run (for cost per stream hour). */
    @Query(value = "SELECT COALESCE(SUM(EXTRACT(EPOCH FROM (LEAST(COALESCE(end_time, :to), :to) - GREATEST(start_time, :from)))), 0) "
            + "FROM stream_jobs WHERE start_time IS NOT NULL AND start_time < :to AND (end_time IS NULL OR end_time > :from)", nativeQuery = true)
    double streamSecondsBetween(@org.springframework.data.repository.query.Param("from") java.time.ZonedDateTime from,
                                @org.springframework.data.repository.query.Param("to") java.time.ZonedDateTime to);
}
