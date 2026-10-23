package com.afklive.streamer.repository;

import com.afklive.streamer.model.StatusIncident;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/** RE-7: status page incidents. */
public interface StatusIncidentRepository extends JpaRepository<StatusIncident, Long> {

    /** Open incidents, and resolved ones from {@code since} on (the page's history window). */
    @Query("SELECT i FROM StatusIncident i WHERE i.resolvedAt IS NULL OR i.createdAt >= :since ORDER BY i.createdAt DESC")
    List<StatusIncident> findRecent(@Param("since") ZonedDateTime since);

    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM StatusIncident i WHERE i.id = :id")
    Optional<StatusIncident> lockById(@Param("id") Long id);
}
