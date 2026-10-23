package com.afklive.streamer.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;

/**
 * A saved broadcast: what plays (a playlist, or the creator's phone), how it looks and sounds, and
 * where it goes. It stays a draft until it goes live; each time it goes live is a {@link StreamJob}.
 */
@Entity
@Table(name = "streams")
@Data
@NoArgsConstructor
@org.hibernate.annotations.DynamicUpdate
public class LiveStream {

    public enum Source { PLAYLIST, PHONE }

    public enum Orientation { LANDSCAPE, VERTICAL }

    public enum Order { IN_ORDER, SHUFFLE }

    /** What viewers hear from the videos themselves: their own audio, nothing, or their audio under the music. */
    public enum AudioMode { KEEP, MUTE, MIX }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "user_id", nullable = false)
    @JsonIgnore
    private User user;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Source source = Source.PLAYLIST;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Orientation orientation = Orientation.LANDSCAPE;

    @Column(name = "quality_height", nullable = false)
    private int qualityHeight = 720;

    @Column(nullable = false)
    private int fps = 30;

    @Enumerated(EnumType.STRING)
    @Column(name = "playback_order", nullable = false)
    private Order playbackOrder = Order.IN_ORDER;

    /** 0 = loop forever. */
    @Column(name = "loop_count", nullable = false)
    private int loopCount = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "music_order", nullable = false)
    private Order musicOrder = Order.IN_ORDER;

    @Enumerated(EnumType.STRING)
    @Column(name = "audio_mode", nullable = false)
    private AudioMode audioMode = AudioMode.KEEP;

    @Column(name = "video_volume", nullable = false)
    private double videoVolume = 1.0;

    @Column(name = "music_volume", nullable = false)
    private double musicVolume = 0.6;


    /** JSON list of overlay settings (see StreamSettings.OverlaySetting). */
    @Column(columnDefinition = "TEXT")
    private String overlays;

    /** JSON scene settings (see StreamSettings.SceneSetting). */
    @Column(columnDefinition = "TEXT")
    private String scenes;

    @Column(name = "time_zone", nullable = false, length = 64)
    private String timeZone = "UTC";

    /** JSON playlist position saved by the worker, so the next run (or a restart) resumes where it stopped. */
    @Column(name = "resume_position", columnDefinition = "TEXT")
    @JsonIgnore
    private String resumePosition;

    @Column(name = "created_at", nullable = false)
    private ZonedDateTime createdAt = ZonedDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private ZonedDateTime updatedAt = ZonedDateTime.now();

    @PreUpdate
    void touch() {
        updatedAt = ZonedDateTime.now();
    }
}
