package com.afklive.streamer.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;

@Entity
@Table(name = "stream_job_queue", indexes = {
    @Index(name = "idx_stream_job_queue_fetch", columnList = "worker_type, status, priority DESC, created_at ASC")
})
@Data
@NoArgsConstructor
public class StreamJobQueue {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(optional = false)
    @JoinColumn(name = "stream_job_id", nullable = false, unique = true)
    private StreamJob streamJob;

    @Column(name = "stream_spec", columnDefinition = "jsonb", nullable = false)
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    private String streamSpec;

    private Integer priority = 0;

    @Column(name = "worker_type", nullable = false)
    private String workerType = "ffmpeg-node";

    @Enumerated(EnumType.STRING)
    private QueueStatus status = QueueStatus.PENDING;

    @Column(name = "created_at")
    private ZonedDateTime createdAt = ZonedDateTime.now();

    @Column(name = "acquired_at")
    private ZonedDateTime acquiredAt;

    @Column(name = "acquired_by")
    private String acquiredBy;

    public enum QueueStatus {
        PENDING,
        ACQUIRED,
        COMPLETED,
        FAILED
    }
}
