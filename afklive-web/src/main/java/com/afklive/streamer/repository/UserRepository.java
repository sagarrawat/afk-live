package com.afklive.streamer.repository;

import com.afklive.streamer.model.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);

    /** Serializes a user's stream starts, so plan limits hold even for simultaneous starts. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT u FROM User u WHERE u.email = :email")
    Optional<User> lockByEmail(@org.springframework.data.repository.query.Param("email") String email);
    Optional<User> findByVerificationToken(String token);
    Optional<User> findByResetToken(String token);

    // Used in EngagementService for cron
    // Fetches users with settings enabled, eager loading channels and settings
    @Query("SELECT u FROM User u LEFT JOIN FETCH u.channels LEFT JOIN FETCH u.settings s WHERE s.autoReplyEnabled = true")
    Slice<User> findByAutoReplyEnabledTrue(Pageable pageable);

    Page<User> findByEmailContainingIgnoreCaseOrFullNameContainingIgnoreCase(String email, String fullName, Pageable pageable);

    @Modifying
    @Query("UPDATE User u SET u.unpaidBalance = u.unpaidBalance + :amount WHERE u.email = :email")
    void addUnpaidBalance(@Param("email") String email, @Param("amount") double amount);

    @Modifying
    @Query("UPDATE User u SET u.unpaidBalance = u.unpaidBalance - :amount WHERE u.email = :email")
    void deductUnpaidBalance(@Param("email") String email, @Param("amount") double amount);

    @Query("SELECT COALESCE(SUM(u.unpaidBalance), 0) FROM User u")
    Double sumUnpaidBalance();

    @Query("SELECT COALESCE(SUM(u.usedStorageBytes), 0) FROM User u")
    Long sumUsedStorageBytes();
}
