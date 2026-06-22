package com.afklive.streamer.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;

@Entity
@Table(name = "stream_worker")
@Data
@NoArgsConstructor
public class StreamWorker {
    @Id
    @Column(name = "worker_id", length = 100)
    private String workerId;

    private String hostname;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WorkerType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WorkerStatus status = WorkerStatus.IDLE;

    @Column(name = "active_streams")
    private Integer activeStreams = 0;

    @Column(name = "max_streams", nullable = false)
    private Integer maxStreams;

    @Column(name = "last_heartbeat", nullable = false)
    private ZonedDateTime lastHeartbeat;

    @Column(name = "registered_at")
    private ZonedDateTime registeredAt = ZonedDateTime.now();

    @Column(columnDefinition = "jsonb")
    private String capabilities;

    public enum WorkerType {
        LIGHTWEIGHT,
        COMPUTE,
        OPTIMIZATION
    }

    public enum WorkerStatus {
        IDLE,
        BUSY,
        DRAINING,
        OFFLINE
    }
}
