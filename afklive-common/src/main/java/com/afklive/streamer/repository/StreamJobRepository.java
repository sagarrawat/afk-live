package com.afklive.streamer.repository;

import com.afklive.streamer.model.StreamJob;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StreamJobRepository extends JpaRepository<StreamJob, Long> {
    // Find the active job for a specific user
    Optional<StreamJob> findByUsernameAndIsLiveTrue(String username);
    List<StreamJob> findAllByUsernameAndIsLiveTrue(String username);
    long countByUsernameAndIsLiveTrue(String username);

    // Admin: Find ALL live jobs
    List<StreamJob> findAllByIsLiveTrue();
    Page<StreamJob> findAllByIsLiveTrue(Pageable pageable);
    long countByIsLiveTrue();

    // Auto-Reply
    List<StreamJob> findByIsLiveTrueAndAutoReplyEnabledTrue();

    // History
    Page<StreamJob> findByUsernameOrderByStartTimeDesc(String username, Pageable pageable);
}