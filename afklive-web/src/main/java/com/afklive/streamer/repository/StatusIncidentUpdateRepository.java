package com.afklive.streamer.repository;

import com.afklive.streamer.model.StatusIncidentUpdate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

/** RE-7: the timestamped updates on status incidents. */
public interface StatusIncidentUpdateRepository extends JpaRepository<StatusIncidentUpdate, Long> {

    List<StatusIncidentUpdate> findAllByIncidentIdInOrderByCreatedAtDescIdDesc(Collection<Long> incidentIds);
}
