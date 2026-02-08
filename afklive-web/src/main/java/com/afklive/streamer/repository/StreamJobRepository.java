package com.afklive.streamer.repository;

import com.afklive.streamer.model.StreamJob;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StreamJobRepository extends JpaRepository<StreamJob, Long> {
    // Find the active job for a specific user
    Optional<StreamJob> findByUserEmailAndIsLiveTrue(String email);
    List<StreamJob> findAllByUserEmailAndIsLiveTrue(String email);
    long countByUserEmailAndIsLiveTrue(String email);

    // Admin: Find ALL live jobs
    List<StreamJob> findAllByIsLiveTrue();
    Page<StreamJob> findAllByIsLiveTrue(Pageable pageable);
    long countByIsLiveTrue();

    // Auto-Reply
    List<StreamJob> findByIsLiveTrueAndAutoReplyEnabledTrue();

    // History
    Page<StreamJob> findByUserEmailOrderByStartTimeDesc(String email, Pageable pageable);
}
