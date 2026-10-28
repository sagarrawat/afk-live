package com.afklive.streamer.repository;

import com.afklive.streamer.model.OfferRedemption;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OfferRedemptionRepository extends JpaRepository<OfferRedemption, Long> {

    /**
     * Claims the account's one offer: 1 when this call created it, 0 when the account already had one (another payment
     * got there first). The primary key on user_id makes the race safe across instances.
     */
    @Modifying
    @Query(value = "INSERT INTO offer_redemptions (user_id, offer_id, plan_type, billing_cycle, percent_off, periods_total,"
            + " periods_used, first_order_id, created_at) VALUES (:userId, :offerId, :plan, :cycle, :percent, :total, 1, :orderId, now())"
            + " ON CONFLICT (user_id) DO NOTHING", nativeQuery = true)
    int claim(@Param("userId") Long userId, @Param("offerId") Long offerId, @Param("plan") String plan,
              @Param("cycle") String cycle, @Param("percent") int percent, @Param("total") int total,
              @Param("orderId") String orderId);

    /** One more discounted payment on the account's offer. */
    @Modifying
    @Query(value = "UPDATE offer_redemptions SET periods_used = periods_used + 1 WHERE user_id = :userId", nativeQuery = true)
    int use(@Param("userId") Long userId);

    long countByOfferId(Long offerId);
}
