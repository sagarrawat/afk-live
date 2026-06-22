package com.afklive.streamer.repository;

import com.afklive.streamer.model.ScheduledStream;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.ZonedDateTime;
import java.util.List;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

@Repository
public interface ScheduledStreamRepository extends JpaRepository<ScheduledStream, Long> {
    List<ScheduledStream> findByUserEmail(String email);
    List<ScheduledStream> findByStatusAndScheduledTimeLessThanEqual(ScheduledStream.StreamStatus status, ZonedDateTime time);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM ScheduledStream s WHERE s.id = :id")
    Optional<ScheduledStream> findByIdForUpdate(@Param("id") Long id);
}
