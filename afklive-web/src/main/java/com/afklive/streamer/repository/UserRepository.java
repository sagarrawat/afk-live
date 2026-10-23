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

    /** Reserves storage only if it fits under the limit; 0 means it doesn't fit (ML-5). */
    @org.springframework.transaction.annotation.Transactional
    @Modifying(flushAutomatically = true)
    @Query(value = "UPDATE users SET used_storage_bytes = COALESCE(used_storage_bytes, 0) + :bytes "
            + "WHERE id = :id AND COALESCE(used_storage_bytes, 0) + :bytes <= :limit", nativeQuery = true)
    int reserveStorage(@Param("id") Long id, @Param("bytes") long bytes, @Param("limit") long limit);

    /** Adds (or with a negative value, releases) storage without a read-modify-write race. */
    @org.springframework.transaction.annotation.Transactional
    @Modifying(flushAutomatically = true)
    @Query(value = "UPDATE users SET used_storage_bytes = GREATEST(0, COALESCE(used_storage_bytes, 0) + :bytes) WHERE id = :id",
            nativeQuery = true)
    int addStorage(@Param("id") Long id, @Param("bytes") long bytes);

    @Query(value = "SELECT COALESCE(used_storage_bytes, 0) FROM users WHERE id = :id", nativeQuery = true)
    long usedStorage(@Param("id") Long id);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id = :id")
    Optional<User> lockById(@Param("id") Long id);

    /** AD-3: find by email or name (lower-case needle). */
    @Query("SELECT u FROM User u WHERE LOWER(u.email) LIKE CONCAT('%', :q, '%') OR LOWER(COALESCE(u.fullName, '')) LIKE CONCAT('%', :q, '%')")
    org.springframework.data.domain.Page<User> search(@Param("q") String q, org.springframework.data.domain.Pageable pageable);
}
