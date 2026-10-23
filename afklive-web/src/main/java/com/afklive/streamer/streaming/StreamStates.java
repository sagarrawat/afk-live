package com.afklive.streamer.streaming;

/** Values for a stream job's status (reported by its worker) and desired state (set by the web app). */
public final class StreamStates {
    public static final String STARTING = "STARTING";
    public static final String LIVE = "LIVE";
    public static final String RECONNECTING = "RECONNECTING";
    public static final String ENDED = "ENDED";
    public static final String FAILED = "FAILED";

    public static final String RUN = "RUN";
    public static final String STOP = "STOP";

    private StreamStates() {
    }
}
