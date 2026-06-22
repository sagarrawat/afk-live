package com.afklive.streamer.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "media_assets", indexes = {
    @Index(name = "idx_media_assets_user", columnList = "user_id"),
    @Index(name = "idx_media_assets_s3key", columnList = "s3_key")
})
@Data
@NoArgsConstructor
public class MediaAsset {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private User user;

    private String title; // Filename

    @Column(name = "s3_key", nullable = false)
    private String s3Key;

    private Long fileSize = 0L;

    @Enumerated(EnumType.STRING)
    private ContentType contentType = ContentType.VIDEO;

    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "optimized_s3_key")
    private String optimizedS3Key;

    @Enumerated(EnumType.STRING)
    @Column(name = "stream_readiness", nullable = false)
    private StreamReadiness streamReadiness = StreamReadiness.PENDING_ANALYSIS;

    @Column(name = "video_codec")
    private String videoCodec;

    @Column(name = "audio_codec")
    private String audioCodec;

    private String resolution;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @Column(name = "bitrate_kbps")
    private Integer bitrateKbps;

    @Column(name = "frame_rate")
    private Double frameRate;

    @Column(name = "stream_profile")
    private String streamProfile;

    @Column(name = "probe_data", columnDefinition = "TEXT")
    private String probeData;

    @Column(name = "optimization_retry_count")
    private Integer optimizationRetryCount = 0;

    public enum ContentType {
        VIDEO, IMAGE, AUDIO
    }

    public enum StreamReadiness {
        PENDING_ANALYSIS,
        STREAM_READY,
        REQUIRES_OPTIMIZATION,
        OPTIMIZATION_FAILED
    }

    public MediaAsset(User user, String title, String s3Key, Long fileSize, ContentType contentType) {
        this.user = user;
        this.title = title;
        this.s3Key = s3Key;
        this.fileSize = fileSize;
        this.contentType = contentType;
        this.createdAt = LocalDateTime.now();
        this.streamReadiness = StreamReadiness.PENDING_ANALYSIS;
    }
}
