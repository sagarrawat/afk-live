package com.afklive.streamer.repository;

import com.afklive.streamer.model.Subscription;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    Optional<Subscription> findByUserId(Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Subscription s WHERE s.userId = :userId")
    Optional<Subscription> lockByUserId(@Param("userId") Long userId);

    @Query("SELECT s FROM Subscription s WHERE s.status <> 'CANCELED' AND s.currentPeriodEnd < :before")
    List<Subscription> findEndingBefore(@Param("before") ZonedDateTime before);
}
