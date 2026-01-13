package com.afklive.streamer.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "scheduled_videos")
public class ScheduledVideo {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String username;
    private String title;

    @Column(length = 5000)
    private String description;

    private String tags;
    private String privacyStatus; // "public", "private", "unlisted"

    private LocalDateTime scheduledTime;

    private String s3Key;

    @Enumerated(EnumType.STRING)
    private VideoStatus status;

    private String youtubeVideoId;

    private String errorMessage;

    public enum VideoStatus {
        LIBRARY,
        PENDING,
        PROCESSING,
        UPLOADED,
        FAILED
    }
}
