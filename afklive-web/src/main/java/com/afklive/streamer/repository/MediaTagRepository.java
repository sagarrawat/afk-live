package com.afklive.streamer.repository;

import com.afklive.streamer.model.MediaTag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** ML-8: a creator's media tags and which files carry them. */
public interface MediaTagRepository extends JpaRepository<MediaTag, Long> {

    Optional<MediaTag> findByIdAndUserId(Long id, Long userId);

    long countByUserId(Long userId);

    Optional<MediaTag> findFirstByUserIdAndNameIgnoreCase(Long userId, String name);

    /** Each tag with how many files carry it, by name. */
    @Query("SELECT t.id, t.name, COUNT(l.id.mediaAssetId) FROM MediaTag t LEFT JOIN MediaAssetTag l ON l.id.tagId = t.id "
            + "WHERE t.userId = :userId GROUP BY t.id, t.name ORDER BY LOWER(t.name), t.id")
    List<Object[]> listWithCounts(@Param("userId") Long userId);

    /** (file id, tag id, tag name) for the given files, tags by name. */
    @Query("SELECT l.id.mediaAssetId, t.id, t.name FROM MediaAssetTag l JOIN MediaTag t ON t.id = l.id.tagId "
            + "WHERE l.id.mediaAssetId IN :assetIds ORDER BY LOWER(t.name), t.id")
    List<Object[]> tagsOf(@Param("assetIds") Collection<Long> assetIds);

    /**
     * Tags one file. Ownership is part of the statement: the tag and the file must both belong to
     * {@code userId}, so a forged id adds nothing. Returns 1 when added, 0 when already there.
     */
    @org.springframework.transaction.annotation.Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "INSERT INTO media_asset_tags (media_asset_id, tag_id) "
            + "SELECT a.id, t.id FROM media_assets a JOIN media_tags t ON t.user_id = a.user_id "
            + "WHERE a.id = :assetId AND t.id = :tagId AND t.user_id = :userId "
            + "ON CONFLICT DO NOTHING", nativeQuery = true)
    int addLink(@Param("userId") Long userId, @Param("tagId") Long tagId, @Param("assetId") Long assetId);

    @org.springframework.transaction.annotation.Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "DELETE FROM media_asset_tags l USING media_tags t "
            + "WHERE l.tag_id = t.id AND t.id = :tagId AND t.user_id = :userId AND l.media_asset_id IN (:assetIds)", nativeQuery = true)
    int removeLinks(@Param("userId") Long userId, @Param("tagId") Long tagId, @Param("assetIds") Collection<Long> assetIds);
}
