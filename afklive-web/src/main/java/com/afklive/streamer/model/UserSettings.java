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

    // The automatic-reply settings (auto_reply_enabled, delete_negative_comments,
    // auto_reply_unreplied_enabled, auto_reply_unreplied_message) are no longer mapped: AFK Live never
    // posts or deletes on YouTube by itself (AI-3). The columns are dropped in a later release.

    public UserSettings(User user) {
        this.user = user;
    }
}
