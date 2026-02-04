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
@lombok.extern.slf4j.Slf4j
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;

    public User getOrCreateUser(String username) {
        return userRepository.findById(username)
                .orElseGet(() -> {
                    User newUser = new User(username);
                    // OAuth users are pre-verified usually, or handle accordingly.
                    // Assuming Google OAuth users are verified.
                    // Send welcome email for new OAuth users
                    emailService.sendWelcomeEmail(username);
                    return userRepository.save(newUser);
                });
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

    public void registerUser(String email, String password, String name, String baseUrl) {
        if (userRepository.existsById(email)) {
            throw new IllegalArgumentException("User already exists");
        }
        User user = new User(email);
        user.setPassword(passwordEncoder.encode(password));
        user.setFullName(name);
        user.setEnabled(false); // Email verification required
        user.setVerificationToken(UUID.randomUUID().toString());
        user.setPlanType(PlanType.FREE); // Default to Free plan
        user.setLastVerificationSentAt(java.time.LocalDateTime.now());
        userRepository.save(user);

        String link = baseUrl + "/verify-email?token=" + user.getVerificationToken();
        emailService.sendVerificationEmail(email, link);
    }

    public void resendVerification(String email, String baseUrl) {
        User user = userRepository.findById(email)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        if (user.isEnabled()) {
             throw new IllegalStateException("User already verified.");
        }

        if (user.getLastVerificationSentAt() != null &&
            user.getLastVerificationSentAt().plusDays(1).isAfter(java.time.LocalDateTime.now())) {
            throw new IllegalStateException("Please wait 24 hours before resending verification.");
        }

        user.setLastVerificationSentAt(java.time.LocalDateTime.now());
        user.setVerificationToken(UUID.randomUUID().toString()); // Rotate token
        userRepository.save(user);

        String link = baseUrl + "/verify-email?token=" + user.getVerificationToken();
        emailService.sendVerificationEmail(email, link);
    }

    public boolean verifyEmail(String token) {
        Optional<User> userOpt = userRepository.findByVerificationToken(token);
        if (userOpt.isPresent()) {
            User user = userOpt.get();
            boolean wasEnabled = user.isEnabled();
            user.setEnabled(true);
            user.setVerificationToken(null);
            userRepository.save(user);

            if (!wasEnabled) {
                emailService.sendWelcomeEmail(user.getUsername());
            }
            return true;
        }
        return false;
    }

    public void requestPasswordReset(String email, String baseUrl) {
        userRepository.findById(email).ifPresent(user -> {
            user.setResetToken(UUID.randomUUID().toString());
            userRepository.save(user);
            String link = baseUrl + "/reset-password?token=" + user.getResetToken();
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
        log.info("Updating plan for user {} to {}", username, newPlan);
        User user = getOrCreateUser(username);
        user.setPlanType(newPlan);

        if (newPlan == PlanType.ESSENTIALS) {
            user.setPlanExpiration(java.time.LocalDateTime.now().plusDays(30));
        } else if (newPlan == PlanType.FREE) {
            user.setPlanExpiration(null);
        }

        userRepository.save(user);
        log.info("Plan updated saved to DB for {}", username);
    }

    public void saveUser(User user) {
        userRepository.save(user);
    }

    @Transactional
    public void addUnpaidBalance(String username, double amount) {
        User user = getOrCreateUser(username);
        user.setUnpaidBalance(user.getUnpaidBalance() + amount);
        userRepository.save(user);
    }

    @Transactional
    public void clearUnpaidBalance(String username, double amount) {
        User user = getOrCreateUser(username);
        double current = user.getUnpaidBalance();
        // Allow reducing balance below zero (credit)? No, user wants to clear balance.
        // But if they pay more, they might have negative unpaid balance (which implies credit).
        user.setUnpaidBalance(current - amount);
        userRepository.save(user);
    }

    public boolean checkCreditLimit(String username) {
        User user = getOrCreateUser(username);
        // If unpaid balance is greater than or equal to credit limit, return false.
        // E.g. limit 50, unpaid 50 -> false (blocked).
        return user.getUnpaidBalance() < user.getCreditLimit();
    }
}
