package com.afklive.streamer.repository;

import com.afklive.streamer.model.EmailLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

public interface EmailLogRepository extends JpaRepository<EmailLog, Long> {

    List<EmailLog> findTop50ByUserIdOrderByCreatedAtDesc(Long userId);

    Optional<EmailLog> findFirstByOkTrueOrderByCreatedAtDesc();

    Optional<EmailLog> findFirstByOkFalseOrderByCreatedAtDesc();

    long countByOkAndCreatedAtAfter(boolean ok, ZonedDateTime since);

    @Modifying
    @Transactional
    @Query("DELETE FROM EmailLog e WHERE e.createdAt < :before")
    int deleteOlderThan(@Param("before") ZonedDateTime before);
}
