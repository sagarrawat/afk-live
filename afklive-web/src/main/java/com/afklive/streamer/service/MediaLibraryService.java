package com.afklive.streamer.service;

import com.afklive.streamer.model.MediaAsset;
import com.afklive.streamer.model.ScheduledVideo;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.MediaAssetRepository;
import com.afklive.streamer.repository.ScheduledVideoRepository;
import com.afklive.streamer.util.AppConstants;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adds a stored file to a user's library. Every way a video enters the library (upload, zip,
 * import) goes through here, so each one gets the same records: a MediaAsset owned by the user
 * (which streaming checks for ownership) and its library entry.
 */
@Service
@RequiredArgsConstructor
public class MediaLibraryService {

    private final UserService userService;
    private final MediaAssetRepository mediaAssetRepository;
    private final ScheduledVideoRepository scheduledVideoRepository;

    @Transactional
    public ScheduledVideo addVideo(String email, String title, String storageKey, long sizeBytes) {
        User user = userService.getOrCreateUser(email);
        MediaAsset asset = mediaAssetRepository.save(
                new MediaAsset(user, title, storageKey, sizeBytes, MediaAsset.ContentType.VIDEO));

        ScheduledVideo video = new ScheduledVideo();
        video.setUser(user);
        video.setTitle(title);
        video.setMediaAsset(asset);
        video.setStatus(ScheduledVideo.VideoStatus.LIBRARY);
        video.setPrivacyStatus(AppConstants.PRIVACY_PRIVATE);
        return scheduledVideoRepository.save(video);
    }
}
