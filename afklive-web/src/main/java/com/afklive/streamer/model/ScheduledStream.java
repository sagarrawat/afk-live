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

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private User user;

    private String videoKey; // S3 Key or Filename

    @ElementCollection
    @CollectionTable(name = "scheduled_stream_stream_keys", joinColumns = @JoinColumn(name = "scheduled_stream_id"))
    @Column(name = "stream_keys")
    @NotEmpty(message = "At least one destination required")
    private List<String> streamKeys;

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
    private boolean muteVideoAudio = true;

    // To track active PID if needed, though StreamService manages active ones.
    // This is primarily for scheduling the *start*.

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

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
