package com.afklive.streamer.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "plan_configs")
public class PlanConfig {
    @Id
    @Enumerated(EnumType.STRING)
    private PlanType planType;

    private String displayName;
    private String price;
    private String billingCycle;
    private long maxStorageBytes;
    private int maxScheduledPosts;
    private int maxActiveStreams;
    private int maxChannels;
    private int maxResolution;

    // BI-1/BI-2 (009-billing)
    @Column(name = "max_fps", nullable = false)
    private int maxFps = 30;
    /** Free plan: streaming hours included per month (0 = unlimited). */
    @Column(name = "monthly_hours", nullable = false)
    private int monthlyHours;
    /** Free plan: a small "AFK Live" mark on the stream. */
    @Column(nullable = false)
    private boolean watermark;
    @Column(name = "max_destinations", nullable = false)
    private int maxDestinations = 1;
    /** GST-inclusive prices in paise (INR) and cents (USD). */
    @Column(name = "price_inr_month", nullable = false)
    private long priceInrMonth;
    @Column(name = "price_inr_year", nullable = false)
    private long priceInrYear;
    @Column(name = "price_usd_month", nullable = false)
    private long priceUsdMonth;
    @Column(name = "price_usd_year", nullable = false)
    private long priceUsdYear;
    @Column(name = "defaults_version", nullable = false)
    private int defaultsVersion;

    /** Price for one billing period, in the currency's minor unit. */
    public long price(String currency, boolean yearly) {
        boolean usd = "USD".equalsIgnoreCase(currency);
        return usd ? (yearly ? priceUsdYear : priceUsdMonth) : (yearly ? priceInrYear : priceInrMonth);
    }

    /** Applies the plan's built-in defaults (first creation, or after a schema change). */
    public void applyDefaults(PlanType t) {
        planType = t;
        displayName = t.getDisplayName();
        maxStorageBytes = t.getMaxStorageBytes();
        maxScheduledPosts = t.getMaxScheduledPosts();
        maxActiveStreams = t.getMaxActiveStreams();
        maxChannels = t.getMaxChannels();
        maxResolution = t.getMaxResolution();
        maxFps = t.getMaxFps();
        monthlyHours = t.getMonthlyHours();
        watermark = t.isWatermark();
        maxDestinations = t.getMaxDestinations();
        priceInrMonth = t.getPriceInrMonth();
        priceInrYear = t.getPriceInrYear();
        priceUsdMonth = t.getPriceUsdMonth();
        priceUsdYear = t.getPriceUsdYear();
        price = t.getPriceInrMonth() == 0 ? "Free" : "₹" + (t.getPriceInrMonth() / 100);
        billingCycle = t.getPriceInrMonth() == 0 ? "Free" : "Monthly";
        defaultsVersion = CURRENT_DEFAULTS;
    }

    /** Bump when the built-in defaults change in a way existing rows should pick up. */
    public static final int CURRENT_DEFAULTS = 1;
}
