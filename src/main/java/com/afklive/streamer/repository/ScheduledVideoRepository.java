package com.afklive.streamer.repository;

import com.afklive.streamer.model.ScheduledVideo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ScheduledVideoRepository extends JpaRepository<ScheduledVideo, Long> {
    List<ScheduledVideo> findByUsername(String username);
    List<ScheduledVideo> findByStatusAndScheduledTimeLessThanEqual(ScheduledVideo.VideoStatus status, LocalDateTime time);
}
