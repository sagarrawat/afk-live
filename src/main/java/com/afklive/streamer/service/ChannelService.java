package com.afklive.streamer.service;

import com.afklive.streamer.model.SocialChannel;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class ChannelService {

    private final UserRepository userRepository;
    private final UserService userService;
    private final YouTubeService youTubeService;

    public ChannelService(UserRepository userRepository, UserService userService, YouTubeService youTubeService) {
        this.userRepository = userRepository;
        this.userService = userService;
        this.youTubeService = youTubeService;
    }

    @Transactional
    public void syncChannelFromGoogle(String username) {
        try {
            String channelName = youTubeService.getChannelName(username);

            // Check if already exists
            User user = userService.getOrCreateUser(username);
            boolean exists = user.getChannels().stream()
                    .anyMatch(c -> c.getName().equals(channelName) && "YOUTUBE".equals(c.getPlatform()));

            if (!exists) {
                addChannel(username, channelName);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to sync YouTube channel: " + e.getMessage());
        }
    }

    @Transactional
    public SocialChannel addChannel(String username, String channelName, String platform) {
        User user = userService.getOrCreateUser(username);

        int limit = user.getPlanType().getMaxChannels();
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
        return user.getChannels();
    }

    @Transactional
    public void removeChannel(String username, Long channelId) {
        User user = userService.getOrCreateUser(username);
        user.getChannels().removeIf(c -> c.getId().equals(channelId));
        userRepository.save(user);
    }
}
