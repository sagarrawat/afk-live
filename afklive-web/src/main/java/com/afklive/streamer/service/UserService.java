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
        long limit = planService.getPlanConfig(user.getPlanType()).getMaxStorageBytes();
        if (userRepository.usedStorage(user.getId()) + fileSize > limit) {
            throw new IllegalStateException("Storage quota exceeded. Limit: " + (limit / 1024 / 1024) + "MB.");
        }
    }

    /** Bytes the user can still store under their plan (never negative). */
    public long remainingStorageBytes(String email) {
        User user = getOrCreateUser(email);
        long limit = planService.getPlanConfig(user.getPlanType()).getMaxStorageBytes();
        return Math.max(0, limit - userRepository.usedStorage(user.getId()));
    }

    @Transactional
    public void updateStorageUsage(String email, long delta) {
        User user = getOrCreateUser(email);
        userRepository.addStorage(user.getId(), delta); // atomic; never below zero
    }

    public void checkStreamQuota(String email, int currentActiveCount) {
        User user = getOrCreateUser(email);
        int limit = planService.getPlanConfig(user.getPlanType()).getMaxActiveStreams();
        if (currentActiveCount >= limit) {
             throw new IllegalStateException("Active stream limit reached (" + limit + ") for plan " + planService.getPlanConfig(user.getPlanType()).getDisplayName());
        }
    }

    /** What happened after a password sign-up. */
    public enum Registration {
        /** A verification link was emailed. */
        VERIFICATION_SENT,
        /** Email isn't set up, so the account was verified at once (OPS-1); the creator can sign in. */
        READY
    }

    public Registration registerUser(String email, String password, String name, String baseUrl) {
        if (userRepository.findByEmail(email).isPresent()) {
            throw new IllegalArgumentException("User already exists");
        }
        User user = new User(email);
        user.setPassword(passwordEncoder.encode(password));
        user.setFullName(name);
        user.setPlanType(PlanType.FREE); // Default to Free plan
        if (!emailService.isConfigured()) {
            // OPS-1: without email a verification link can never arrive; don't leave the account stuck.
            user.setEnabled(true);
            user.setVerificationToken(null);
            User saved = userRepository.save(user);
            log.warn("Email isn't set up: verified the new password sign-up (user {}) without an email check", saved.getId());
            return Registration.READY;
        }
        user.setEnabled(false); // Email verification required
        user.setVerificationToken(UUID.randomUUID().toString());
        user.setLastVerificationSentAt(java.time.LocalDateTime.now());
        userRepository.save(user);

        String link = baseUrl + "/verify-email?token=" + user.getVerificationToken();
        emailService.sendVerificationEmail(email, link);
        return Registration.VERIFICATION_SENT;
    }

    public void resendVerification(String email, String baseUrl) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        if (user.isEnabled()) {
             throw new IllegalStateException("User already verified.");
        }
        if (!emailService.isConfigured()) {
            throw new IllegalStateException("We can't send email right now. Write to support@afklive.in and we'll verify your account.");
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

    /**
     * Emails a reset link if the account exists. Returns false, and changes nothing, when email isn't
     * set up (OPS-1): the page then says so instead of claiming a link was sent.
     */
    public boolean requestPasswordReset(String email, String baseUrl) {
        if (!emailService.isConfigured()) {
            return false;
        }
        userRepository.findByEmail(email).ifPresent(user -> {
            user.setResetToken(UUID.randomUUID().toString());
            userRepository.save(user);
            String link = baseUrl + "/reset-password?token=" + user.getResetToken();
            emailService.sendPasswordResetEmail(email, link);
        });
        return true;
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
    public void addUnpaidBalance(String email, double amount) {
        // Ensure user exists first (optional but safe)
        if (userRepository.findByEmail(email).isEmpty()) {
            getOrCreateUser(email);
        }
        userRepository.addUnpaidBalance(email, amount);
    }

    @Transactional
    public void clearUnpaidBalance(String email, double amount) {
        // Ensure user exists
        if (userRepository.findByEmail(email).isEmpty()) {
            getOrCreateUser(email);
        }
        userRepository.deductUnpaidBalance(email, amount);
    }

    public boolean checkCreditLimit(String email) {
        User user = getOrCreateUser(email);
        // If unpaid balance is greater than or equal to credit limit, return false.
        // E.g. limit 50, unpaid 50 -> false (blocked).
        return user.getUnpaidBalance() < user.getCreditLimit();
    }
}
