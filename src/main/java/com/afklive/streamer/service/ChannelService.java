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

    public ChannelService(UserRepository userRepository, UserService userService) {
        this.userRepository = userRepository;
        this.userService = userService;
    }

    @Transactional
    public SocialChannel addChannel(String username, String channelName) {
        User user = userService.getOrCreateUser(username);

        int limit = user.getPlanType().getMaxChannels();
        if (user.getChannels().size() >= limit) {
            throw new IllegalStateException("Plan limit reached. Upgrade to add more channels.");
        }

        SocialChannel channel = new SocialChannel(channelName, "YOUTUBE", user);
        user.getChannels().add(channel);
        userRepository.save(user);
        return channel;
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
