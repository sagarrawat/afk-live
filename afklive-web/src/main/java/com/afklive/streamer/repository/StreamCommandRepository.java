package com.afklive.streamer.repository;

import com.afklive.streamer.model.StreamCommand;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;

public interface StreamCommandRepository extends JpaRepository<StreamCommand, Long> {

    /** New commands for the jobs a worker runs, oldest first. */
    @Query("SELECT c FROM StreamCommand c WHERE c.jobId IN :jobIds AND c.id > :after ORDER BY c.id")
    List<StreamCommand> findNew(@Param("jobIds") Collection<Long> jobIds, @Param("after") long after);

    @Query("SELECT COALESCE(MAX(c.id), 0) FROM StreamCommand c")
    long maxId();

    @Modifying
    @Transactional
    @Query("DELETE FROM StreamCommand c WHERE c.createdAt < :before")
    int deleteOlderThan(@Param("before") ZonedDateTime before);
}
