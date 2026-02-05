package com.afklive.streamer.service;

import com.afklive.streamer.model.SocialChannel;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.UserRepository;
import org.hibernate.Hibernate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class ChannelService {

    private final UserRepository userRepository;
    private final UserService userService;
    private final YouTubeService youTubeService;
    private final PlanService planService;

    public ChannelService(UserRepository userRepository, UserService userService, YouTubeService youTubeService, PlanService planService) {
        this.userRepository = userRepository;
        this.userService = userService;
        this.youTubeService = youTubeService;
        this.planService = planService;
    }

    @Transactional
    public void syncChannelFromGoogle(String username) {
        syncChannelFromGoogle(username, username);
    }

    @Transactional
    public void syncChannelFromGoogle(String credentialId, String targetUsername) {
        try {
            String channelName = youTubeService.getChannelName(credentialId);

            User user = userService.getOrCreateUser(targetUsername);
            Optional<SocialChannel> existing = user.getChannels().stream()
                    .filter(c -> c.getName().equals(channelName) && "YOUTUBE".equals(c.getPlatform()))
                    .findFirst();

            if (existing.isPresent()) {
                // Update credential for existing channel (re-link)
                existing.get().setCredentialId(credentialId);
                userRepository.save(user);
            } else {
                SocialChannel ch = addChannel(targetUsername, channelName);
                ch.setCredentialId(credentialId);
                userRepository.save(user);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to sync YouTube channel: " + e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public String getCredentialId(String username) {
        User user = userService.getOrCreateUser(username);
        return user.getChannels().stream()
                .filter(c -> "YOUTUBE".equals(c.getPlatform()) && c.getCredentialId() != null)
                .findFirst()
                .map(SocialChannel::getCredentialId)
                .orElse(username);
    }

    @Transactional
    public SocialChannel addChannel(String username, String channelName, String platform) {
        User user = userService.getOrCreateUser(username);

        int limit = planService.getPlanConfig(user.getPlanType()).getMaxChannels();
        if (user.getChannels().size() >= limit) {
            throw new IllegalStateException("Plan limit reached. Upgrade to add more channels.");
        }

        if (platform == null || platform.isEmpty()) {
            platform = "YOUTUBE";
        }

        SocialChannel channel = new SocialChannel(channelName, platform, user);
        user.getChannels().add(channel);
        userRepository.save(user);
        return channel;
    }

    @Transactional
    public SocialChannel addChannel(String username, String channelName) {
        return addChannel(username, channelName, "YOUTUBE");
    }

    @Transactional(readOnly = true)
    public List<SocialChannel> getChannels(String username) {
        User user = userService.getOrCreateUser(username);
        Hibernate.initialize(user.getChannels());
        return user.getChannels();
    }

    @Transactional
    public void removeChannel(String username, Long channelId) {
        User user = userService.getOrCreateUser(username);
        user.getChannels().removeIf(c -> c.getId().equals(channelId));
        userRepository.save(user);
    }
}
