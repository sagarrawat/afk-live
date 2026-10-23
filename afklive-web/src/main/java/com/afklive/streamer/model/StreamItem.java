package com.afklive.streamer.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One entry of a stream's video playlist or music playlist. */
@Entity
@Table(name = "stream_items")
@Data
@NoArgsConstructor
public class StreamItem {

    public enum Kind { VIDEO, AUDIO }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stream_id", nullable = false)
    private Long streamId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Kind kind;

    @Column(nullable = false)
    private int position;

    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "media_asset_id", nullable = false)
    private MediaAsset media;

    /** PL-6: this item's own fit (FIT, FILL, BLUR); null follows the stream. */
    @Column(length = 10)
    private String fit;

    public StreamItem(Long streamId, Kind kind, int position, MediaAsset media) {
        this.streamId = streamId;
        this.kind = kind;
        this.position = position;
        this.media = media;
    }
}
