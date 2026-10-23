package com.afklive.streamer.repository;

import com.afklive.streamer.model.StreamItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface StreamItemRepository extends JpaRepository<StreamItem, Long> {

    List<StreamItem> findAllByStreamIdAndKindOrderByPositionAsc(Long streamId, StreamItem.Kind kind);

    List<StreamItem> findAllByStreamIdOrderByPositionAsc(Long streamId);

    /** Streams that use a library file, for "in use" checks before deleting it. */
    @Query("SELECT DISTINCT i.streamId FROM StreamItem i WHERE i.media.id = :mediaId")
    List<Long> findStreamIdsUsingMedia(@Param("mediaId") Long mediaId);

    /** Takes a deleted library file out of every playlist (ML-6). */
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM StreamItem i WHERE i.media.id = :mediaId")
    int deleteByMediaId(@Param("mediaId") Long mediaId);
}
