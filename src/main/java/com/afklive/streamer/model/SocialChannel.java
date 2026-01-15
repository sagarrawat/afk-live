package com.afklive.streamer.model;

import jakarta.persistence.*;

@Entity
@Table(name = "social_channels")
public class SocialChannel {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private String platform; // e.g., "YOUTUBE"
    private String profileUrl;

    @ManyToOne
    @JoinColumn(name = "user_id")
    private User user;

    public SocialChannel() {}

    public SocialChannel(String name, String platform, User user) {
        this.name = name;
        this.platform = platform;
        this.user = user;
        this.profileUrl = "https://ui-avatars.com/api/?name=" + name + "&background=random";
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }

    public String getProfileUrl() { return profileUrl; }
    public void setProfileUrl(String profileUrl) { this.profileUrl = profileUrl; }

    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
}
