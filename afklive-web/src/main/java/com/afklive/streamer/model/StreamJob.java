package com.afklive.streamer.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "stream_jobs", indexes = {
    @Index(name = "idx_streamjob_status", columnList = "status"),
    @Index(name = "idx_streamjob_user_time", columnList = "user_id, start_time DESC")
})
public class StreamJob {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private User user;

    private String streamKey;
    private String fileName;
    
    @Column(name = "worker_id")
    private String workerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StreamStatus status = StreamStatus.QUEUED;

    @Column(name = "last_heartbeat")
    private java.time.ZonedDateTime lastHeartbeat;

    @Column(name = "ledger_recorded", nullable = false)
    private boolean ledgerRecorded = false;

    @Column(name = "retry_count")
    private Integer retryCount = 0;

    @Column(name = "max_retries")
    private Integer maxRetries = 3;

    @Column(name = "stream_spec_json", columnDefinition = "jsonb")
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    private String streamSpecJson;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    @Column(name = "resume_from_seconds")
    private Integer resumeFromSeconds = 0;

    @Column(name = "idempotency_key", unique = true)
    private String idempotencyKey;

    @Column(name = "current_fps")
    private Double currentFps;

    @Column(name = "current_bitrate_kbps")
    private Double currentBitrateKbps;

    @Column(name = "dropped_frames")
    private Long droppedFrames = 0L;

    @Column(name = "bytes_streamed")
    private Long bytesStreamed = 0L;

    /**
     * The hourly rate that was active when this stream session started.
     */
    @Column(name = "locked_hourly_rate", nullable = false, precision = 19, scale = 4)
    private java.math.BigDecimal lockedHourlyRate = java.math.BigDecimal.ZERO;

    public enum StreamStatus {
        QUEUED,
        PREPARING,
        LIVE,
        RECONNECTING,
        RECOVERY_PENDING,
        STOP_REQUESTED,
        COMPLETED,
        FAILED
    }
    private String musicName;
    private String musicVolume;

    private String destinationName;

    // Metadata
    @Column(columnDefinition = "TEXT")
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    private String privacy;
    private java.time.ZonedDateTime startTime;
    private java.time.ZonedDateTime endTime;
    
    @Column(precision = 19, scale = 4)
    private java.math.BigDecimal cost;

    private java.time.ZonedDateTime lastBillingTime;
    
    @Column(precision = 19, scale = 4)
    private java.math.BigDecimal accumulatedCost = java.math.BigDecimal.ZERO;

    private boolean autoReplyEnabled;

    @Column(columnDefinition = "TEXT")
    private String lastPageToken;
    private String liveChatId;

    @com.fasterxml.jackson.annotation.JsonProperty("username")
    public String getUsername() {
        return user != null ? user.getUsername() : null;
    }

    public void setUsername(String username) {
        // use setUser
    }

    public StreamJob(
            User user,
            String streamKey,
            String fileName,
            String musicName,
            String musicVolume,
            String title,
            String description,
            String privacy,
            java.time.ZonedDateTime startTime,
            String destinationName,
            boolean autoReplyEnabled
    ) {
        this.user = user;
        this.streamKey = streamKey;
        this.fileName = fileName;
        this.musicName = musicName;
        this.musicVolume = musicVolume;
        this.title = title;
        this.description = description;
        this.privacy = privacy;
        this.startTime = startTime;
        this.destinationName = destinationName;
        this.autoReplyEnabled = autoReplyEnabled;
        this.status = StreamStatus.QUEUED;
    }

}
