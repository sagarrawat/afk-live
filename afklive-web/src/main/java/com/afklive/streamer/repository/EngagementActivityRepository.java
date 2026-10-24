package com.afklive.streamer.repository;

import com.afklive.streamer.model.EngagementActivity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface EngagementActivityRepository extends JpaRepository<EngagementActivity, Long> {
    List<EngagementActivity> findByUserEmailOrderByTimestampDesc(String email);

    /** GR-9: replies AFK Live posted for this user since {@code since}, as [text, times], most repeated first. */
    @org.springframework.data.jpa.repository.Query("SELECT a.content, COUNT(a) FROM EngagementActivity a WHERE a.user.id = :userId "
            + "AND a.actionType IN ('REPLY', 'LIVE_REPLY') AND a.content IS NOT NULL AND a.timestamp > :since "
            + "GROUP BY a.content HAVING COUNT(a) >= :min ORDER BY COUNT(a) DESC")
    List<Object[]> findRepeatedReplies(@org.springframework.data.repository.query.Param("userId") Long userId,
                                       @org.springframework.data.repository.query.Param("since") java.time.LocalDateTime since,
                                       @org.springframework.data.repository.query.Param("min") long min,
                                       org.springframework.data.domain.Pageable page);
}
