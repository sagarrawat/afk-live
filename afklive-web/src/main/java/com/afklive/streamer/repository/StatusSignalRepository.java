package com.afklive.streamer.repository;

import com.afklive.streamer.model.StatusSignal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;

/** RE-7: when platform components were last seen working, shared by every web instance. */
public interface StatusSignalRepository extends JpaRepository<StatusSignal, String> {

    /** Records a sighting; a row newer than {@code ifOlderThan} is left alone, so busy hooks write rarely. */
    @Modifying
    @Transactional
    @Query(value = "INSERT INTO status_signals(name, seen_at) VALUES (:name, :at) "
            + "ON CONFLICT (name) DO UPDATE SET seen_at = :at WHERE status_signals.seen_at < :ifOlderThan", nativeQuery = true)
    int seen(@Param("name") String name, @Param("at") ZonedDateTime at, @Param("ifOlderThan") ZonedDateTime ifOlderThan);
}
