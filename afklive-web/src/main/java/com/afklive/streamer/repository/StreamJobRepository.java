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
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true)
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
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query("UPDATE StreamJob j SET j.accumulatedCost = j.accumulatedCost + :increment, j.lastBillingTime = :now WHERE j.id = :id AND j.isLive = true AND j.lastBillingTime = :previous")
    int addBilling(@org.springframework.data.repository.query.Param("id") Long id,
                   @org.springframework.data.repository.query.Param("increment") double increment,
                   @org.springframework.data.repository.query.Param("now") java.time.ZonedDateTime now,
                   @org.springframework.data.repository.query.Param("previous") java.time.ZonedDateTime previous);

    /** The last charge when a job ends (it is no longer live); also records the total as the job's cost. */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query("UPDATE StreamJob j SET j.accumulatedCost = j.accumulatedCost + :increment, j.cost = j.accumulatedCost + :increment, j.lastBillingTime = :now WHERE j.id = :id AND j.lastBillingTime = :previous")
    int addFinalBilling(@org.springframework.data.repository.query.Param("id") Long id,
                        @org.springframework.data.repository.query.Param("increment") double increment,
                        @org.springframework.data.repository.query.Param("now") java.time.ZonedDateTime now,
                        @org.springframework.data.repository.query.Param("previous") java.time.ZonedDateTime previous);

    // ---- worker coordination (all conditional, so concurrent workers never both own a job) ----

    /** Web asks for a stop; the reason is kept so the worker records it. */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query("UPDATE StreamJob j SET j.desiredState = 'STOP', j.endReason = :reason WHERE j.id = :id AND j.isLive = true")
    int requestStop(@org.springframework.data.repository.query.Param("id") Long id,
                    @org.springframework.data.repository.query.Param("reason") String reason);

    @org.springframework.data.jpa.repository.Query("SELECT j.id FROM StreamJob j WHERE j.isLive = true AND j.desiredState = 'RUN' AND j.workerId IS NULL ORDER BY j.startTime")
    List<Long> findUnclaimedIds();

    /** Other workers' jobs that stopped reporting: candidates for takeover. */
    @org.springframework.data.jpa.repository.Query("SELECT j.id FROM StreamJob j WHERE j.isLive = true AND j.desiredState = 'RUN' AND j.workerId IS NOT NULL AND j.workerId <> :worker AND (j.heartbeatAt IS NULL OR j.heartbeatAt < :cutoff)")
    List<Long> findOrphanIds(@org.springframework.data.repository.query.Param("cutoff") java.time.ZonedDateTime cutoff,
                             @org.springframework.data.repository.query.Param("worker") String worker);

    /** Stop requested but no worker is alive to carry it out: the web side ends these. */
    @org.springframework.data.jpa.repository.Query("SELECT j.id FROM StreamJob j WHERE j.isLive = true AND j.desiredState = 'STOP' AND (j.workerId IS NULL OR j.heartbeatAt IS NULL OR j.heartbeatAt < :cutoff)")
    List<Long> findAbandonedStopIds(@org.springframework.data.repository.query.Param("cutoff") java.time.ZonedDateTime cutoff);

    /** A restarted worker resumes its own jobs (same worker id). */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query("UPDATE StreamJob j SET j.heartbeatAt = :now WHERE j.id = :id AND j.workerId = :worker AND j.isLive = true AND j.desiredState = 'RUN'")
    int resumeOwn(@org.springframework.data.repository.query.Param("id") Long id,
                  @org.springframework.data.repository.query.Param("worker") String worker,
                  @org.springframework.data.repository.query.Param("now") java.time.ZonedDateTime now);

    /** First time the stream is actually on air: billing starts now (not while files were downloading). */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query("UPDATE StreamJob j SET j.status = 'LIVE', j.lastBillingTime = :now WHERE j.id = :id AND j.isLive = true AND j.status = 'STARTING'")
    int markFirstLive(@org.springframework.data.repository.query.Param("id") Long id,
                      @org.springframework.data.repository.query.Param("now") java.time.ZonedDateTime now);

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query("UPDATE StreamJob j SET j.stopAt = :stopAt WHERE j.id = :id AND j.isLive = true")
    int updateStopAt(@org.springframework.data.repository.query.Param("id") Long id,
                     @org.springframework.data.repository.query.Param("stopAt") java.time.ZonedDateTime stopAt);

    /** Jobs that are on air (billable). */
    @org.springframework.data.jpa.repository.Query("SELECT j FROM StreamJob j WHERE j.isLive = true AND j.status IN ('LIVE', 'RECONNECTING')")
    List<StreamJob> findAllOnAir();

    List<StreamJob> findAllByWorkerIdAndIsLiveTrue(String workerId);

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query("UPDATE StreamJob j SET j.workerId = :worker, j.heartbeatAt = :now WHERE j.id = :id AND j.isLive = true AND j.desiredState = 'RUN' AND j.workerId IS NULL")
    int claim(@org.springframework.data.repository.query.Param("id") Long id,
              @org.springframework.data.repository.query.Param("worker") String worker,
              @org.springframework.data.repository.query.Param("now") java.time.ZonedDateTime now);

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true)
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
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true)
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
}
