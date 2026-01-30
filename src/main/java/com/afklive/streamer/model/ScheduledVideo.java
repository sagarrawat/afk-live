package com.afklive.streamer.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "scheduled_videos")
public class ScheduledVideo {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String username;

    @Column(length = 255) // Still VARCHAR(255) for DB, validation handles 100
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    private String tags;
    private String privacyStatus;
    private String categoryId;
    private LocalDateTime scheduledTime;

    private String s3Key;
    private String thumbnailS3Key;
    private String youtubeVideoId;

    private String firstComment;

    private Long fileSize; // in bytes

    private Long socialChannelId; // Link to specific channel

    @Enumerated(EnumType.STRING)
    private VideoStatus status = VideoStatus.PENDING;

    private String errorMessage;

    @Enumerated(EnumType.STRING)
    private OptimizationStatus optimizationStatus = OptimizationStatus.NOT_OPTIMIZED;

    public enum VideoStatus {
        PENDING, PROCESSING, UPLOADED, FAILED, LIBRARY
    }

    public enum OptimizationStatus {
        NOT_OPTIMIZED, IN_PROGRESS, COMPLETED, FAILED
    }

    // Getters and Setters

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getTags() {
        return tags;
    }

    public void setTags(String tags) {
        this.tags = tags;
    }

    public String getPrivacyStatus() {
        return privacyStatus;
    }

    public void setPrivacyStatus(String privacyStatus) {
        this.privacyStatus = privacyStatus;
    }

    public String getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(String categoryId) {
        this.categoryId = categoryId;
    }

    public LocalDateTime getScheduledTime() {
        return scheduledTime;
    }

    public void setScheduledTime(LocalDateTime scheduledTime) {
        this.scheduledTime = scheduledTime;
    }

    public String getS3Key() {
        return s3Key;
    }

    public void setS3Key(String s3Key) {
        this.s3Key = s3Key;
    }

    public String getThumbnailS3Key() {
        return thumbnailS3Key;
    }

    public void setThumbnailS3Key(String thumbnailS3Key) {
        this.thumbnailS3Key = thumbnailS3Key;
    }

    public VideoStatus getStatus() {
        return status;
    }

    public void setStatus(VideoStatus status) {
        this.status = status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getYoutubeVideoId() {
        return youtubeVideoId;
    }

    public void setYoutubeVideoId(String youtubeVideoId) {
        this.youtubeVideoId = youtubeVideoId;
    }

    public String getFirstComment() {
        return firstComment;
    }

    public void setFirstComment(String firstComment) {
        this.firstComment = firstComment;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public Long getSocialChannelId() {
        return socialChannelId;
    }

    public void setSocialChannelId(Long socialChannelId) {
        this.socialChannelId = socialChannelId;
    }

    public OptimizationStatus getOptimizationStatus() {
        return optimizationStatus;
    }

    public void setOptimizationStatus(OptimizationStatus optimizationStatus) {
        this.optimizationStatus = optimizationStatus;
    }
}
