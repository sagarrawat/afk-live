package com.afklive.streamer.model;

/**
 * The plans (BI-1, BI-2): priced by how many streams run at once, with quality, storage and
 * destinations by tier. These are the defaults used to create each plan's row the first time; the
 * live values (including prices) are in {@code plan_configs} and can be changed by an admin without
 * a deploy. Prices are GST-inclusive, in paise / US cents; yearly is ten months' price.
 * <p>
 * Default prices are proposals sized from the spec's cost model (about 1 vCPU per re-encoded 1080p
 * stream); confirm them against measured cost per stream-hour before launch.
 */
public enum PlanType {
    //            name        storage           posts  streams channels height fps  hours/mo watermark dests  INR/mo  INR/yr   USD/mo USD/yr
    FREE("Free", gb(2), 10, 1, 1, 720, 30, 10, true, 1, 0, 0, 0, 0),
    ESSENTIALS("Creator", gb(25), Integer.MAX_VALUE, 1, 3, 1080, 30, 0, false, 2, 49_900, 499_000, 900, 9_000),
    PRO("Pro", gb(100), Integer.MAX_VALUE, 3, 10, 1080, 60, 0, false, 5, 149_900, 1_499_000, 2_400, 24_000),
    AGENCY("Agency", gb(500), Integer.MAX_VALUE, 10, 50, 2160, 60, 0, false, 5, 399_900, 3_999_000, 5_900, 59_000);

    private final String displayName;
    private final long maxStorageBytes;
    private final int maxScheduledPosts;
    private final int maxActiveStreams;
    private final int maxChannels;
    private final int maxResolution;
    private final int maxFps;
    private final int monthlyHours;
    private final boolean watermark;
    private final int maxDestinations;
    private final long priceInrMonth;
    private final long priceInrYear;
    private final long priceUsdMonth;
    private final long priceUsdYear;

    PlanType(String displayName, long maxStorageBytes, int maxScheduledPosts, int maxActiveStreams, int maxChannels,
             int maxResolution, int maxFps, int monthlyHours, boolean watermark, int maxDestinations,
             long priceInrMonth, long priceInrYear, long priceUsdMonth, long priceUsdYear) {
        this.displayName = displayName;
        this.maxStorageBytes = maxStorageBytes;
        this.maxScheduledPosts = maxScheduledPosts;
        this.maxActiveStreams = maxActiveStreams;
        this.maxChannels = maxChannels;
        this.maxResolution = maxResolution;
        this.maxFps = maxFps;
        this.monthlyHours = monthlyHours;
        this.watermark = watermark;
        this.maxDestinations = maxDestinations;
        this.priceInrMonth = priceInrMonth;
        this.priceInrYear = priceInrYear;
        this.priceUsdMonth = priceUsdMonth;
        this.priceUsdYear = priceUsdYear;
    }

    private static long gb(long n) {
        return n * 1024 * 1024 * 1024;
    }

    public String getDisplayName() {
        return displayName;
    }

    public long getMaxStorageBytes() {
        return maxStorageBytes;
    }

    public int getMaxScheduledPosts() {
        return maxScheduledPosts;
    }

    public int getMaxActiveStreams() {
        return maxActiveStreams;
    }

    public int getMaxChannels() {
        return maxChannels;
    }

    public int getMaxResolution() {
        return maxResolution;
    }

    public int getMaxFps() {
        return maxFps;
    }

    public int getMonthlyHours() {
        return monthlyHours;
    }

    public boolean isWatermark() {
        return watermark;
    }

    public int getMaxDestinations() {
        return maxDestinations;
    }

    public long getPriceInrMonth() {
        return priceInrMonth;
    }

    public long getPriceInrYear() {
        return priceInrYear;
    }

    public long getPriceUsdMonth() {
        return priceUsdMonth;
    }

    public long getPriceUsdYear() {
        return priceUsdYear;
    }

    /** Rank for upgrade/downgrade decisions. */
    public int rank() {
        return ordinal();
    }
}
