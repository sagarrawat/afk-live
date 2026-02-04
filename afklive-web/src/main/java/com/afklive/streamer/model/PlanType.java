package com.afklive.streamer.model;

public enum PlanType {
    FREE("Pay As You Go", 1 * 1024 * 1024 * 1024L, 10, 1, 1, 720),
    ESSENTIALS("Essentials", 10 * 1024 * 1024 * 1024L, Integer.MAX_VALUE, 1, 3, 1080),
    TEAM("Team", 100 * 1024 * 1024 * 1024L, Integer.MAX_VALUE, 3, 100, 1440);

    private final String displayName;
    private final long maxStorageBytes;
    private final int maxScheduledPosts;
    private final int maxActiveStreams;
    private final int maxChannels;
    private final int maxResolution;

    PlanType(String displayName, long maxStorageBytes, int maxScheduledPosts, int maxActiveStreams, int maxChannels, int maxResolution) {
        this.displayName = displayName;
        this.maxStorageBytes = maxStorageBytes;
        this.maxScheduledPosts = maxScheduledPosts;
        this.maxActiveStreams = maxActiveStreams;
        this.maxChannels = maxChannels;
        this.maxResolution = maxResolution;
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
}
