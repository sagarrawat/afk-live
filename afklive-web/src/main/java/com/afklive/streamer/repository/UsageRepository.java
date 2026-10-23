package com.afklive.streamer.repository;

import com.afklive.streamer.model.User;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** Streaming seconds per user per month (BI-1 free hours, BI-6 usage). */
public interface UsageRepository extends Repository<User, Long> {

    @Modifying
    @Transactional
    @Query(value = "INSERT INTO usage_months(user_id, month, stream_seconds) VALUES (:userId, :month, :seconds) "
            + "ON CONFLICT (user_id, month) DO UPDATE SET stream_seconds = usage_months.stream_seconds + :seconds", nativeQuery = true)
    int add(@Param("userId") Long userId, @Param("month") String month, @Param("seconds") long seconds);

    @Query(value = "SELECT COALESCE((SELECT stream_seconds FROM usage_months WHERE user_id = :userId AND month = :month), 0)",
            nativeQuery = true)
    long secondsIn(@Param("userId") Long userId, @Param("month") String month);
}
