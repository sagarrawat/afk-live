package com.afklive.streamer.repository;

import com.afklive.streamer.model.ScheduledStream;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ScheduledStreamRepository extends JpaRepository<ScheduledStream, Long> {
    List<ScheduledStream> findByUsername(String username);
    List<ScheduledStream> findByStatusAndScheduledTimeLessThanEqual(ScheduledStream.StreamStatus status, LocalDateTime time);
}
