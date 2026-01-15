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
    private String resetToken;
    private String fullName;

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

    public java.util.List<SocialChannel> getChannels() {
        return channels;
    }

    public void setChannels(java.util.List<SocialChannel> channels) {
        this.channels = channels;
    }
}
