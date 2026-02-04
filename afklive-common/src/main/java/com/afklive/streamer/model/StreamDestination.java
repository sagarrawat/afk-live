package com.afklive.streamer.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "stream_destinations")
@Data
@NoArgsConstructor
public class StreamDestination {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    @Column(name = "stream_key", length = 1024)
    private String streamKey;

    private String type; // "RTMP", "YOUTUBE_AUTO"

    private boolean selected;

    @ManyToOne
    @JoinColumn(name = "user_id")
    @JsonIgnore
    private User user;

    public StreamDestination(String name, String streamKey, String type, User user) {
        this.name = name;
        this.streamKey = streamKey;
        this.type = type;
        this.user = user;
        this.selected = true; // Default to selected on create
    }
}
