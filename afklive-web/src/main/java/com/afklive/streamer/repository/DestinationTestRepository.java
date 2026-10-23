package com.afklive.streamer.repository;

import com.afklive.streamer.model.DestinationTest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

public interface DestinationTestRepository extends JpaRepository<DestinationTest, Long> {

    Optional<DestinationTest> findByIdAndUserId(Long id, Long userId);

    long countByUserIdAndCreatedAtAfter(Long userId, ZonedDateTime after);

    boolean existsByUserIdAndStatusIn(Long userId, List<DestinationTest.Status> statuses);

    @Query("SELECT t.id FROM DestinationTest t WHERE t.status = 'PENDING' ORDER BY t.id")
    List<Long> findPendingIds();

    /** A worker claims a pending test; 1 means it's this worker's to run. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE DestinationTest t SET t.status = 'RUNNING', t.workerId = :worker, t.startedAt = :now WHERE t.id = :id AND t.status = 'PENDING'")
    int claim(@Param("id") Long id, @Param("worker") String worker, @Param("now") ZonedDateTime now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE DestinationTest t SET t.status = :status, t.message = :message, t.finishedAt = :now WHERE t.id = :id AND t.status = 'RUNNING'")
    int finish(@Param("id") Long id, @Param("status") DestinationTest.Status status, @Param("message") String message,
               @Param("now") ZonedDateTime now);

    /** Tests no worker picked up, or whose worker died: they fail with an explanation. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE DestinationTest t SET t.status = 'FAILED', t.message = :message, t.finishedAt = :now "
            + "WHERE (t.status = 'PENDING' AND t.createdAt < :pendingBefore) OR (t.status = 'RUNNING' AND t.startedAt < :runningBefore)")
    int expire(@Param("message") String message, @Param("pendingBefore") ZonedDateTime pendingBefore,
               @Param("runningBefore") ZonedDateTime runningBefore, @Param("now") ZonedDateTime now);
}
