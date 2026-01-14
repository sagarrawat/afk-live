package com.afklive.streamer.model;

import lombok.Getter;

@Getter
public enum PlanType {
    FREE("Free", 1 * 1024 * 1024 * 1024L, 10, 1),
    ESSENTIALS("Essentials", 10 * 1024 * 1024 * 1024L, Integer.MAX_VALUE, 1),
    TEAM("Team", 100 * 1024 * 1024 * 1024L, Integer.MAX_VALUE, 3);

    private final String displayName;
    private final long maxStorageBytes;
    private final int maxScheduledPosts;
    private final int maxActiveStreams;

    PlanType(String displayName, long maxStorageBytes, int maxScheduledPosts, int maxActiveStreams) {
        this.displayName = displayName;
        this.maxStorageBytes = maxStorageBytes;
        this.maxScheduledPosts = maxScheduledPosts;
        this.maxActiveStreams = maxActiveStreams;
    }
}
