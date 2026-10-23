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
    @org.springframework.data.jpa.repository.Query("UPDATE StreamJob j SET j.isLive = false, j.endTime = :endTime, j.endReason = :reason WHERE j.id = :id AND j.isLive = true")
    int markEnded(@org.springframework.data.repository.query.Param("id") Long id,
                  @org.springframework.data.repository.query.Param("endTime") java.time.ZonedDateTime endTime,
                  @org.springframework.data.repository.query.Param("reason") String reason);

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
}
