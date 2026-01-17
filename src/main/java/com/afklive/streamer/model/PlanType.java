package com.afklive.streamer.model;

public enum PlanType {
    FREE("Free", 1 * 1024 * 1024 * 1024L, 10, 1, 1),
    ESSENTIALS("Essentials", 10 * 1024 * 1024 * 1024L, Integer.MAX_VALUE, 1, 3),
    TEAM("Team", 100 * 1024 * 1024 * 1024L, Integer.MAX_VALUE, 3, 100);

    private final String displayName;
    private final long maxStorageBytes;
    private final int maxScheduledPosts;
    private final int maxActiveStreams;
    private final int maxChannels;

    PlanType(String displayName, long maxStorageBytes, int maxScheduledPosts, int maxActiveStreams, int maxChannels) {
        this.displayName = displayName;
        this.maxStorageBytes = maxStorageBytes;
        this.maxScheduledPosts = maxScheduledPosts;
        this.maxActiveStreams = maxActiveStreams;
        this.maxChannels = maxChannels;
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
}
