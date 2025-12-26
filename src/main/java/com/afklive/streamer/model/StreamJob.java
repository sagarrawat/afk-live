package com.afklive.streamer.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
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

    public StreamJob(
            String username,
            String streamKey,
            String fileName,
            String musicName,
            String musicVolume,
            boolean live,
            long pid
    ) {
        this.username = username;
        this.streamKey = streamKey;
        this.fileName = fileName;
        this.musicName = musicName;
        this.musicVolume = musicVolume;
        this.isLive = live;
        this.pid = pid;
    }

}