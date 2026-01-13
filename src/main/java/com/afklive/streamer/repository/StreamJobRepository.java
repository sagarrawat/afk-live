package com.afklive.streamer.repository;

import com.afklive.streamer.model.StreamJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface StreamJobRepository extends JpaRepository<StreamJob, Long> {
    // Find the active job for a specific user
    Optional<StreamJob> findByUsernameAndIsLiveTrue(String username);
    long countByUsernameAndIsLiveTrue(String username);
}