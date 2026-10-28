package com.afklive.streamer.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;

/**
 * BI-9: an account's one launch offer. Created by the first discounted payment; each discounted payment (the first
 * one and discounted renewals) adds one to {@link #periodsUsed}. When it reaches {@link #periodsTotal} renewals are
 * at the list price. Kept forever: it is also what makes an account "not new" for the next offer.
 */
@Entity
@Table(name = "offer_redemptions")
@Data
@NoArgsConstructor
public class OfferRedemption {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "offer_id", nullable = false)
    private Long offerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "plan_type", nullable = false)
    private PlanType planType;

    @Enumerated(EnumType.STRING)
    @Column(name = "billing_cycle", nullable = false)
    private Subscription.Cycle cycle;

    @Column(name = "percent_off", nullable = false)
    private int percentOff;

    @Column(name = "periods_total", nullable = false)
    private int periodsTotal;

    @Column(name = "periods_used", nullable = false)
    private int periodsUsed;

    @Column(name = "first_order_id")
    private String firstOrderId;

    @Column(name = "created_at", nullable = false)
    private ZonedDateTime createdAt = ZonedDateTime.now();

    public int periodsLeft() {
        return Math.max(0, periodsTotal - periodsUsed);
    }
}
