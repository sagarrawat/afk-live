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

    Optional<LiveStream> findByIngestKeyHash(String hash);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE LiveStream s SET s.ingestLastPublishAt = :at WHERE s.id = :id")
    int markIngestPublish(@Param("id") Long id, @Param("at") java.time.ZonedDateTime at);

    /** Locks the stream row: going live twice at once must create one run, not two. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM LiveStream s WHERE s.id = :id")
    Optional<LiveStream> lockById(@Param("id") Long id);

    /** Saved by the worker every few seconds; a targeted update so it never overwrites the creator's edits. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE LiveStream s SET s.resumePosition = :position WHERE s.id = :id AND EXISTS "
            + "(SELECT 1 FROM StreamJob j WHERE j.id = :jobId AND j.workerId = :worker AND j.isLive = true)")
    int saveResumePosition(@Param("id") Long id, @Param("position") String position,
                           @Param("jobId") Long jobId, @Param("worker") String worker);

    /** Test support: set a saved position without a worker. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE LiveStream s SET s.resumePosition = :position WHERE s.id = :id")
    int saveResumePositionForTest(@Param("id") Long id, @Param("position") String position);

    /** GR-9 weekly check: streams live now or at some point since {@code since}. */
    @Query("SELECT s.id FROM LiveStream s WHERE EXISTS (SELECT 1 FROM StreamJob j WHERE j.streamId = s.id "
            + "AND (j.isLive = true OR j.endTime > :since)) ORDER BY s.id")
    List<Long> findIdsLiveSince(@Param("since") java.time.ZonedDateTime since);

    /** GR-9: claims this week's check for one stream; 1 for the instance that should send it, 0 for the rest. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = "UPDATE streams SET check_weekly_at = :now WHERE id = :id AND (check_weekly_at IS NULL OR check_weekly_at < :before)",
            nativeQuery = true)
    int claimWeeklyCheck(@Param("id") Long id, @Param("now") java.time.ZonedDateTime now, @Param("before") java.time.ZonedDateTime before);

    /** GR-9 fix: stops automatic live-chat replies on the user's runs (a targeted update; nothing else on a run changes). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE StreamJob j SET j.autoReplyEnabled = false WHERE j.user.id = :userId AND j.autoReplyEnabled = true")
    int stopLiveAutoReplies(@Param("userId") Long userId);

    /** Starts the next run from the top (the playlist finished). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE LiveStream s SET s.resumePosition = NULL WHERE s.id = :id")
    int clearResumePosition(@Param("id") Long id);
}
