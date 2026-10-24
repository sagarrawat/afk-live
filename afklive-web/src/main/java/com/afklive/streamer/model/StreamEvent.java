package com.afklive.streamer.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;

/**
 * RE-9: one line on a run's timeline, written by the worker as it happens: a destination reached its
 * platform, is reconnecting (and why), isn't reaching it, or is back. The same reconnect reason again
 * bumps {@link #repeats} and {@link #lastAt} instead of adding a row, so a reconnect loop is one line.
 */
@Entity
@Table(name = "stream_events")
@Data
@NoArgsConstructor
public class StreamEvent {

    /** What happened. */
    public static final class Kinds {
        /** The platform started receiving the stream (the first time in this run). */
        public static final String REACHED = "REACHED";
        /** The connection dropped; we reconnect on our own. */
        public static final String RECONNECTING = "RECONNECTING";
        /** Two bad windows in a row: the platform isn't receiving (we keep trying). */
        public static final String NOT_REACHING = "NOT_REACHING";
        /** Receiving again after a reconnect or after not reaching. */
        public static final String RECOVERED = "RECOVERED";

        private Kinds() {
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_id", nullable = false)
    private Long jobId;

    @Column(nullable = false)
    private ZonedDateTime at;

    @Column(name = "last_at", nullable = false)
    private ZonedDateTime lastAt;

    @Column(nullable = false, length = 30)
    private String kind;

    /** The destination's position in the run. */
    private Integer position;

    /** The destination's name as the creator saved it. */
    private String name;

    /** In plain words, ready to show ("Reconnecting to YouTube: YouTube refused the stream key. ..."). */
    @Column(columnDefinition = "TEXT")
    private String message;

    @Column(nullable = false)
    private int repeats = 1;

    public StreamEvent(Long jobId, ZonedDateTime at, String kind, Integer position, String name, String message) {
        this.jobId = jobId;
        this.at = at;
        this.lastAt = at;
        this.kind = kind;
        this.position = position;
        this.name = name;
        this.message = message;
    }
}
