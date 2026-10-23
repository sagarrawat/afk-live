package com.afklive.streamer.repository;

import com.afklive.streamer.model.RecoveryCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.ZonedDateTime;

public interface RecoveryCodeRepository extends JpaRepository<RecoveryCode, Long> {

    long countByUserIdAndUsedAtIsNull(Long userId);

    /** Uses a code: 1 if it was there and unused (so two simultaneous sign-ins can't both use it). */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE RecoveryCode c SET c.usedAt = :now WHERE c.userId = :userId AND c.codeHash = :hash AND c.usedAt IS NULL")
    int use(@Param("userId") Long userId, @Param("hash") String hash, @Param("now") ZonedDateTime now);

    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM RecoveryCode c WHERE c.userId = :userId")
    int deleteAllForUser(@Param("userId") Long userId);
}
