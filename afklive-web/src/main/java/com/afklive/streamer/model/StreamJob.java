package com.afklive.streamer.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(indexes = {
    @Index(name = "idx_streamjob_user_live", columnList = "username, is_live"),
    @Index(name = "idx_streamjob_live", columnList = "is_live"),
    @Index(name = "idx_streamjob_user_time", columnList = "username, start_time DESC"),
    @Index(name = "idx_streamjob_live_reply", columnList = "is_live, auto_reply_enabled")
})
public class StreamJob {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String username;
    private String streamKey;
    private String fileName;
    private boolean isLive;
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

    private java.time.ZonedDateTime lastBillingTime;
    private double accumulatedCost = 0.0;

    private boolean autoReplyEnabled;

    @Column(columnDefinition = "TEXT")
    private String lastPageToken;
    private String liveChatId;

    public StreamJob(
            String username,
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
        this.username = username;
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
