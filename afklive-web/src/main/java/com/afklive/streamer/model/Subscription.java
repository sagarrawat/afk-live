package com.afklive.streamer.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;

/** A user's paid plan: what they pay for, until when, and what changes at the end of the period. */
@Entity
@Table(name = "subscriptions")
@Data
@NoArgsConstructor
public class Subscription {

    public enum Cycle { MONTHLY, YEARLY }

    /** ACTIVE: paid through the period end. PAST_DUE: period ended, a few days' grace to pay. CANCELED: back on Free. */
    public enum Status { ACTIVE, PAST_DUE, CANCELED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "plan_type", nullable = false)
    private PlanType planType;

    @Enumerated(EnumType.STRING)
    @Column(name = "billing_cycle", nullable = false)
    private Cycle cycle;

    @Column(nullable = false, length = 3)
    private String currency = "INR";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "current_period_start", nullable = false)
    private ZonedDateTime currentPeriodStart;

    @Column(name = "current_period_end", nullable = false)
    private ZonedDateTime currentPeriodEnd;

    /** BI-5: cancel at the end of the period (access kept until then). */
    @Column(name = "cancel_at_period_end", nullable = false)
    private boolean cancelAtPeriodEnd;

    /** BI-5: a downgrade, applied at the end of the period. */
    @Enumerated(EnumType.STRING)
    @Column(name = "pending_plan")
    private PlanType pendingPlan;

    @Enumerated(EnumType.STRING)
    @Column(name = "pending_cycle")
    private Cycle pendingCycle;

    @Column(name = "renewal_reminded_at")
    private ZonedDateTime renewalRemindedAt;

    /**
     * BI-9: what the current period cost before any upgrade credit (the list price, or less with a launch offer);
     * null for periods paid before this was recorded (then the list price is assumed).
     */
    @Column(name = "period_price")
    private Long periodPrice;

    /** BI-10: the limits bought with this plan (null on rows from before grandfathering, or on a plan an admin set). */
    @Embedded
    private PlanTerms terms;

    @Column(name = "created_at", nullable = false)
    private ZonedDateTime createdAt = ZonedDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private ZonedDateTime updatedAt = ZonedDateTime.now();

    @PreUpdate
    void touch() {
        updatedAt = ZonedDateTime.now();
    }
}
