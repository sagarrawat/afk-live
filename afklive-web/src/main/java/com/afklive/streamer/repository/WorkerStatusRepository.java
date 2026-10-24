package com.afklive.streamer.repository;

import com.afklive.streamer.model.WorkerStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.List;

/** AD-2 / RE-7: workers' self-reports. Each worker writes only its own row. */
public interface WorkerStatusRepository extends JpaRepository<WorkerStatus, String> {

    @Modifying
    @Transactional
    @Query(value = "INSERT INTO stream_workers(worker_id, started_at, heartbeat_at, streams, capacity, cpu_percent, memory_used_bytes, memory_total_bytes, cpus) "
            + "VALUES (:id, :startedAt, :now, :streams, :capacity, :cpu, :memUsed, :memTotal, :cpus) "
            + "ON CONFLICT (worker_id) DO UPDATE SET started_at = :startedAt, heartbeat_at = :now, streams = :streams, capacity = :capacity, "
            + "cpu_percent = :cpu, memory_used_bytes = :memUsed, memory_total_bytes = :memTotal, cpus = :cpus", nativeQuery = true)
    int report(@Param("id") String workerId, @Param("startedAt") ZonedDateTime startedAt, @Param("now") ZonedDateTime now,
               @Param("streams") int streams, @Param("capacity") int capacity, @Param("cpu") Double cpuPercent,
               @Param("memUsed") Long memoryUsedBytes, @Param("memTotal") Long memoryTotalBytes, @Param("cpus") Integer cpus);

    default int report(String workerId, ZonedDateTime startedAt, ZonedDateTime now, int streams, int capacity, Double cpuPercent,
                       Long memoryUsedBytes, Long memoryTotalBytes) {
        return report(workerId, startedAt, now, streams, capacity, cpuPercent, memoryUsedBytes, memoryTotalBytes, null);
    }

    /**
     * OPS-3: marks a worker draining (or not, with null). Creates its row if it hasn't reported yet, so the
     * mark is never lost; the report never touches this column.
     */
    @Modifying
    @Transactional
    @Query(value = "INSERT INTO stream_workers(worker_id, started_at, heartbeat_at, streams, capacity, draining_since) "
            + "VALUES (:id, :now, :now, 0, 0, :since) "
            + "ON CONFLICT (worker_id) DO UPDATE SET draining_since = :since", nativeQuery = true)
    int setDraining(@Param("id") String workerId, @Param("now") ZonedDateTime now, @Param("since") ZonedDateTime drainingSince);

    /** OPS-3: since when this worker is draining; empty when it isn't (or has no row). */
    @Query("SELECT w.drainingSince FROM WorkerStatus w WHERE w.workerId = :id AND w.drainingSince IS NOT NULL")
    java.util.Optional<ZonedDateTime> findDrainingSince(@Param("id") String workerId);

    List<WorkerStatus> findAllByHeartbeatAtAfterOrderByWorkerIdAsc(ZonedDateTime since);

    long countByHeartbeatAtAfter(ZonedDateTime since);

    @Modifying
    @Transactional
    @Query("DELETE FROM WorkerStatus w WHERE w.workerId = :id")
    int remove(@Param("id") String workerId);
}
