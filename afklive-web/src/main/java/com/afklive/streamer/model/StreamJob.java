package com.afklive.streamer.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
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

    private boolean autoReplyEnabled;
    private String lastPageToken;

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
