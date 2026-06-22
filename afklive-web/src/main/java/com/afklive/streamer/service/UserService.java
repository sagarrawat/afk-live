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
    private final PlanService planService;

    public User getOrCreateUser(String email) {
        return userRepository.findByEmail(email)
                .orElseGet(() -> {
                    User newUser = new User(email);
                    // OAuth users are pre-verified usually, or handle accordingly.
                    // Send welcome email for new OAuth users
                    try {
                        emailService.sendWelcomeEmail(email);
                    } catch (Exception e) {
                        log.warn("Failed to send welcome email to {}", email);
                    }
                    return userRepository.save(newUser);
                });
    }

    public void checkStorageQuota(String email, long fileSize) {
        User user = getOrCreateUser(email);
        long limit = 50L * 1024 * 1024 * 1024; // 50 GB for all users
        if (user.getUsedStorageBytes() + fileSize > limit) {
            throw new IllegalStateException("Storage quota exceeded. Limit: 50GB.");
        }
    }

    @Transactional
    public void updateStorageUsage(String email, long delta) {
        User user = getOrCreateUser(email);
        long newUsage = user.getUsedStorageBytes() + delta;
        user.setUsedStorageBytes(Math.max(0, newUsage)); // Prevent negative
        userRepository.save(user);
    }

    public void checkStreamQuota(String email, int currentActiveCount) {
        User user = getOrCreateUser(email);
        int limit = planService.getPlanConfig(user.getPlanType()).getMaxActiveStreams();
        if (currentActiveCount >= limit) {
             throw new IllegalStateException("Active stream limit reached (" + limit + ") for plan " + planService.getPlanConfig(user.getPlanType()).getDisplayName());
        }
    }

    public void registerUser(String email, String password, String name, String baseUrl) {
        if (userRepository.findByEmail(email).isPresent()) {
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
        User user = userRepository.findByEmail(email)
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
                emailService.sendWelcomeEmail(user.getEmail());
            }
            return true;
        }
        return false;
    }

    public void requestPasswordReset(String email, String baseUrl) {
        userRepository.findByEmail(email).ifPresent(user -> {
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
    public void updatePlan(String email, PlanType newPlan) {
        log.info("Updating plan for user {} to {}", email, newPlan);
        User user = getOrCreateUser(email);
        user.setPlanType(newPlan);

        if (newPlan == PlanType.ESSENTIALS) {
            user.setPlanExpiration(java.time.LocalDateTime.now().plusDays(30));
        } else if (newPlan == PlanType.FREE) {
            user.setPlanExpiration(null);
        }

        userRepository.save(user);
        log.info("Plan updated saved to DB for {}", email);
    }

    public void saveUser(User user) {
        userRepository.save(user);
    }

    @Transactional
    public void addUnpaidBalance(String email, java.math.BigDecimal amount) {
        // Ensure user exists first (optional but safe)
        if (userRepository.findByEmail(email).isEmpty()) {
            getOrCreateUser(email);
        }
        userRepository.addUnpaidBalance(email, amount);
    }

    @Transactional
    public void clearUnpaidBalance(String email, java.math.BigDecimal amount) {
        // Ensure user exists
        if (userRepository.findByEmail(email).isEmpty()) {
            getOrCreateUser(email);
        }
        userRepository.deductUnpaidBalance(email, amount);
    }

    public boolean checkCreditLimit(String email) {
        User user = getOrCreateUser(email);
        return user.getUnpaidBalance().compareTo(user.getCreditLimit()) < 0;
    }
}
