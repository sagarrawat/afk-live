package com.afklive.streamer.repository;

import com.afklive.streamer.model.ScheduledVideo;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ScheduledVideoRepository extends JpaRepository<ScheduledVideo, Long> {
    List<ScheduledVideo> findByUserEmail(String email);
    Page<ScheduledVideo> findByUserEmail(String email, Pageable pageable);
    Page<ScheduledVideo> findByUserEmailAndStatus(String email, ScheduledVideo.VideoStatus status, Pageable pageable);
    List<ScheduledVideo> findByStatusAndScheduledTimeLessThanEqual(ScheduledVideo.VideoStatus status, ZonedDateTime time);
    Optional<ScheduledVideo> findByUserEmailAndTitle(String email, String filename);
    Optional<ScheduledVideo> findFirstByUserEmailAndS3Key(String email, String s3Key);
}
