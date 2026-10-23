package com.afklive.streamer.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "users", indexes = {
    @Index(name = "idx_user_verif_token", columnList = "verification_token"),
    @Index(name = "idx_user_reset_token", columnList = "reset_token"),
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

    /** Changed only by atomic updates (UserRepository.reserveStorage / addStorage), never by saving the entity. */
    @Column(name = "used_storage_bytes", insertable = false, updatable = false)
    private long usedStorageBytes = 0;

    /** ON-3: the creator's time zone (IANA id) and billing currency (INR or USD); null until set. */
    @Column(name = "time_zone", length = 64)
    private String timeZone;
    @Column(length = 3)
    private String currency;
    /** ON-2: when the setup wizard was finished or skipped. */
    @Column(name = "onboarded_at")
    private java.time.ZonedDateTime onboardedAt;
    /** GL-6: tips the creator has dismissed, comma-separated ids (see MeController.HINTS); null: none. */
    @Column(name = "dismissed_hints", length = 500)
    private String dismissedHints;

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

    /** NT-1/NT-2: send alerts by email as well as in the app. */
    @Column(name = "email_alerts", nullable = false)
    private boolean emailAlerts = true;

    /** NT-3: per kind of alert, email and push on or off (JSON, see AlertPreferences); null: all on. */
    @Column(name = "alert_preferences", columnDefinition = "TEXT")
    private String alertPreferences;

    /** ON-6: the authenticator app secret (Base32), encrypted at rest; null when two-step sign-in is off. */
    @JsonIgnore
    @Column(name = "two_factor_secret", columnDefinition = "TEXT")
    @Convert(converter = com.afklive.streamer.util.EncryptedStringConverter.class)
    private String twoFactorSecret;

    /** ON-6: a secret being set up, until the creator confirms it with a code. */
    @JsonIgnore
    @Column(name = "two_factor_pending_secret", columnDefinition = "TEXT")
    @Convert(converter = com.afklive.streamer.util.EncryptedStringConverter.class)
    private String twoFactorPendingSecret;

    @Column(name = "two_factor_enabled_at")
    private java.time.ZonedDateTime twoFactorEnabledAt;

    @JsonIgnore
    @Column(name = "two_factor_last_step")
    private Long twoFactorLastStep;

    @JsonIgnore
    @Column(name = "two_factor_failures", nullable = false)
    private int twoFactorFailures;

    @JsonIgnore
    @Column(name = "two_factor_locked_until")
    private java.time.ZonedDateTime twoFactorLockedUntil;

    /** ON-7: when the creator asked to delete the account, and when its data goes. */
    @Column(name = "deletion_requested_at")
    private java.time.ZonedDateTime deletionRequestedAt;

    @Column(name = "deletion_scheduled_for")
    private java.time.ZonedDateTime deletionScheduledFor;

    @OneToOne(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private UserSettings settings;

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

    public UserSettings getSettings() {
        return settings;
    }

    public void setSettings(UserSettings settings) {
        this.settings = settings;
        if (settings != null) {
            settings.setUser(this);
        }
    }

    // Delegate methods for backward compatibility
    @JsonIgnore
    public boolean isAutoReplyEnabled() {
        return settings != null && settings.isAutoReplyEnabled();
    }

    public void setAutoReplyEnabled(boolean autoReplyEnabled) {
        if (settings == null) settings = new UserSettings(this);
        settings.setAutoReplyEnabled(autoReplyEnabled);
    }

    @JsonIgnore
    public boolean isDeleteNegativeComments() {
        return settings != null && settings.isDeleteNegativeComments();
    }

    public void setDeleteNegativeComments(boolean deleteNegativeComments) {
        if (settings == null) settings = new UserSettings(this);
        settings.setDeleteNegativeComments(deleteNegativeComments);
    }

    @JsonIgnore
    public boolean isAutoReplyUnrepliedEnabled() {
        return settings != null && settings.isAutoReplyUnrepliedEnabled();
    }

    public void setAutoReplyUnrepliedEnabled(boolean autoReplyUnrepliedEnabled) {
        if (settings == null) settings = new UserSettings(this);
        settings.setAutoReplyUnrepliedEnabled(autoReplyUnrepliedEnabled);
    }

    @JsonIgnore
    public String getAutoReplyUnrepliedMessage() {
        return settings != null ? settings.getAutoReplyUnrepliedMessage() : null;
    }

    public void setAutoReplyUnrepliedMessage(String autoReplyUnrepliedMessage) {
        if (settings == null) settings = new UserSettings(this);
        settings.setAutoReplyUnrepliedMessage(autoReplyUnrepliedMessage);
    }

    public java.util.List<SocialChannel> getChannels() {
        return channels;
    }

    public void setChannels(java.util.List<SocialChannel> channels) {
        this.channels = channels;
    }

    public boolean isEmailAlerts() {
        return emailAlerts;
    }

    public void setEmailAlerts(boolean emailAlerts) {
        this.emailAlerts = emailAlerts;
    }

    public String getTimeZone() {
        return timeZone;
    }

    public void setTimeZone(String timeZone) {
        this.timeZone = timeZone;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public java.time.ZonedDateTime getOnboardedAt() {
        return onboardedAt;
    }

    public void setOnboardedAt(java.time.ZonedDateTime onboardedAt) {
        this.onboardedAt = onboardedAt;
    }

    public String getDismissedHints() {
        return dismissedHints;
    }

    public void setDismissedHints(String dismissedHints) {
        this.dismissedHints = dismissedHints;
    }

    public String getAlertPreferences() {
        return alertPreferences;
    }

    public void setAlertPreferences(String alertPreferences) {
        this.alertPreferences = alertPreferences;
    }

    @JsonIgnore
    public String getTwoFactorSecret() {
        return twoFactorSecret;
    }

    public void setTwoFactorSecret(String twoFactorSecret) {
        this.twoFactorSecret = twoFactorSecret;
    }

    @JsonIgnore
    public String getTwoFactorPendingSecret() {
        return twoFactorPendingSecret;
    }

    public void setTwoFactorPendingSecret(String twoFactorPendingSecret) {
        this.twoFactorPendingSecret = twoFactorPendingSecret;
    }

    public java.time.ZonedDateTime getTwoFactorEnabledAt() {
        return twoFactorEnabledAt;
    }

    public void setTwoFactorEnabledAt(java.time.ZonedDateTime twoFactorEnabledAt) {
        this.twoFactorEnabledAt = twoFactorEnabledAt;
    }

    @JsonIgnore
    public boolean isTwoFactorEnabled() {
        return twoFactorSecret != null;
    }

    @JsonIgnore
    public Long getTwoFactorLastStep() {
        return twoFactorLastStep;
    }

    public void setTwoFactorLastStep(Long twoFactorLastStep) {
        this.twoFactorLastStep = twoFactorLastStep;
    }

    @JsonIgnore
    public int getTwoFactorFailures() {
        return twoFactorFailures;
    }

    public void setTwoFactorFailures(int twoFactorFailures) {
        this.twoFactorFailures = twoFactorFailures;
    }

    @JsonIgnore
    public java.time.ZonedDateTime getTwoFactorLockedUntil() {
        return twoFactorLockedUntil;
    }

    public void setTwoFactorLockedUntil(java.time.ZonedDateTime twoFactorLockedUntil) {
        this.twoFactorLockedUntil = twoFactorLockedUntil;
    }

    public java.time.ZonedDateTime getDeletionRequestedAt() {
        return deletionRequestedAt;
    }

    public void setDeletionRequestedAt(java.time.ZonedDateTime deletionRequestedAt) {
        this.deletionRequestedAt = deletionRequestedAt;
    }

    public java.time.ZonedDateTime getDeletionScheduledFor() {
        return deletionScheduledFor;
    }

    public void setDeletionScheduledFor(java.time.ZonedDateTime deletionScheduledFor) {
        this.deletionScheduledFor = deletionScheduledFor;
    }
}
