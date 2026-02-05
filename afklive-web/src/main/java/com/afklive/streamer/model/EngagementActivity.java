package com.afklive.streamer.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "engagement_activity", indexes = {
    @Index(name = "idx_engagement_user_time", columnList = "username, timestamp DESC")
})
public class EngagementActivity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String username;
    private String actionType; // REPLY, DELETE, LIKE
    private String commentId; // The ID of the comment being acted upon
    private String videoId;
    private String createdCommentId; // The ID of the NEW comment created (for replies)

    @Column(columnDefinition = "TEXT")
    private String content; // Reply text or original comment text if deleted

    private LocalDateTime timestamp = LocalDateTime.now();

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getActionType() { return actionType; }
    public void setActionType(String actionType) { this.actionType = actionType; }

    public String getCommentId() { return commentId; }
    public void setCommentId(String commentId) { this.commentId = commentId; }

    public String getVideoId() { return videoId; }
    public void setVideoId(String videoId) { this.videoId = videoId; }

    public String getCreatedCommentId() { return createdCommentId; }
    public void setCreatedCommentId(String createdCommentId) { this.createdCommentId = createdCommentId; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }
}
