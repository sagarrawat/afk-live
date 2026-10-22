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

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
        name = "scheduled_stream_destinations",
        joinColumns = @JoinColumn(name = "scheduled_stream_id"),
        inverseJoinColumns = @JoinColumn(name = "stream_destination_id")
    )
    private java.util.List<StreamDestination> destinations;

    private ZonedDateTime scheduledTime;

    @Enumerated(EnumType.STRING)
    private StreamStatus status;

    // Optional settings
    private String musicName;
    private String musicVolume; // "1.0"
    private int loopCount = -1;
    private String streamMode = "original";
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

    @com.fasterxml.jackson.annotation.JsonProperty("username")
    public String getUsername() {
        return user != null ? user.getUsername() : null;
    }

    public enum StreamStatus {
        PENDING,
        RUNNING, // Started successfully; the live stream itself is tracked as a StreamJob
        COMPLETED,
        FAILED,
        CANCELLED
    }
}
