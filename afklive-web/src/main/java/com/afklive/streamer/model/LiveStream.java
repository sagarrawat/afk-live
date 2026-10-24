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

    /** Phone streams: SHA-256 of the ingest key, for the ingest server's lookups. */
    @Column(name = "ingest_key_hash", length = 64)
    @JsonIgnore
    private String ingestKeyHash;

    /** The ingest key itself, encrypted at rest; shown to the owner to set up their phone. */
    @Column(name = "ingest_key", columnDefinition = "TEXT")
    @Convert(converter = com.afklive.streamer.util.EncryptedStringConverter.class)
    @JsonIgnore
    private String ingestKey;

    /** Last time the phone app connected (so the setup page can say "connected"). */
    @Column(name = "ingest_last_publish_at")
    private ZonedDateTime ingestLastPublishAt;

    /** MS-2: end the stream if the phone stays away longer than the grace time (else BRB stays on). */
    @Column(name = "end_on_input_loss", nullable = false)
    private boolean endOnInputLoss = false;

    @Column(name = "input_grace_seconds", nullable = false)
    private int inputGraceSeconds = 120;

    /** SRT buffer: 1000 ms default on 4G/5G, 2000 ms for unstable networks (latency NFR). */
    @Column(name = "srt_latency_ms", nullable = false)
    private int srtLatencyMs = 1000;

    /** PL-6 / MS-10: FIT (bars), FILL (crop) or BLUR (blurred background); items may override. */
    @Column(nullable = false, length = 10)
    private String fit = "FIT";

    /** AU-3: seconds the music overlaps from one track into the next (0: straight cut). */
    @Column(name = "crossfade_seconds", nullable = false)
    private int crossfadeSeconds = 0;

    /** GR-9: the title the creator uses on YouTube, typed into AFK Live so the go-live check can read it. */
    @Column(name = "youtube_title", length = 100)
    private String youtubeTitle;

    /** GR-9: the thumbnail picture (an image in the library) the creator uses on YouTube. */
    @Column(name = "thumbnail_media_id")
    private Long thumbnailMediaId;

    /** GR-9: comma-separated go-live check flags the creator chose to keep (dismissed). */
    @Column(name = "check_dismissed", length = 500)
    @JsonIgnore
    private String checkDismissed;

    @Column(name = "created_at", nullable = false)
    private ZonedDateTime createdAt = ZonedDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private ZonedDateTime updatedAt = ZonedDateTime.now();

    @PreUpdate
    void touch() {
        updatedAt = ZonedDateTime.now();
    }
}
