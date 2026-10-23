package com.afklive.streamer.repository;

import com.afklive.streamer.model.PushSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface PushSubscriptionRepository extends JpaRepository<PushSubscription, Long> {

    List<PushSubscription> findAllByUserId(Long userId);

    Optional<PushSubscription> findByEndpoint(String endpoint);

    long countByUserId(Long userId);

    @Modifying
    @Transactional
    int deleteByEndpointAndUserId(String endpoint, Long userId);
}
