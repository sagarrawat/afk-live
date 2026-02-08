package com.afklive.streamer.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "users", indexes = {
    @Index(name = "idx_user_verif_token", columnList = "verification_token"),
    @Index(name = "idx_user_reset_token", columnList = "reset_token"),
    @Index(name = "idx_user_autoreply", columnList = "auto_reply_enabled"),
    @Index(name = "idx_user_fullname", columnList = "full_name"),
    @Index(name = "idx_user_email", columnList = "email")
})
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email; // Formerly username, Email from OAuth

    @Enumerated(EnumType.STRING)
    private PlanType planType = PlanType.FREE;

    private long usedStorageBytes = 0;

    private String password;
    private boolean enabled = true;
    private String verificationToken;
    private LocalDateTime lastVerificationSentAt;
    private java.time.LocalDateTime planExpiration;
    private String resetToken;
    private String fullName;

    @Column(columnDefinition = "TEXT")
    private String pictureUrl;

    private String role = "ROLE_USER";

    private double unpaidBalance = 0.0;
    private double creditLimit = 50.0;

    private boolean autoReplyEnabled = false;
    private boolean deleteNegativeComments = false;
    private boolean autoReplyUnrepliedEnabled = false;

    @Column(columnDefinition = "TEXT")
    private String autoReplyUnrepliedMessage;

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    private java.util.List<SocialChannel> channels = new java.util.ArrayList<>();

    public User() {
    }

    public User(String email) {
        this.email = email;
    }

    // Getters and Setters

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    @JsonProperty("username")
    public String getUsername() {
        return email;
    }

    @JsonProperty("username")
    public void setUsername(String username) {
        this.email = username;
    }

    // Direct access to email if needed
    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
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

    public java.time.LocalDateTime getPlanExpiration() {
        return planExpiration;
    }

    public void setPlanExpiration(java.time.LocalDateTime planExpiration) {
        this.planExpiration = planExpiration;
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

    public double getUnpaidBalance() {
        return unpaidBalance;
    }

    public void setUnpaidBalance(double unpaidBalance) {
        this.unpaidBalance = unpaidBalance;
    }

    public double getCreditLimit() {
        return creditLimit;
    }

    public void setCreditLimit(double creditLimit) {
        this.creditLimit = creditLimit;
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

    public java.util.List<SocialChannel> getChannels() {
        return channels;
    }

    public void setChannels(java.util.List<SocialChannel> channels) {
        this.channels = channels;
    }
}
