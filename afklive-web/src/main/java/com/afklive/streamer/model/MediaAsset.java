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
    private User user;

    private String title; // Filename

    @Column(name = "s3_key", nullable = false)
    private String s3Key;

    private Long fileSize = 0L;

    @Enumerated(EnumType.STRING)
    private ContentType contentType = ContentType.VIDEO;

    private LocalDateTime createdAt = LocalDateTime.now();

    /** Facts playout needs, filled when the file is probed (null until then). */
    private Double durationSeconds;
    private Integer width;
    private Integer height;
    private String videoCodec;
    private Boolean hasAudio;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MediaStatus status = MediaStatus.READY;
    @Column(columnDefinition = "TEXT")
    private String statusReason;
    private Double fps;

    /** Small JPEG for the library grid (videos and images), null until processed. */
    @Column(name = "thumbnail_key", length = 512)
    private String thumbnailKey;

    /** Set by the instance processing this file; a stale claim is taken over (ML-2). */
    @Column(name = "processing_claimed_at")
    private java.time.ZonedDateTime processingClaimedAt;

    public enum ContentType {
        VIDEO, IMAGE, AUDIO
    }

    public MediaAsset(User user, String title, String s3Key, Long fileSize, ContentType contentType) {
        this.user = user;
        this.title = title;
        this.s3Key = s3Key;
        this.fileSize = fileSize;
        this.contentType = contentType;
        this.createdAt = LocalDateTime.now();
    }
}
