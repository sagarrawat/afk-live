package com.afklive.streamer.repository;

import com.afklive.streamer.model.StreamSchedule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StreamScheduleRepository extends JpaRepository<StreamSchedule, Long> {

    List<StreamSchedule> findAllByStreamIdOrderByIdAsc(Long streamId);

    List<StreamSchedule> findAllByEnabledTrue();

    /** Claims one occurrence: only one web instance (and only once) starts it. */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query("UPDATE StreamSchedule s SET s.lastStartedOn = :date WHERE s.id = :id AND s.enabled = true AND (s.lastStartedOn IS NULL OR s.lastStartedOn <> :date)")
    int claimOccurrence(@org.springframework.data.repository.query.Param("id") Long id,
                        @org.springframework.data.repository.query.Param("date") java.time.LocalDate date);

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query("UPDATE StreamSchedule s SET s.lastError = :error WHERE s.id = :id")
    int recordError(@org.springframework.data.repository.query.Param("id") Long id,
                    @org.springframework.data.repository.query.Param("error") String error);

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query("UPDATE StreamSchedule s SET s.enabled = false WHERE s.id = :id")
    int disable(@org.springframework.data.repository.query.Param("id") Long id);
}
