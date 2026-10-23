package com.afklive.streamer.repository;

import com.afklive.streamer.model.StreamTarget;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StreamTargetRepository extends JpaRepository<StreamTarget, StreamTarget.Key> {

    List<StreamTarget> findAllByStreamId(Long streamId);
}
