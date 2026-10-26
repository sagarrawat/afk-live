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

    /** GL-1: a user's live runs, to keep destination tests off keys that are on air. */
    @Query("SELECT j FROM StreamJob j WHERE j.user.id = :userId AND j.isLive = true")
    List<StreamJob> findAllLiveByUserId(@org.springframework.data.repository.query.Param("userId") Long userId);

    /** GL-6: every go-live is a job, so this is how many times the account has gone live (1 during the first). */
    @Query("SELECT count(j) FROM StreamJob j WHERE j.user.id = :userId")
    long countGoLives(@org.springframework.data.repository.query.Param("userId") Long userId);

    // Admin: Find ALL live jobs
    @Query("SELECT j FROM StreamJob j JOIN FETCH j.user WHERE j.isLive = true")
    List<StreamJob> findAllByIsLiveTrue();


    long countByIsLiveTrue();

    /** RE-8: runs a platform is receiving right now (a destination ON, worker still reporting): the homepage's "live now". */
    @Query(value = "SELECT count(DISTINCT j.id) FROM stream_jobs j JOIN stream_job_destinations d ON d.job_id = j.id "
            + "WHERE j.is_live = true AND j.heartbeat_at > :since AND d.reach = 'ON'", nativeQuery = true)
    long countReachingSince(@org.springframework.data.repository.query.Param("since") java.time.ZonedDateTime since);


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
            + "j.moveRequestedAt = :now, j.handoverFrom = NULL, j.handoverTo = NULL, j.handoverOfferedAt = NULL, j.handoverPosition = NULL, j.handoverClaimedAt = NULL, j.handoverBeatAt = NULL, j.handoverReadyAt = NULL WHERE j.id = :id AND j.isLive = true AND j.desiredState = 'RUN' AND j.workerId = :worker")
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

    /** A restarted worker resumes its own jobs (same worker id); a handover it offered before restarting is dropped. */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query("UPDATE StreamJob j SET j.heartbeatAt = :now, j.handoverFrom = NULL, j.handoverTo = NULL, j.handoverOfferedAt = NULL, j.handoverPosition = NULL, j.handoverClaimedAt = NULL, j.handoverBeatAt = NULL, j.handoverReadyAt = NULL WHERE j.id = :id AND j.workerId = :worker AND j.isLive = true AND j.desiredState = 'RUN'")
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
    @org.springframework.data.jpa.repository.Query("UPDATE StreamJob j SET j.workerId = :worker, j.heartbeatAt = :now, j.restarts = j.restarts + 1, j.handoverFrom = NULL, j.handoverTo = NULL, j.handoverOfferedAt = NULL, j.handoverPosition = NULL, j.handoverClaimedAt = NULL, j.handoverBeatAt = NULL, j.handoverReadyAt = NULL WHERE j.id = :id AND j.isLive = true AND j.desiredState = 'RUN' AND (j.heartbeatAt IS NULL OR j.heartbeatAt < :cutoff)")
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

    // ---- OPS-3: planned handover to another worker (see streaming.Handover). Every step is conditional. ----

    String CLEAR_HANDOVER = "j.handoverFrom = NULL, j.handoverTo = NULL, j.handoverOfferedAt = NULL, j.handoverPosition = NULL, "
            + "j.handoverClaimedAt = NULL, j.handoverBeatAt = NULL, j.handoverReadyAt = NULL";

    /** A draining owner offers one of its streams, with its playlist position. */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @Query("UPDATE StreamJob j SET j.handoverFrom = :worker, j.handoverOfferedAt = :now, j.handoverPosition = :position, "
            + "j.handoverTo = NULL, j.handoverClaimedAt = NULL, j.handoverBeatAt = NULL, j.handoverReadyAt = NULL "
            + "WHERE j.id = :id AND j.workerId = :worker AND j.isLive = true AND j.desiredState = 'RUN' AND j.handoverFrom IS NULL")
    int offerHandover(@org.springframework.data.repository.query.Param("id") Long id,
                      @org.springframework.data.repository.query.Param("worker") String worker,
                      @org.springframework.data.repository.query.Param("now") java.time.ZonedDateTime now,
                      @org.springframework.data.repository.query.Param("position") String position);

    /** Keeps an unclaimed offer's position current. */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @Query("UPDATE StreamJob j SET j.handoverPosition = :position "
            + "WHERE j.id = :id AND j.workerId = :worker AND j.handoverFrom = :worker AND j.handoverTo IS NULL AND j.isLive = true")
    int refreshHandoverPosition(@org.springframework.data.repository.query.Param("id") Long id,
                                @org.springframework.data.repository.query.Param("worker") String worker,
                                @org.springframework.data.repository.query.Param("position") String position);

    /** The owner stops draining: offers nobody claimed yet are taken back (a claimed one finishes or times out). */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @Query("UPDATE StreamJob j SET " + CLEAR_HANDOVER
            + " WHERE j.workerId = :worker AND j.handoverFrom = :worker AND j.handoverTo IS NULL AND j.isLive = true")
    int withdrawHandoverOffers(@org.springframework.data.repository.query.Param("worker") String worker);

    /** Streams other workers offer and nobody claimed yet, oldest offer first. */
    @Query("SELECT j.id FROM StreamJob j WHERE j.isLive = true AND j.desiredState = 'RUN' "
            + "AND j.handoverFrom IS NOT NULL AND j.handoverFrom = j.workerId AND j.handoverTo IS NULL AND j.workerId <> :worker ORDER BY j.handoverOfferedAt")
    List<Long> findHandoverOfferIds(@org.springframework.data.repository.query.Param("worker") String worker);

    /** A worker claims an offered stream; its first beat is {@code now}. */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @Query("UPDATE StreamJob j SET j.handoverTo = :worker, j.handoverClaimedAt = :now, j.handoverBeatAt = :now, j.handoverReadyAt = NULL "
            + "WHERE j.id = :id AND j.isLive = true AND j.desiredState = 'RUN' AND j.handoverFrom IS NOT NULL AND j.handoverFrom = j.workerId "
            + "AND j.handoverTo IS NULL AND j.workerId <> :worker")
    int claimHandover(@org.springframework.data.repository.query.Param("id") Long id,
                      @org.springframework.data.repository.query.Param("worker") String worker,
                      @org.springframework.data.repository.query.Param("now") java.time.ZonedDateTime now);

    /**
     * The claimer's beat, fenced like the heartbeat; with {@code ready} it also says its copy is on air
     * everywhere it publishes. Returns 0 once the claim is gone (dropped, or completed: then it owns the stream).
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @Query("UPDATE StreamJob j SET j.handoverBeatAt = :now, "
            + "j.handoverReadyAt = CASE WHEN :ready = true AND j.handoverReadyAt IS NULL THEN :now ELSE j.handoverReadyAt END "
            + "WHERE j.id = :id AND j.handoverTo = :worker AND j.handoverBeatAt = :expected AND j.workerId <> :worker "
            + "AND j.isLive = true AND j.desiredState = 'RUN'")
    int handoverBeat(@org.springframework.data.repository.query.Param("id") Long id,
                     @org.springframework.data.repository.query.Param("worker") String worker,
                     @org.springframework.data.repository.query.Param("expected") java.time.ZonedDateTime expected,
                     @org.springframework.data.repository.query.Param("now") java.time.ZonedDateTime now,
                     @org.springframework.data.repository.query.Param("ready") boolean ready);

    /**
     * The owner hands the stream over: the claimer becomes the owner, its last beat becomes the heartbeat
     * it fences on, and the ingest its copy publishes to is recorded. Only while the claim is ready and
     * its beat is newer than {@code freshAfter}, so a claimer that died never gets the stream.
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @Query("UPDATE StreamJob j SET j.workerId = j.handoverTo, j.heartbeatAt = j.handoverBeatAt, j.youtubeIngest = :ingest, "
            + CLEAR_HANDOVER + " WHERE j.id = :id AND j.workerId = :from AND j.handoverFrom = :from AND j.handoverTo = :to "
            + "AND j.handoverReadyAt IS NOT NULL AND j.handoverBeatAt > :freshAfter AND j.isLive = true AND j.desiredState = 'RUN'")
    int completeHandover(@org.springframework.data.repository.query.Param("id") Long id,
                         @org.springframework.data.repository.query.Param("from") String from,
                         @org.springframework.data.repository.query.Param("to") String to,
                         @org.springframework.data.repository.query.Param("freshAfter") java.time.ZonedDateTime freshAfter,
                         @org.springframework.data.repository.query.Param("ingest") String ingest);

    /**
     * The claimer gives up (not on air in time, its copy stopped, or it is shutting down). The offer goes
     * too, so the owner moves on to its next stream and offers this one again later.
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @Query("UPDATE StreamJob j SET " + CLEAR_HANDOVER + " WHERE j.id = :id AND j.handoverTo = :worker")
    int abandonHandoverClaim(@org.springframework.data.repository.query.Param("id") Long id,
                             @org.springframework.data.repository.query.Param("worker") String worker);

    /** The owner drops a handover whose claimer stopped beating (it died). */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @Query("UPDATE StreamJob j SET " + CLEAR_HANDOVER
            + " WHERE j.id = :id AND j.workerId = :owner AND j.handoverFrom = :owner AND j.handoverTo IS NOT NULL "
            + "AND (j.handoverBeatAt IS NULL OR j.handoverBeatAt < :staleBefore)")
    int dropStaleHandoverClaim(@org.springframework.data.repository.query.Param("id") Long id,
                               @org.springframework.data.repository.query.Param("owner") String owner,
                               @org.springframework.data.repository.query.Param("staleBefore") java.time.ZonedDateTime staleBefore);

    /** The owner records which YouTube ingest it publishes to (after moving back to the primary). */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @Query("UPDATE StreamJob j SET j.youtubeIngest = :ingest WHERE j.id = :id AND j.workerId = :worker AND j.isLive = true")
    int saveYoutubeIngest(@org.springframework.data.repository.query.Param("id") Long id,
                          @org.springframework.data.repository.query.Param("worker") String worker,
                          @org.springframework.data.repository.query.Param("ingest") String ingest);

    /**
     * A draining worker shutting down before it could hand a stream over lets go of it, so another
     * worker claims it on its next pass (seconds) instead of waiting for the takeover timeout.
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @Query("UPDATE StreamJob j SET j.workerId = NULL, j.heartbeatAt = NULL, " + CLEAR_HANDOVER
            + " WHERE j.id = :id AND j.workerId = :worker AND j.isLive = true AND j.desiredState = 'RUN'")
    int releaseOnShutdown(@org.springframework.data.repository.query.Param("id") Long id,
                          @org.springframework.data.repository.query.Param("worker") String worker);

    // ---- FO-8: worker pools. The same conditions go into the lookup and into the fenced claim itself. ----

    /** Hard rule: a worker that may not read the phone ingest takes only runs known not to need it. */
    String POOL_FILTER = "(:mayIngest = true OR j.placement IN ('PLAYOUT', 'PLAYLIST', 'OTHER'))";
    /** The pool this run prefers, from the claiming worker's configuration ('' = none, or the claimer's own). */
    String PREFERRED_POOL = "(CASE j.placement WHEN 'PLAYOUT' THEN CAST(:preferPlayout AS VARCHAR) "
            + "WHEN 'PLAYLIST' THEN CAST(:preferPlaylist AS VARCHAR) WHEN 'OTHER' THEN CAST(:preferOther AS VARCHAR) ELSE '' END)";
    /** A worker of the preferred pool that could start it now: reported recently, not draining, a free slot, CPU to spare. */
    String PREFERRED_AVAILABLE = "EXISTS (SELECT 1 FROM stream_workers w WHERE COALESCE(w.pool, 'main') = " + PREFERRED_POOL
            + " AND w.heartbeat_at > :availableAfter AND w.draining_since IS NULL AND w.streams < w.capacity "
            + "AND (w.cpu_percent IS NULL OR w.cpu_percent < :maxCpuPercent))";
    /*
     * Soft rule: leave a run to its preferred pool while that pool can take it, until it has waited long enough
     * (since it went live or was moved; for a handover, since it was offered). Annotation values must be
     * constants, hence spelled out twice.
     */
    String NEW_SINCE = "GREATEST(j.start_time, j.move_requested_at)";
    String CLAIMABLE = "j.is_live = true AND j.desired_state = 'RUN' AND j.worker_id IS NULL "
            + "AND (j.avoid_worker IS NULL OR j.avoid_worker <> :worker OR j.move_requested_at IS NULL OR j.move_requested_at < :avoidUntil) "
            + "AND " + POOL_FILTER + " AND (" + PREFERRED_POOL + " = '' OR " + NEW_SINCE + " IS NULL OR " + NEW_SINCE
            + " < :waitedBefore OR NOT " + PREFERRED_AVAILABLE + ")";
    String OFFERED = "j.is_live = true AND j.desired_state = 'RUN' AND j.handover_from IS NOT NULL AND j.handover_from = j.worker_id "
            + "AND j.handover_to IS NULL AND j.worker_id <> :worker AND " + POOL_FILTER + " AND (" + PREFERRED_POOL
            + " = '' OR j.handover_offered_at IS NULL OR j.handover_offered_at < :waitedBefore OR NOT " + PREFERRED_AVAILABLE + ")";

    /** New streams this worker may claim now (pool rules applied), oldest first; like {@link #findUnclaimedIds}. */
    @Query(value = "SELECT j.id FROM stream_jobs j WHERE " + CLAIMABLE + " ORDER BY j.start_time", nativeQuery = true)
    List<Long> findClaimableIds(@org.springframework.data.repository.query.Param("worker") String worker,
                                @org.springframework.data.repository.query.Param("avoidUntil") java.time.ZonedDateTime avoidUntil,
                                @org.springframework.data.repository.query.Param("mayIngest") boolean mayIngest,
                                @org.springframework.data.repository.query.Param("preferPlayout") String preferPlayout,
                                @org.springframework.data.repository.query.Param("preferPlaylist") String preferPlaylist,
                                @org.springframework.data.repository.query.Param("preferOther") String preferOther,
                                @org.springframework.data.repository.query.Param("waitedBefore") java.time.ZonedDateTime waitedBefore,
                                @org.springframework.data.repository.query.Param("availableAfter") java.time.ZonedDateTime availableAfter,
                                @org.springframework.data.repository.query.Param("maxCpuPercent") double maxCpuPercent);

    default List<Long> findClaimableIds(String worker, java.time.ZonedDateTime avoidUntil, com.afklive.streamer.streaming.WorkerPools.Claim c) {
        return findClaimableIds(worker, avoidUntil, c.mayIngest(), c.preferPlayout(), c.preferPlaylist(), c.preferOther(),
                c.waitedBefore(), c.availableAfter(), c.maxCpuPercent());
    }

    /** {@link #claim}, fenced on the same pool rules as {@link #findClaimableIds}, so a stale lookup never claims what this worker mustn't run. */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @Query(value = "UPDATE stream_jobs j SET worker_id = :worker, heartbeat_at = :now WHERE j.id = :id AND " + CLAIMABLE, nativeQuery = true)
    int claimFor(@org.springframework.data.repository.query.Param("id") Long id,
                 @org.springframework.data.repository.query.Param("worker") String worker,
                 @org.springframework.data.repository.query.Param("now") java.time.ZonedDateTime now,
                 @org.springframework.data.repository.query.Param("avoidUntil") java.time.ZonedDateTime avoidUntil,
                 @org.springframework.data.repository.query.Param("mayIngest") boolean mayIngest,
                 @org.springframework.data.repository.query.Param("preferPlayout") String preferPlayout,
                 @org.springframework.data.repository.query.Param("preferPlaylist") String preferPlaylist,
                 @org.springframework.data.repository.query.Param("preferOther") String preferOther,
                 @org.springframework.data.repository.query.Param("waitedBefore") java.time.ZonedDateTime waitedBefore,
                 @org.springframework.data.repository.query.Param("availableAfter") java.time.ZonedDateTime availableAfter,
                 @org.springframework.data.repository.query.Param("maxCpuPercent") double maxCpuPercent);

    default int claimFor(Long id, String worker, java.time.ZonedDateTime now, java.time.ZonedDateTime avoidUntil,
                         com.afklive.streamer.streaming.WorkerPools.Claim c) {
        return claimFor(id, worker, now, avoidUntil, c.mayIngest(), c.preferPlayout(), c.preferPlaylist(), c.preferOther(),
                c.waitedBefore(), c.availableAfter(), c.maxCpuPercent());
    }

    /** {@link #findOrphanIds} that this worker may run (hard rule only: a stream without a live owner is rescued at once). */
    @Query(value = "SELECT j.id FROM stream_jobs j WHERE j.is_live = true AND j.desired_state = 'RUN' AND j.worker_id IS NOT NULL "
            + "AND j.worker_id <> :worker AND (j.heartbeat_at IS NULL OR j.heartbeat_at < :cutoff) AND " + POOL_FILTER, nativeQuery = true)
    List<Long> findOrphanIdsFor(@org.springframework.data.repository.query.Param("cutoff") java.time.ZonedDateTime cutoff,
                                @org.springframework.data.repository.query.Param("worker") String worker,
                                @org.springframework.data.repository.query.Param("mayIngest") boolean mayIngest);

    /** {@link #takeOver}, fenced on the hard pool rule. */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @Query(value = "UPDATE stream_jobs j SET worker_id = :worker, heartbeat_at = :now, restarts = j.restarts + 1, handover_from = NULL, "
            + "handover_to = NULL, handover_offered_at = NULL, handover_position = NULL, handover_claimed_at = NULL, handover_beat_at = NULL, "
            + "handover_ready_at = NULL WHERE j.id = :id AND j.is_live = true AND j.desired_state = 'RUN' "
            + "AND (j.heartbeat_at IS NULL OR j.heartbeat_at < :cutoff) AND " + POOL_FILTER, nativeQuery = true)
    int takeOverFor(@org.springframework.data.repository.query.Param("id") Long id,
                    @org.springframework.data.repository.query.Param("worker") String worker,
                    @org.springframework.data.repository.query.Param("now") java.time.ZonedDateTime now,
                    @org.springframework.data.repository.query.Param("cutoff") java.time.ZonedDateTime cutoff,
                    @org.springframework.data.repository.query.Param("mayIngest") boolean mayIngest);

    /** {@link #findHandoverOfferIds} with the pool rules (a remote deploy's offers go to the same pool's new version first). */
    @Query(value = "SELECT j.id FROM stream_jobs j WHERE " + OFFERED + " ORDER BY j.handover_offered_at", nativeQuery = true)
    List<Long> findHandoverOfferIdsFor(@org.springframework.data.repository.query.Param("worker") String worker,
                                       @org.springframework.data.repository.query.Param("mayIngest") boolean mayIngest,
                                       @org.springframework.data.repository.query.Param("preferPlayout") String preferPlayout,
                                       @org.springframework.data.repository.query.Param("preferPlaylist") String preferPlaylist,
                                       @org.springframework.data.repository.query.Param("preferOther") String preferOther,
                                       @org.springframework.data.repository.query.Param("waitedBefore") java.time.ZonedDateTime waitedBefore,
                                       @org.springframework.data.repository.query.Param("availableAfter") java.time.ZonedDateTime availableAfter,
                                       @org.springframework.data.repository.query.Param("maxCpuPercent") double maxCpuPercent);

    default List<Long> findHandoverOfferIdsFor(String worker, com.afklive.streamer.streaming.WorkerPools.Claim c) {
        return findHandoverOfferIdsFor(worker, c.mayIngest(), c.preferPlayout(), c.preferPlaylist(), c.preferOther(),
                c.waitedBefore(), c.availableAfter(), c.maxCpuPercent());
    }

    /** {@link #claimHandover}, fenced on the pool rules. */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @Query(value = "UPDATE stream_jobs j SET handover_to = :worker, handover_claimed_at = :now, handover_beat_at = :now, handover_ready_at = NULL "
            + "WHERE j.id = :id AND " + OFFERED, nativeQuery = true)
    int claimHandoverFor(@org.springframework.data.repository.query.Param("id") Long id,
                         @org.springframework.data.repository.query.Param("worker") String worker,
                         @org.springframework.data.repository.query.Param("now") java.time.ZonedDateTime now,
                         @org.springframework.data.repository.query.Param("mayIngest") boolean mayIngest,
                         @org.springframework.data.repository.query.Param("preferPlayout") String preferPlayout,
                         @org.springframework.data.repository.query.Param("preferPlaylist") String preferPlaylist,
                         @org.springframework.data.repository.query.Param("preferOther") String preferOther,
                         @org.springframework.data.repository.query.Param("waitedBefore") java.time.ZonedDateTime waitedBefore,
                         @org.springframework.data.repository.query.Param("availableAfter") java.time.ZonedDateTime availableAfter,
                         @org.springframework.data.repository.query.Param("maxCpuPercent") double maxCpuPercent);

    default int claimHandoverFor(Long id, String worker, java.time.ZonedDateTime now, com.afklive.streamer.streaming.WorkerPools.Claim c) {
        return claimHandoverFor(id, worker, now, c.mayIngest(), c.preferPlayout(), c.preferPlaylist(), c.preferOther(),
                c.waitedBefore(), c.availableAfter(), c.maxCpuPercent());
    }

    /** FO-8: the owner keeps the run's placement current (live edits can change it); written only when it differs. */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @Query(value = "UPDATE stream_jobs SET placement = :placement WHERE id = :id AND worker_id = :worker AND is_live = true "
            + "AND placement IS DISTINCT FROM CAST(:placement AS VARCHAR)", nativeQuery = true)
    int savePlacement(@org.springframework.data.repository.query.Param("id") Long id,
                      @org.springframework.data.repository.query.Param("worker") String worker,
                      @org.springframework.data.repository.query.Param("placement") String placement);

    /** Who owns the job right now (cheap check while a handover completes). */
    @Query("SELECT j.workerId FROM StreamJob j WHERE j.id = :id")
    java.util.Optional<String> findWorkerIdById(@org.springframework.data.repository.query.Param("id") Long id);
}
