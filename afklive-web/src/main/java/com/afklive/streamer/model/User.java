package com.afklive.streamer.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "users")
public class User {
    @Id
    private String username; // Email from OAuth

    @Enumerated(EnumType.STRING)
    private PlanType planType = PlanType.FREE;

    private long usedStorageBytes = 0;

    private String password;
    private boolean enabled = true;
    private String verificationToken;
    private LocalDateTime lastVerificationSentAt;
    private String resetToken;
    private String fullName;
    private String pictureUrl;

    private String role = "ROLE_USER";

    private boolean autoReplyEnabled = false;
    private boolean deleteNegativeComments = false;
    private boolean autoReplyUnrepliedEnabled = false;
    private String autoReplyUnrepliedMessage;

    private int streamSlots = 0;
    private LocalDateTime streamAccessExpiration;

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    private java.util.List<SocialChannel> channels = new java.util.ArrayList<>();

    public User() {
    }

    public User(String username) {
        this.username = username;
    }

    // Getters and Setters

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public PlanType getPlanType() {
        return planType;
    }

    public void setPlanType(PlanType planType) {
        this.planType = planType;
    }

    public long getUsedStorageBytes() {
        return usedStorageBytes;
    }

    public void setUsedStorageBytes(long usedStorageBytes) {
        this.usedStorageBytes = usedStorageBytes;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getVerificationToken() {
        return verificationToken;
    }

    public void setVerificationToken(String verificationToken) {
        this.verificationToken = verificationToken;
    }

    public LocalDateTime getLastVerificationSentAt() {
        return lastVerificationSentAt;
    }

    public void setLastVerificationSentAt(LocalDateTime lastVerificationSentAt) {
        this.lastVerificationSentAt = lastVerificationSentAt;
    }

    public String getResetToken() {
        return resetToken;
    }

    public void setResetToken(String resetToken) {
        this.resetToken = resetToken;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getPictureUrl() {
        return pictureUrl;
    }

    public void setPictureUrl(String pictureUrl) {
        this.pictureUrl = pictureUrl;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public boolean isAutoReplyEnabled() {
        return autoReplyEnabled;
    }

    public void setAutoReplyEnabled(boolean autoReplyEnabled) {
        this.autoReplyEnabled = autoReplyEnabled;
    }

    public boolean isDeleteNegativeComments() {
        return deleteNegativeComments;
    }

    public void setDeleteNegativeComments(boolean deleteNegativeComments) {
        this.deleteNegativeComments = deleteNegativeComments;
    }

    public boolean isAutoReplyUnrepliedEnabled() {
        return autoReplyUnrepliedEnabled;
    }

    public void setAutoReplyUnrepliedEnabled(boolean autoReplyUnrepliedEnabled) {
        this.autoReplyUnrepliedEnabled = autoReplyUnrepliedEnabled;
    }

    public String getAutoReplyUnrepliedMessage() {
        return autoReplyUnrepliedMessage;
    }

    public void setAutoReplyUnrepliedMessage(String autoReplyUnrepliedMessage) {
        this.autoReplyUnrepliedMessage = autoReplyUnrepliedMessage;
    }

    public int getStreamSlots() {
        return streamSlots;
    }

    public void setStreamSlots(int streamSlots) {
        this.streamSlots = streamSlots;
    }

    public LocalDateTime getStreamAccessExpiration() {
        return streamAccessExpiration;
    }

    public void setStreamAccessExpiration(LocalDateTime streamAccessExpiration) {
        this.streamAccessExpiration = streamAccessExpiration;
    }

    public java.util.List<SocialChannel> getChannels() {
        return channels;
    }

    public void setChannels(java.util.List<SocialChannel> channels) {
        this.channels = channels;
    }
}
