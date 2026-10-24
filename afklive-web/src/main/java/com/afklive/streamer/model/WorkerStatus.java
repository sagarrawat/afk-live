package com.afklive.streamer.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;

/** AD-2 / RE-7: what a stream worker last reported about itself (written only by the worker). */
@Entity
@Table(name = "stream_workers")
@Data
@NoArgsConstructor
public class WorkerStatus {
    @Id
    @Column(name = "worker_id", length = 100)
    private String workerId;
    @Column(name = "started_at", nullable = false)
    private ZonedDateTime startedAt;
    @Column(name = "heartbeat_at", nullable = false)
    private ZonedDateTime heartbeatAt;
    private int streams;
    private int capacity;
    @Column(name = "cpu_percent")
    private Double cpuPercent;
    @Column(name = "memory_used_bytes")
    private Long memoryUsedBytes;
    @Column(name = "memory_total_bytes")
    private Long memoryTotalBytes;
    /** GL-7: CPUs the worker can use (null from workers that predate it). */
    private Integer cpus;
    /** OPS-3: set while this worker hands its streams to a new version (null: not draining). */
    @Column(name = "draining_since")
    private ZonedDateTime drainingSince;
}
