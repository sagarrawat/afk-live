package com.afklive.streamer.service;

import com.afklive.streamer.model.PlanType;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.security.crypto.password.PasswordEncoder;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;

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

    public void registerUser(String email, String password, String name) {
        if (userRepository.existsById(email)) {
            throw new IllegalArgumentException("User already exists");
        }
        User user = new User(email);
        user.setPassword(passwordEncoder.encode(password));
        user.setFullName(name);
        user.setEnabled(false); // Email verification required
        user.setVerificationToken(UUID.randomUUID().toString());
        user.setPlanType(PlanType.FREE); // Default to Free plan
        userRepository.save(user);

        String link = "http://localhost:8080/verify-email?token=" + user.getVerificationToken();
        emailService.sendVerificationEmail(email, link);
    }

    public boolean verifyEmail(String token) {
        Optional<User> userOpt = userRepository.findByVerificationToken(token);
        if (userOpt.isPresent()) {
            User user = userOpt.get();
            user.setEnabled(true);
            user.setVerificationToken(null);
            userRepository.save(user);
            return true;
        }
        return false;
    }

    public void requestPasswordReset(String email) {
        userRepository.findById(email).ifPresent(user -> {
            user.setResetToken(UUID.randomUUID().toString());
            userRepository.save(user);
            String link = "http://localhost:8080/reset-password?token=" + user.getResetToken();
            emailService.sendPasswordResetEmail(email, link);
        });
    }

    public boolean resetPassword(String token, String newPassword) {
        Optional<User> userOpt = userRepository.findByResetToken(token);
        if (userOpt.isPresent()) {
            User user = userOpt.get();
            user.setPassword(passwordEncoder.encode(newPassword));
            user.setResetToken(null);
            userRepository.save(user);
            return true;
        }
        return false;
    }

    @Transactional
    public void updatePlan(String username, PlanType newPlan) {
        User user = getOrCreateUser(username);
        user.setPlanType(newPlan);
        userRepository.save(user);
    }

    public void saveUser(User user) {
        userRepository.save(user);
    }
}
