package com.afklive.streamer.repository;

import com.afklive.streamer.model.ScheduledVideo;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ScheduledVideoRepository extends JpaRepository<ScheduledVideo, Long> {

    @Query("SELECT v FROM ScheduledVideo v LEFT JOIN FETCH v.socialChannel LEFT JOIN FETCH v.user WHERE v.user.email = :email")
    List<ScheduledVideo> findByUserEmail(String email);

    @Query(value = "SELECT v FROM ScheduledVideo v LEFT JOIN FETCH v.socialChannel LEFT JOIN FETCH v.user WHERE v.user.email = :email",
           countQuery = "SELECT COUNT(v) FROM ScheduledVideo v WHERE v.user.email = :email")
    Page<ScheduledVideo> findByUserEmail(String email, Pageable pageable);

    @Query(value = "SELECT v FROM ScheduledVideo v LEFT JOIN FETCH v.socialChannel LEFT JOIN FETCH v.user WHERE v.user.email = :email AND v.status = :status",
           countQuery = "SELECT COUNT(v) FROM ScheduledVideo v WHERE v.user.email = :email AND v.status = :status")
    Page<ScheduledVideo> findByUserEmailAndStatus(String email, ScheduledVideo.VideoStatus status, Pageable pageable);

    @Query("SELECT v FROM ScheduledVideo v LEFT JOIN FETCH v.socialChannel LEFT JOIN FETCH v.user WHERE v.status = :status AND v.scheduledTime <= :time")
    List<ScheduledVideo> findByStatusAndScheduledTimeLessThanEqual(ScheduledVideo.VideoStatus status, ZonedDateTime time);

    Optional<ScheduledVideo> findByUserEmailAndTitle(String email, String filename);
    Optional<ScheduledVideo> findFirstByUserEmailAndS3Key(String email, String s3Key);
}
