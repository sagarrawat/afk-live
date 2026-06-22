package com.afklive.streamer.repository;

import com.afklive.streamer.model.StreamJob;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;

public interface StreamJobRepository extends JpaRepository<StreamJob, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT j FROM StreamJob j WHERE j.id = :id")
    Optional<StreamJob> findByIdForUpdate(Long id);

    // Find the active job for a specific user
    @Query("SELECT j FROM StreamJob j JOIN FETCH j.user WHERE j.user.email = :email AND j.status = 'LIVE'")
    Optional<StreamJob> findByUserEmailAndStatusLive(String email);

    // Find jobs by user and a specific status (used to fix the findAll() table-scan bug)
    @Query("SELECT j FROM StreamJob j JOIN FETCH j.user WHERE j.user.email = :email AND j.status = :status")
    List<StreamJob> findAllByUserEmailAndStatus(String email, StreamJob.StreamStatus status);

    @Query("SELECT j FROM StreamJob j JOIN FETCH j.user WHERE j.user.email = :email AND j.status = 'LIVE'")
    List<StreamJob> findAllByUserEmailAndStatusLive(String email);

    @Query("SELECT count(j) FROM StreamJob j WHERE j.user.email = :email AND j.status = 'LIVE'")
    long countByUserEmailAndStatusLive(String email);

    // Admin: Find ALL live jobs
    @Query("SELECT j FROM StreamJob j JOIN FETCH j.user WHERE j.status = 'LIVE'")
    List<StreamJob> findAllByStatusLive();

    @Query(value = "SELECT j FROM StreamJob j JOIN FETCH j.user WHERE j.status = 'LIVE'",
           countQuery = "SELECT count(j) FROM StreamJob j WHERE j.status = 'LIVE'")
    Page<StreamJob> findAllByStatusLive(Pageable pageable);

    @Query("SELECT count(j) FROM StreamJob j WHERE j.status = 'LIVE'")
    long countByStatusLive();

    // Auto-Reply
    @Query("SELECT j FROM StreamJob j JOIN FETCH j.user WHERE j.status = 'LIVE' AND j.autoReplyEnabled = true")
    List<StreamJob> findByStatusLiveAndAutoReplyEnabledTrue();

    // History
    Page<StreamJob> findByUserEmailOrderByStartTimeDesc(String email, Pageable pageable);

    @Query("SELECT j FROM StreamJob j JOIN FETCH j.user WHERE j.status IN ('COMPLETED', 'FAILED', 'STOPPED') AND j.ledgerRecorded = false AND j.accumulatedCost > 0")
    List<StreamJob> findUnrecordedFinishedJobs();
}
