package com.afklive.streamer.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "user_settings")
@Data
@NoArgsConstructor
public class UserSettings {
    @Id
    private Long userId;

    @OneToOne
    @MapsId
    @JoinColumn(name = "user_id")
    @JsonIgnore
    private User user;

    private boolean autoReplyEnabled = false;
    private boolean deleteNegativeComments = false;
    private boolean autoReplyUnrepliedEnabled = false;

    @Column(columnDefinition = "TEXT")
    private String autoReplyUnrepliedMessage;

    public UserSettings(User user) {
        this.user = user;
    }
}
