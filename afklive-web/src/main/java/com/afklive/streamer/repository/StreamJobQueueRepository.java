package com.afklive.streamer.repository;

import com.afklive.streamer.model.StreamJobQueue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StreamJobQueueRepository extends JpaRepository<StreamJobQueue, Long> {
    List<StreamJobQueue> findByStatus(StreamJobQueue.QueueStatus status);
}
