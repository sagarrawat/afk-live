package com.afklive.streamer.repository;

import com.afklive.streamer.model.MediaAsset;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface MediaAssetRepository extends JpaRepository<MediaAsset, Long>,
        org.springframework.data.jpa.repository.JpaSpecificationExecutor<MediaAsset> {
    List<MediaAsset> findByUserEmail(String email);
    Page<MediaAsset> findByUserEmail(String email, Pageable pageable);

    // For legacy lookups by filename (title)
    Optional<MediaAsset> findByUserEmailAndTitle(String email, String title);
    Optional<MediaAsset> findFirstByUserEmailAndS3Key(String email, String s3Key);

    Optional<MediaAsset> findByIdAndUserEmail(Long id, String email);

    List<MediaAsset> findAllByIdInAndUserEmail(java.util.Collection<Long> ids, String email);

    @org.springframework.data.jpa.repository.Query("SELECT a.id FROM MediaAsset a WHERE a.status = com.afklive.streamer.model.MediaStatus.PROCESSING "
            + "AND (a.processingClaimedAt IS NULL OR a.processingClaimedAt < :stale) ORDER BY a.id")
    List<Long> findProcessable(@org.springframework.data.repository.query.Param("stale") java.time.ZonedDateTime stale);

    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true, clearAutomatically = true)
    @org.springframework.data.jpa.repository.Query("UPDATE MediaAsset a SET a.processingClaimedAt = :now WHERE a.id = :id "
            + "AND a.status = com.afklive.streamer.model.MediaStatus.PROCESSING AND (a.processingClaimedAt IS NULL OR a.processingClaimedAt < :stale)")
    int claimProcessing(@org.springframework.data.repository.query.Param("id") Long id,
                        @org.springframework.data.repository.query.Param("now") java.time.ZonedDateTime now,
                        @org.springframework.data.repository.query.Param("stale") java.time.ZonedDateTime stale);

    /** Records the result, only for the claim that's still current (a takeover wins). */
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true, clearAutomatically = true)
    @org.springframework.data.jpa.repository.Query("UPDATE MediaAsset a SET a.status = :status, a.statusReason = :reason, "
            + "a.durationSeconds = :duration, a.width = :width, a.height = :height, a.videoCodec = :codec, a.hasAudio = :hasAudio, "
            + "a.fps = :fps, a.thumbnailKey = :thumb, a.processingClaimedAt = NULL "
            + "WHERE a.id = :id AND a.processingClaimedAt = :claimedAt AND a.status = com.afklive.streamer.model.MediaStatus.PROCESSING")
    int finishProcessing(@org.springframework.data.repository.query.Param("id") Long id,
                         @org.springframework.data.repository.query.Param("claimedAt") java.time.ZonedDateTime claimedAt,
                         @org.springframework.data.repository.query.Param("status") com.afklive.streamer.model.MediaStatus status,
                         @org.springframework.data.repository.query.Param("reason") String reason,
                         @org.springframework.data.repository.query.Param("duration") Double duration,
                         @org.springframework.data.repository.query.Param("width") Integer width,
                         @org.springframework.data.repository.query.Param("height") Integer height,
                         @org.springframework.data.repository.query.Param("codec") String codec,
                         @org.springframework.data.repository.query.Param("hasAudio") Boolean hasAudio,
                         @org.springframework.data.repository.query.Param("fps") Double fps,
                         @org.springframework.data.repository.query.Param("thumb") String thumb);

    List<MediaAsset> findAllByIdIn(java.util.Collection<Long> ids);

    boolean existsByUserEmailAndTitle(String email, String title);

    /** AU-5: ready files with sound whose loudness hasn't been measured yet (older uploads), oldest first. */
    @org.springframework.data.jpa.repository.Query("SELECT a.id FROM MediaAsset a WHERE a.status = com.afklive.streamer.model.MediaStatus.READY "
            + "AND a.loudnessChecked = false AND (a.contentType = com.afklive.streamer.model.MediaAsset.ContentType.AUDIO "
            + "OR (a.contentType = com.afklive.streamer.model.MediaAsset.ContentType.VIDEO AND a.hasAudio = true)) ORDER BY a.id")
    List<Long> findNeedingLoudness(org.springframework.data.domain.Pageable page);

    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true, clearAutomatically = true)
    @org.springframework.data.jpa.repository.Query("UPDATE MediaAsset a SET a.loudnessLufs = :lufs, a.loudnessChecked = true WHERE a.id = :id")
    int setLoudness(@org.springframework.data.repository.query.Param("id") Long id,
                    @org.springframework.data.repository.query.Param("lufs") Double lufs);

    // GR-9: whether a video is one still picture. Native, so the entity (saved whole elsewhere) never overwrites it.

    @org.springframework.data.jpa.repository.Query(value = "SELECT id FROM media_assets WHERE status = 'READY' AND content_type = 'VIDEO' "
            + "AND still_checked = false ORDER BY EXISTS (SELECT 1 FROM stream_items i WHERE i.media_asset_id = media_assets.id) DESC, id DESC",
            nativeQuery = true)
    List<Long> findNeedingStillCheck(org.springframework.data.domain.Pageable page);

    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(value = "UPDATE media_assets SET still_picture = :still, still_checked = true WHERE id = :id",
            nativeQuery = true)
    int setStillPicture(@org.springframework.data.repository.query.Param("id") Long id,
                        @org.springframework.data.repository.query.Param("still") Boolean still);

    /** The ones among {@code ids} measured as one still picture (unmeasured files are not included). */
    @org.springframework.data.jpa.repository.Query(value = "SELECT id FROM media_assets WHERE id IN (:ids) AND still_picture = true",
            nativeQuery = true)
    List<Long> findStillPictures(@org.springframework.data.repository.query.Param("ids") java.util.Collection<Long> ids);

    /** The ones among {@code ids} not yet measured. */
    @org.springframework.data.jpa.repository.Query(value = "SELECT id FROM media_assets WHERE id IN (:ids) AND still_checked = false",
            nativeQuery = true)
    List<Long> findStillUnchecked(@org.springframework.data.repository.query.Param("ids") java.util.Collection<Long> ids);
}
