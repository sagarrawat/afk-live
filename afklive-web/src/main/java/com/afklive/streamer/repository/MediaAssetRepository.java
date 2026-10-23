package com.afklive.streamer.repository;

import com.afklive.streamer.model.MediaAsset;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface MediaAssetRepository extends JpaRepository<MediaAsset, Long> {
    List<MediaAsset> findByUserEmail(String email);
    Page<MediaAsset> findByUserEmail(String email, Pageable pageable);

    // For legacy lookups by filename (title)
    Optional<MediaAsset> findByUserEmailAndTitle(String email, String title);
    Optional<MediaAsset> findFirstByUserEmailAndS3Key(String email, String s3Key);

    Optional<MediaAsset> findByIdAndUserEmail(Long id, String email);

    List<MediaAsset> findAllByIdInAndUserEmail(java.util.Collection<Long> ids, String email);
}
