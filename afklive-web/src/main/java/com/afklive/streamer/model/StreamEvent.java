package com.afklive.streamer.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;

/**
 * One line on a run's timeline (RE-1, RE-3, RE-8): a destination reached its platform, lost it (and
 * why), came back; the encoder restarted; the run gave up. Written by the worker as it happens. A
 * failure that repeats with the same reason bumps {@code repeats} and {@code lastAt} instead of adding a row.
 */
@Entity
@Table(name = "stream_events")
@Data
@NoArgsConstructor
public class StreamEvent {

    /** What happened. */
    public static final class Kinds {
        /** A destination is receiving the stream (first time, or {@link #DEST_RECOVERED} after a problem). */
        public static final String DEST_ON = "DEST_ON";
        public static final String DEST_RECOVERED = "DEST_RECOVERED";
        /** A destination that was receiving lost the connection; we reconnect. */
        public static final String DEST_LOST = "DEST_LOST";
        /** A destination couldn't connect (or keeps failing); we keep retrying. */
        public static final String DEST_FAILING = "DEST_FAILING";
        /** We dropped a connection on purpose because the platform stopped taking the stream or couldn't keep up. */
        public static final String DEST_GAVE_UP = "DEST_GAVE_UP";
        public static final String ENCODER_RESTART = "ENCODER_RESTART";
        /** The run ended because it kept failing. */
        public static final String GAVE_UP = "GAVE_UP";

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

    @Column(nullable = false, length = 30)
    private String kind;

    /** The destination's position in the run, or null for the whole stream. */
    private Integer position;

    @Column(length = 200)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String message;

    @Column(nullable = false)
    private int repeats = 1;

    @Column(name = "last_at", nullable = false)
    private ZonedDateTime lastAt;

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
