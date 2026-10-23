package com.afklive.streamer.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@org.hibernate.annotations.DynamicUpdate
@Table(name = "stream_jobs", indexes = {
    @Index(name = "idx_streamjob_user_live", columnList = "user_id, is_live"),
    @Index(name = "idx_streamjob_live", columnList = "is_live"),
    @Index(name = "idx_streamjob_user_time", columnList = "user_id, start_time DESC"),
    @Index(name = "idx_streamjob_live_reply", columnList = "is_live, auto_reply_enabled")
})
public class StreamJob {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private User user;

    @com.fasterxml.jackson.annotation.JsonIgnore
    private String streamKey;
    private String fileName;
    private boolean isLive;
    @com.fasterxml.jackson.annotation.JsonIgnore
    private long pid;
    private String musicName;
    private String musicVolume;

    private String destinationName;

    // Metadata
    @Column(columnDefinition = "TEXT")
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    private String privacy;
    private java.time.ZonedDateTime startTime;
    private java.time.ZonedDateTime endTime;
    private Double cost;

    /** Lifecycle as reported by the worker: STARTING, LIVE, RECONNECTING, ENDED, FAILED. */
    private String status;
    /** What the web app wants: RUN or STOP. Workers act on it. */
    private String desiredState;
    /** Everything a worker needs to run the stream (JSON StreamSpec). Contains stream keys: never sent to clients. */
    @Column(columnDefinition = "TEXT")
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String spec;
    /** The worker running this job; null until claimed. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String workerId;
    /** Last time the worker confirmed the job is running. A stale heartbeat means the worker is gone. */
    private java.time.ZonedDateTime heartbeatAt;
    private int restarts;
    private Double bitrateKbps;
    private Double fps;
    private Double speed;
    private Long droppedFrames;
    @Column(columnDefinition = "TEXT")
    private String lastError;

    @lombok.ToString.Exclude
    @lombok.EqualsAndHashCode.Exclude
    @OneToMany(mappedBy = "job", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("position ASC")
    private java.util.List<StreamJobDestination> destinations = new java.util.ArrayList<>();

    /** When set, the stream is stopped automatically at this time. */
    private java.time.ZonedDateTime stopAt;

    /** The saved stream this run belongs to (null for legacy one-off runs). */
    @Column(name = "stream_id")
    private Long streamId;
    /** Scene on screen, whether the phone input is live, and what's playing: reported by the worker. */
    private String scene;
    private Boolean inputLive;
    @Column(name = "now_playing", columnDefinition = "TEXT")
    private String nowPlaying;
    /** Why the stream ended (user, schedule, credit limit, crash, server restart). */
    private String endReason;

    private java.time.ZonedDateTime lastBillingTime;
    private double accumulatedCost = 0.0;

    private boolean autoReplyEnabled;

    @Column(columnDefinition = "TEXT")
    private String lastPageToken;
    private String liveChatId;

    @com.fasterxml.jackson.annotation.JsonProperty("username")
    public String getUsername() {
        return user != null ? user.getUsername() : null;
    }

    public void setUsername(String username) {
        // use setUser
    }

    public StreamJob(
            User user,
            String streamKey,
            String fileName,
            String musicName,
            String musicVolume,
            boolean live,
            long pid,
            String title,
            String description,
            String privacy,
            java.time.ZonedDateTime startTime,
            String destinationName,
            boolean autoReplyEnabled
    ) {
        this.user = user;
        this.streamKey = streamKey;
        this.fileName = fileName;
        this.musicName = musicName;
        this.musicVolume = musicVolume;
        this.isLive = live;
        this.pid = pid;
        this.title = title;
        this.description = description;
        this.privacy = privacy;
        this.startTime = startTime;
        this.destinationName = destinationName;
        this.autoReplyEnabled = autoReplyEnabled;
    }

}
