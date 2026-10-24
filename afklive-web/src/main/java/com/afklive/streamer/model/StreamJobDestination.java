package com.afklive.streamer.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.ZonedDateTime;

/** One platform a stream is sent to, with its own state (each reconnects independently). */
@Entity
@org.hibernate.annotations.DynamicUpdate
@Table(name = "stream_job_destinations")
@Data
@NoArgsConstructor
public class StreamJobDestination {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "job_id", nullable = false)
    @JsonIgnore
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private StreamJob job;

    /** Index into the job spec's destination list. */
    private int position;
    private String name;
    /** CONNECTING, LIVE, RECONNECTING, STOPPED */
    private String status;
    private int restarts;
    @Column(columnDefinition = "TEXT")
    private String lastError;
    private ZonedDateTime updatedAt;
    /**
     * RE-8: ON (the platform has been receiving steadily), CONNECTING or NOT_REACHING; null from a worker of
     * the previous release or when turned off. {@link #reachReason}: the relay's last error, keys redacted.
     */
    @Column(length = 20)
    private String reach;
    @Column(name = "reach_reason", columnDefinition = "TEXT")
    private String reachReason;
    /** FO-3: bytes this destination actually sent during the run (every reconnect), reported with the heartbeat. */
    @Column(name = "bytes_sent", nullable = false)
    private long bytesSent;

    public StreamJobDestination(StreamJob job, int position, String name) {
        this.job = job;
        this.position = position;
        this.name = name;
        this.status = "CONNECTING";
    }
}
