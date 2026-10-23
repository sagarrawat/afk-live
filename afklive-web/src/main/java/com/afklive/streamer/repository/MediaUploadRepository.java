package com.afklive.streamer.repository;

import com.afklive.streamer.model.MediaUpload;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

public interface MediaUploadRepository extends JpaRepository<MediaUpload, String> {

    Optional<MediaUpload> findByIdAndUserId(String id, Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM MediaUpload u WHERE u.id = :id AND u.userId = :userId")
    Optional<MediaUpload> lockByIdAndUserId(@Param("id") String id, @Param("userId") Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM MediaUpload u WHERE u.id = :id")
    Optional<MediaUpload> lockById(@Param("id") String id);

    List<MediaUpload> findAllByUserIdAndStatusOrderByCreatedAtDesc(Long userId, MediaUpload.Status status);

    @Query("SELECT u.id FROM MediaUpload u WHERE u.status = 'UPLOADING' AND u.expiresAt < :now")
    List<String> findExpired(@Param("now") ZonedDateTime now);

    @org.springframework.transaction.annotation.Transactional
    @Modifying(flushAutomatically = true)
    @Query("UPDATE MediaUpload u SET u.expiresAt = :until WHERE u.id = :id AND u.status = 'UPLOADING'")
    int extend(@Param("id") String id, @Param("until") ZonedDateTime until);
}
