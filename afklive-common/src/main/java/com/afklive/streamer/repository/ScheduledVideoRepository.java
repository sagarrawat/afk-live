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
    List<ScheduledVideo> findByUsername(String username);
    Page<ScheduledVideo> findByUsername(String username, Pageable pageable);
    Page<ScheduledVideo> findByUsernameAndStatus(String username, ScheduledVideo.VideoStatus status, Pageable pageable);
    List<ScheduledVideo> findByStatusAndScheduledTimeLessThanEqual(ScheduledVideo.VideoStatus status, ZonedDateTime time);
    Optional<ScheduledVideo> findByUsernameAndTitle(String username, String filename);
    Optional<ScheduledVideo> findFirstByUsernameAndS3Key(String username, String s3Key);
}
