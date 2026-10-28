package com.afklive.streamer.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;

/**
 * BI-10 grandfathering: the plan limits a subscriber bought, recorded on their subscription when they pay for a plan.
 * Limit checks use the more generous of these and the plan's current {@code plan_configs} row ({@link #merge}), so an
 * admin changing a plan's limits later never takes anything away from someone already paying for it.
 */
@Embeddable
@Data
@NoArgsConstructor
public class PlanTerms {

    @Enumerated(EnumType.STRING)
    @Column(name = "terms_plan")
    private PlanType plan;
    @Column(name = "terms_streams")
    private Integer streams;
    @Column(name = "terms_destinations")
    private Integer destinations;
    @Column(name = "terms_max_height")
    private Integer maxHeight;
    @Column(name = "terms_max_fps")
    private Integer maxFps;
    @Column(name = "terms_storage_bytes")
    private Long storageBytes;
    /** 0 = unlimited, as in plan_configs. */
    @Column(name = "terms_monthly_hours")
    private Integer monthlyHours;
    /** Null = no monthly streaming data allowance, as in plan_configs. */
    @Column(name = "terms_traffic_gb")
    private Integer trafficGb;
    @Column(name = "terms_watermark")
    private Boolean watermark;
    @Column(name = "terms_captured_at")
    private ZonedDateTime capturedAt;

    /** The limits of {@code c} as they are now. */
    public static PlanTerms of(PlanConfig c, ZonedDateTime at) {
        PlanTerms t = new PlanTerms();
        t.plan = c.getPlanType();
        t.streams = c.getMaxActiveStreams();
        t.destinations = c.getMaxDestinations();
        t.maxHeight = c.getMaxResolution();
        t.maxFps = c.getMaxFps();
        t.storageBytes = c.getMaxStorageBytes();
        t.monthlyHours = c.getMonthlyHours();
        t.trafficGb = c.getMonthlyTrafficGb();
        t.watermark = c.isWatermark();
        t.capturedAt = at;
        return t;
    }

    /**
     * Field by field the more generous of {@code current} and these terms, as a new object ({@code current} may be a
     * cached row: it is never changed). Prices and names come from {@code current}.
     */
    public PlanConfig merge(PlanConfig current) {
        PlanConfig out = copy(current);
        out.setMaxActiveStreams(Math.max(current.getMaxActiveStreams(), nz(streams)));
        out.setMaxDestinations(Math.max(current.getMaxDestinations(), nz(destinations)));
        out.setMaxResolution(Math.max(current.getMaxResolution(), nz(maxHeight)));
        out.setMaxFps(Math.max(current.getMaxFps(), nz(maxFps)));
        out.setMaxStorageBytes(Math.max(current.getMaxStorageBytes(), storageBytes == null ? 0 : storageBytes));
        int hours = monthlyHours == null ? current.getMonthlyHours() : monthlyHours;
        out.setMonthlyHours(current.getMonthlyHours() == 0 || hours == 0 ? 0 : Math.max(current.getMonthlyHours(), hours));
        // Traffic: null on either side means no allowance, which is the most generous.
        out.setMonthlyTrafficGb(current.getMonthlyTrafficGb() == null || trafficGb == null ? null
                : Math.max(current.getMonthlyTrafficGb(), trafficGb));
        out.setWatermark(current.isWatermark() && (watermark == null || watermark));
        return out;
    }

    /**
     * Upgrading keeps anything the old terms gave that is more than the new plan's (a grandfathered Pro moving to
     * Agency doesn't lose destinations); the result is recorded as the new plan's terms.
     */
    public static PlanTerms carryOver(PlanTerms previous, PlanConfig next, ZonedDateTime at) {
        if (previous == null || previous.plan == null) {
            return of(next, at);
        }
        PlanTerms t = of(previous.merge(next), at);
        t.plan = next.getPlanType();
        return t;
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    static PlanConfig copy(PlanConfig c) {
        return new PlanConfig(c.getPlanType(), c.getDisplayName(), c.getPrice(), c.getBillingCycle(), c.getMaxStorageBytes(),
                c.getMaxScheduledPosts(), c.getMaxActiveStreams(), c.getMaxChannels(), c.getMaxResolution(), c.getMaxFps(),
                c.getMonthlyHours(), c.isWatermark(), c.getMaxDestinations(), c.getPriceInrMonth(), c.getPriceInrYear(),
                c.getPriceUsdMonth(), c.getPriceUsdYear(), c.getDefaultsVersion(), c.getMonthlyTrafficGb());
    }
}
