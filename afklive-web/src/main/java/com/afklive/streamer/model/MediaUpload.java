package com.afklive.streamer.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;

/**
 * ML-1: a resumable upload. The file goes up in fixed-size parts (straight to object storage when it
 * supports presigned multipart uploads), so a dropped connection or a page refresh only loses the
 * part in flight. The file's size is reserved against the user's storage when the upload starts.
 */
@Entity
@Table(name = "media_uploads")
@Data
@NoArgsConstructor
public class MediaUpload {

    public enum Status { UPLOADING, COMPLETED, ABORTED }

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String filename;

    @Enumerated(EnumType.STRING)
    @Column(name = "content_type", nullable = false)
    private MediaAsset.ContentType contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "part_size", nullable = false)
    private long partSize;

    @Column(name = "storage_key", nullable = false, length = 512)
    private String storageKey;

    @Column(name = "multipart_id", length = 1024)
    private String multipartId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.UPLOADING;

    @Column(name = "media_asset_id")
    private Long mediaAssetId;

    @Column(name = "created_at", nullable = false)
    private ZonedDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private ZonedDateTime expiresAt;

    public int partCount() {
        return (int) Math.max(1, (sizeBytes + partSize - 1) / partSize);
    }
}
