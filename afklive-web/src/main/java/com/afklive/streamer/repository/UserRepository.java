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
    Optional<User> findByVerificationToken(String token);
    Optional<User> findByResetToken(String token);

    // Used in EngagementService for cron, might need channels?
    // EngagementService calls youTubeService which needs channels.
    @Query("SELECT u FROM User u LEFT JOIN FETCH u.channels WHERE u.autoReplyEnabled = true")
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
