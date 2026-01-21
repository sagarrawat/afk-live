package com.afklive.streamer.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@Entity
@Table(name = "social_channels")
public class SocialChannel {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private String platform; // e.g., "YOUTUBE"
    private String profileUrl;
    private String credentialId;

    @ManyToOne
    @JoinColumn(name = "user_id")
    @JsonIgnore
    private User user;

    public SocialChannel(String name, String platform, String credentialId, User user) {
        this.name = name;
        this.platform = platform;
        this.credentialId = credentialId;
        this.user = user;
        this.profileUrl = "https://ui-avatars.com/api/?name=" + name + "&background=random";
    }
}
