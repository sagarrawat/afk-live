package com.afklive.streamer.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;
import java.util.List;

@Entity
@Data
@NoArgsConstructor
@Table(name = "scheduled_streams")
public class ScheduledStream {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private User user;

    @ManyToOne
    @JoinColumn(name = "media_asset_id")
    private MediaAsset mediaAsset;

    @ManyToMany
    @JoinTable(
        name = "scheduled_stream_destinations",
        joinColumns = @JoinColumn(name = "scheduled_stream_id"),
        inverseJoinColumns = @JoinColumn(name = "stream_destination_id")
    )
    private java.util.List<StreamDestination> destinations;

    @Transient
    private List<String> streamKeys; // Backward compat for DTO

    @jakarta.validation.constraints.NotNull(message = "Scheduled time is required")
    @Future(message = "Scheduled time must be in the future")
    private ZonedDateTime scheduledTime;

    @Enumerated(EnumType.STRING)
    private StreamStatus status;

    // Optional settings
    private String musicName;
    private String musicVolume; // "1.0"
    private int loopCount = -1;
    private String streamMode = "original";
    private int streamQuality = 0;
    private boolean muteVideoAudio = true;

    // To track active PID if needed, though StreamService manages active ones.
    // This is primarily for scheduling the *start*.

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    // Legacy Getter
    @com.fasterxml.jackson.annotation.JsonIgnore
    public String getVideoKey() {
        return mediaAsset != null ? mediaAsset.getS3Key() : null;
    }

    public void setVideoKey(String videoKey) {
        // No-op or handle in service to lookup asset
    }

    @com.fasterxml.jackson.annotation.JsonProperty("username")
    public String getUsername() {
        return user != null ? user.getUsername() : null;
    }

    public void setUsername(String username) {
        // no-op, use setUser
    }

    public enum StreamStatus {
        PENDING,
        RUNNING, // Currently active (might not need to track here if StreamJob does)
        COMPLETED, // Successfully started
        FAILED,
        CANCELLED
    }
}
