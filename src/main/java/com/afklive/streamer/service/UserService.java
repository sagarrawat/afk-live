package com.afklive.streamer.service;

import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    public User getOrCreateUser(String username) {
        return userRepository.findById(username)
                .orElseGet(() -> userRepository.save(new User(username)));
    }

    public void checkStorageQuota(String username, long fileSize) {
        User user = getOrCreateUser(username);
        long limit = user.getPlanType().getMaxStorageBytes();
        if (user.getUsedStorageBytes() + fileSize > limit) {
            throw new IllegalStateException("Storage quota exceeded. Limit: " + (limit / 1024 / 1024) + "MB.");
        }
    }

    @Transactional
    public void updateStorageUsage(String username, long delta) {
        User user = getOrCreateUser(username);
        long newUsage = user.getUsedStorageBytes() + delta;
        user.setUsedStorageBytes(Math.max(0, newUsage)); // Prevent negative
        userRepository.save(user);
    }

    public void checkStreamQuota(String username, int currentActiveCount) {
        User user = getOrCreateUser(username);
        int limit = user.getPlanType().getMaxActiveStreams();
        if (currentActiveCount >= limit) {
             throw new IllegalStateException("Active stream limit reached (" + limit + ") for plan " + user.getPlanType().getDisplayName());
        }
    }
}
