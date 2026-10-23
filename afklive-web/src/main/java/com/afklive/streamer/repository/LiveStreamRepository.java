package com.afklive.streamer.repository;

import com.afklive.streamer.model.LiveStream;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface LiveStreamRepository extends JpaRepository<LiveStream, Long> {

    List<LiveStream> findAllByUserEmailOrderByCreatedAtAsc(String email);

    Optional<LiveStream> findByIdAndUserEmail(Long id, String email);

    long countByUserEmail(String email);

    /** Locks the stream row: going live twice at once must create one run, not two. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM LiveStream s WHERE s.id = :id")
    Optional<LiveStream> lockById(@Param("id") Long id);

    /** Saved by the worker every few seconds; a targeted update so it never overwrites the creator's edits. */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("UPDATE LiveStream s SET s.resumePosition = :position WHERE s.id = :id")
    int saveResumePosition(@Param("id") Long id, @Param("position") String position);
}
