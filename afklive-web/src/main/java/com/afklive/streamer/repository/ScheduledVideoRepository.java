package com.afklive.streamer.repository;

import com.afklive.streamer.model.ScheduledVideo;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ScheduledVideoRepository extends JpaRepository<ScheduledVideo, Long> {

    @Query("SELECT v FROM ScheduledVideo v LEFT JOIN FETCH v.socialChannel LEFT JOIN FETCH v.user LEFT JOIN FETCH v.mediaAsset LEFT JOIN FETCH v.tagEntities WHERE v.user.email = :email")
    List<ScheduledVideo> findByUserEmail(String email);

    @Query(value = "SELECT v FROM ScheduledVideo v LEFT JOIN FETCH v.socialChannel LEFT JOIN FETCH v.user LEFT JOIN FETCH v.mediaAsset LEFT JOIN FETCH v.tagEntities WHERE v.user.email = :email",
           countQuery = "SELECT COUNT(v) FROM ScheduledVideo v WHERE v.user.email = :email")
    Page<ScheduledVideo> findByUserEmail(String email, Pageable pageable);

    @Query(value = "SELECT v FROM ScheduledVideo v LEFT JOIN FETCH v.socialChannel LEFT JOIN FETCH v.user LEFT JOIN FETCH v.mediaAsset LEFT JOIN FETCH v.tagEntities WHERE v.user.email = :email AND v.status = :status",
           countQuery = "SELECT COUNT(v) FROM ScheduledVideo v WHERE v.user.email = :email AND v.status = :status")
    Page<ScheduledVideo> findByUserEmailAndStatus(String email, ScheduledVideo.VideoStatus status, Pageable pageable);

    @Query("SELECT v FROM ScheduledVideo v LEFT JOIN FETCH v.socialChannel LEFT JOIN FETCH v.user LEFT JOIN FETCH v.mediaAsset LEFT JOIN FETCH v.tagEntities WHERE v.status = :status AND v.scheduledTime <= :time")
    List<ScheduledVideo> findByStatusAndScheduledTimeLessThanEqual(ScheduledVideo.VideoStatus status, ZonedDateTime time);

    Optional<ScheduledVideo> findByUserEmailAndTitle(String email, String filename);

    @Query("SELECT v FROM ScheduledVideo v JOIN v.mediaAsset ma WHERE v.user.email = :email AND ma.s3Key = :s3Key")
    Optional<ScheduledVideo> findFirstByUserEmailAndS3Key(String email, String s3Key);

    List<ScheduledVideo> findAllByMediaAssetId(Long mediaAssetId);

    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("UPDATE ScheduledVideo v SET v.thumbnailAsset = NULL WHERE v.thumbnailAsset.id = :id")
    int clearThumbnail(@org.springframework.data.repository.query.Param("id") Long id);

    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("UPDATE ScheduledVideo v SET v.mediaAsset = NULL WHERE v.mediaAsset.id = :id")
    int clearMedia(@org.springframework.data.repository.query.Param("id") Long id);
}
