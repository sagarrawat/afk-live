package com.afklive.streamer.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * BI-9: a launch offer. A real percentage off the real list price, for people who have never paid for a plan, on
 * the first {@link #monthlyPeriods} monthly payments (or once on the first year when {@link #includeYearly}),
 * between a real start and end date. Automatic offers apply by themselves and are shown on the pricing page; an
 * offer with only a code applies when the code is entered. Created, edited and ended by an admin; once anyone has paid
 * with it, its discount, plans and months are fixed ({@code OfferService#edit}).
 */
@Entity
@Table(name = "launch_offers")
@Data
@NoArgsConstructor
public class LaunchOffer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 80)
    private String name;

    /** Upper case; null for an automatic offer without a code. */
    @Column(length = 32, unique = true)
    private String code;

    @Column(nullable = false)
    private boolean automatic;

    @Column(name = "percent_off", nullable = false)
    private int percentOff;

    @Column(name = "monthly_periods", nullable = false)
    private int monthlyPeriods;

    @Column(name = "include_yearly", nullable = false)
    private boolean includeYearly;

    /** Comma-separated plan ids, e.g. "ESSENTIALS,PRO,AGENCY". */
    @Column(nullable = false, length = 100)
    private String plans;

    @Column(name = "starts_at", nullable = false)
    private ZonedDateTime startsAt;

    @Column(name = "ends_at", nullable = false)
    private ZonedDateTime endsAt;

    /** Set when an admin ends the offer early. */
    @Column(name = "ended_at")
    private ZonedDateTime endedAt;

    @Column(name = "created_by")
    private String createdBy;
    @Column(name = "created_reason", columnDefinition = "TEXT")
    private String createdReason;
    @Column(name = "ended_by")
    private String endedBy;
    @Column(name = "ended_reason", columnDefinition = "TEXT")
    private String endedReason;

    @Column(name = "created_at", nullable = false)
    private ZonedDateTime createdAt = ZonedDateTime.now();

    public Set<PlanType> planSet() {
        return Arrays.stream(plans.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .map(s -> PlanType.valueOf(s.toUpperCase(Locale.ROOT))).collect(Collectors.toSet());
    }

    /** When it stops being offered: its end date, or earlier if an admin ended it. */
    public ZonedDateTime closesAt() {
        return endedAt != null && endedAt.isBefore(endsAt) ? endedAt : endsAt;
    }

    public boolean isActive(ZonedDateTime now) {
        return !now.isBefore(startsAt) && now.isBefore(closesAt());
    }

    public boolean covers(PlanType plan, Subscription.Cycle cycle) {
        return planSet().contains(plan) && (cycle == Subscription.Cycle.MONTHLY || includeYearly);
    }

    /** How many payments get the discount: the monthly count, or the first year once. */
    public int periodsFor(Subscription.Cycle cycle) {
        return cycle == Subscription.Cycle.YEARLY ? 1 : monthlyPeriods;
    }
}
