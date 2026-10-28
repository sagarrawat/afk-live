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

    /** BI-10: subscriptions that aren't canceled, on a plan. */
    @Query("SELECT COUNT(s) FROM Subscription s WHERE s.status <> 'CANCELED' AND s.planType = :plan")
    long countLiveOnPlan(@Param("plan") com.afklive.streamer.model.PlanType plan);

    /** BI-10: subscriptions that aren't canceled and have no recorded terms yet. */
    @Query("SELECT s FROM Subscription s WHERE s.status <> 'CANCELED' AND s.terms.plan IS NULL")
    List<Subscription> findLiveWithoutTerms();

    /** BI-10: accounts on a plan without a live subscription (Free accounts, or a plan set before billing existed). */
    @Query("SELECT COUNT(u) FROM User u WHERE u.planType = :plan AND NOT EXISTS"
            + " (SELECT s.id FROM Subscription s WHERE s.userId = u.id AND s.status <> 'CANCELED')")
    long countUsersOnPlanWithoutSubscription(@Param("plan") com.afklive.streamer.model.PlanType plan);
}
