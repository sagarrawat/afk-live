package com.afklive.streamer.model;

import jakarta.persistence.*;
import java.time.ZonedDateTime;

@Entity
@Table(name = "scheduled_videos", indexes = {
    @Index(name = "idx_schedvideo_user_status", columnList = "user_id, status"),
    @Index(name = "idx_schedvideo_status_time", columnList = "status, scheduled_time"),
    @Index(name = "idx_schedvideo_user_title", columnList = "user_id, title")
})
public class ScheduledVideo {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private User user;

    @Column(length = 255) // Still VARCHAR(255) for DB, validation handles 100
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @ManyToMany
    @JoinTable(
        name = "video_tags",
        joinColumns = @JoinColumn(name = "video_id"),
        inverseJoinColumns = @JoinColumn(name = "tag_id")
    )
    private java.util.Set<Tag> tagEntities = new java.util.HashSet<>();

    @Transient
    private String tags; // For backward compatibility in JSON/DTO

    private String privacyStatus;
    private String categoryId;
    private ZonedDateTime scheduledTime;

    @ManyToOne
    @JoinColumn(name = "media_asset_id")
    private MediaAsset mediaAsset;

    @ManyToOne
    @JoinColumn(name = "thumbnail_asset_id")
    private MediaAsset thumbnailAsset;

    private String youtubeVideoId;

    @Column(columnDefinition = "TEXT")
    private String firstComment;

    @ManyToOne
    @JoinColumn(name = "social_channel_id")
    @com.fasterxml.jackson.annotation.JsonIgnore
    private SocialChannel socialChannel;

    @Transient
    private Long transientSocialChannelId;

    @Enumerated(EnumType.STRING)
    private VideoStatus status = VideoStatus.PENDING;

    @Column(columnDefinition = "TEXT")
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

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    @com.fasterxml.jackson.annotation.JsonProperty("username")
    public String getUsername() {
        return user != null ? user.getUsername() : null;
    }

    public void setUsername(String username) {
        // use setUser
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
        if (tagEntities != null && !tagEntities.isEmpty()) {
            return tagEntities.stream().map(Tag::getName).collect(java.util.stream.Collectors.joining(","));
        }
        return tags;
    }

    public void setTags(String tags) {
        this.tags = tags;
        // Logic to update tagEntities should be handled in service to look up/create tags
    }

    public java.util.Set<Tag> getTagEntities() {
        return tagEntities;
    }

    public void setTagEntities(java.util.Set<Tag> tagEntities) {
        this.tagEntities = tagEntities;
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

    public ZonedDateTime getScheduledTime() {
        return scheduledTime;
    }

    public void setScheduledTime(ZonedDateTime scheduledTime) {
        this.scheduledTime = scheduledTime;
    }

    public MediaAsset getMediaAsset() {
        return mediaAsset;
    }

    public void setMediaAsset(MediaAsset mediaAsset) {
        this.mediaAsset = mediaAsset;
    }

    public MediaAsset getThumbnailAsset() {
        return thumbnailAsset;
    }

    public void setThumbnailAsset(MediaAsset thumbnailAsset) {
        this.thumbnailAsset = thumbnailAsset;
    }

    // Backward compatibility getters
    @com.fasterxml.jackson.annotation.JsonIgnore
    public String getS3Key() {
        return mediaAsset != null ? mediaAsset.getS3Key() : null;
    }

    public void setS3Key(String s3Key) {
        // Handled via MediaAsset now
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public String getThumbnailS3Key() {
        return thumbnailAsset != null ? thumbnailAsset.getS3Key() : null;
    }

    public void setThumbnailS3Key(String thumbnailS3Key) {
        // Handled via MediaAsset now
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
        return mediaAsset != null ? mediaAsset.getFileSize() : 0L;
    }

    public void setFileSize(Long fileSize) {
        // Handled via MediaAsset
    }

    public SocialChannel getSocialChannel() {
        return socialChannel;
    }

    public void setSocialChannel(SocialChannel socialChannel) {
        this.socialChannel = socialChannel;
    }

    @com.fasterxml.jackson.annotation.JsonProperty("socialChannelId")
    public Long getSocialChannelId() {
        return socialChannel != null ? socialChannel.getId() : transientSocialChannelId;
    }

    public void setSocialChannelId(Long socialChannelId) {
        this.transientSocialChannelId = socialChannelId;
    }

    public OptimizationStatus getOptimizationStatus() {
        return optimizationStatus;
    }

    public void setOptimizationStatus(OptimizationStatus optimizationStatus) {
        this.optimizationStatus = optimizationStatus;
    }
}
