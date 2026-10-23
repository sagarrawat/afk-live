package com.afklive.streamer.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;

/** An alert for a creator: shown in the app, and emailed when they allow it. */
@Entity
@Table(name = "notifications")
@Data
@NoArgsConstructor
public class Notification {

    public enum Level { INFO, SUCCESS, WARNING, ERROR }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    @JsonIgnore
    private Long userId;

    @Column(nullable = false, length = 40)
    private String type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Level level;

    @Column(name = "stream_id")
    private Long streamId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String body;

    @Column(length = 300)
    private String link;

    @Column(name = "dedupe_key", length = 200)
    @JsonIgnore
    private String dedupeKey;

    @Column(name = "created_at", nullable = false)
    private ZonedDateTime createdAt = ZonedDateTime.now();

    @Column(name = "read_at")
    private ZonedDateTime readAt;

    @Column(name = "emailed_at")
    private ZonedDateTime emailedAt;
}
