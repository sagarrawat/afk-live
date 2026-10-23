package com.afklive.streamer.service;

import com.afklive.streamer.exception.ConflictException;
import com.afklive.streamer.exception.NotFoundException;
import com.afklive.streamer.model.MediaAsset;
import com.afklive.streamer.model.MediaStatus;
import com.afklive.streamer.model.MediaUpload;
import com.afklive.streamer.model.ScheduledVideo;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.MediaAssetRepository;
import com.afklive.streamer.repository.MediaUploadRepository;
import com.afklive.streamer.repository.ScheduledVideoRepository;
import com.afklive.streamer.repository.UserRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The media library (ML-1..ML-6): resumable uploads with storage reserved up front, listing with
 * search/sort/filter, the storage meter, rename and (bulk) delete that refuses files a live stream is
 * using.
 */
@Service
@Slf4j
public class MediaService {

    /** S3 needs parts of at least 5 MB (except the last) and at most 10,000 parts; the default part is 16 MB. */
    static final int MAX_PARTS = 10_000;
    static final Duration UPLOAD_TTL = Duration.ofHours(24);
    static final double WARN_AT = 0.8;

    static final Set<String> VIDEO_EXT = Set.of("mp4", "mov", "mkv", "avi", "webm", "flv", "m4v", "ts", "mts", "wmv", "mpg", "mpeg", "3gp");
    static final Set<String> AUDIO_EXT = Set.of("mp3", "m4a", "aac", "wav", "ogg", "oga", "opus", "flac", "wma");
    static final Set<String> IMAGE_EXT = Set.of("jpg", "jpeg", "png", "gif", "bmp");

    private final MediaAssetRepository assets;
    private final MediaUploadRepository uploads;
    private final ScheduledVideoRepository scheduledVideos;
    private final com.afklive.streamer.repository.StreamItemRepository streamItems;
    private final UserRepository users;
    private final PlanService plans;
    private final FileStorageService storage;
    private final MediaProcessor processor;
    private final LiveStreamService streams;
    private final TransactionTemplate tx;
    private final String role;
    private final long minPart;
    private final ScheduledExecutorService janitor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "upload-janitor");
        t.setDaemon(true);
        return t;
    });

    public MediaService(MediaAssetRepository assets, MediaUploadRepository uploads, ScheduledVideoRepository scheduledVideos,
                        com.afklive.streamer.repository.StreamItemRepository streamItems,
                        UserRepository users, PlanService plans, FileStorageService storage, MediaProcessor processor,
                        LiveStreamService streams, PlatformTransactionManager txManager,
                        @Value("${app.streaming.role:all}") String role,
                        @Value("${app.media.min-part-bytes:16777216}") long minPart) {
        this.minPart = Math.max(64 * 1024, minPart);
        this.assets = assets;
        this.uploads = uploads;
        this.scheduledVideos = scheduledVideos;
        this.streamItems = streamItems;
        this.users = users;
        this.plans = plans;
        this.storage = storage;
        this.processor = processor;
        this.streams = streams;
        this.tx = new TransactionTemplate(txManager);
        this.role = role;
    }

    // ------------------------------------------------------------------ views

    public record MediaView(Long id, String title, String type, String status, String statusReason, long sizeBytes,
                            Double durationSeconds, Integer width, Integer height, Boolean hasAudio, Double fps,
                            LocalDateTime createdAt, String thumbnailUrl, String previewUrl, List<String> usedBy, boolean inLiveUse) {
    }

    public record StorageView(long usedBytes, long limitBytes, double percent, boolean warning, boolean full, String plan) {
    }

    public record UploadView(String id, String filename, String type, long sizeBytes, long partSize, int partCount,
                             List<Integer> uploadedParts, long uploadedBytes, String status, Long mediaId, ZonedDateTime expiresAt) {
    }

    public record PartTarget(int partNumber, String url, String method, long size, boolean direct) {
    }

    public record DeleteResult(List<Long> deleted, List<Blocked> blocked, long freedBytes) {
    }

    public record Blocked(Long id, String title, String reason) {
    }

    // ------------------------------------------------------------------ listing (ML-3, ML-4)

    public Page<MediaView> list(String email, String q, String type, String status, String sort, String dir, int page, int size) {
        Specification<MediaAsset> spec = (root, query, cb) -> cb.equal(root.get("user").get("email"), email);
        if (q != null && !q.isBlank()) {
            String like = "%" + q.trim().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            spec = spec.and((root, query, cb) -> cb.like(cb.lower(root.get("title")), like, '\\'));
        }
        if (type != null && !type.isBlank()) {
            MediaAsset.ContentType t = parseEnum(MediaAsset.ContentType.class, type, "type");
            spec = spec.and((root, query, cb) -> cb.equal(root.get("contentType"), t));
        }
        if (status != null && !status.isBlank()) {
            MediaStatus st = parseEnum(MediaStatus.class, status, "status");
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), st));
        }
        String field = switch (sort == null ? "date" : sort.toLowerCase(Locale.ROOT)) {
            case "name" -> "title";
            case "duration" -> "durationSeconds";
            case "size" -> "fileSize";
            case "date" -> "createdAt";
            default -> throw new IllegalArgumentException("Sort by date, name, duration or size.");
        };
        Sort.Direction direction = "asc".equalsIgnoreCase(dir) ? Sort.Direction.ASC : Sort.Direction.DESC;
        if (dir == null && "title".equals(field)) {
            direction = Sort.Direction.ASC;
        }
        Sort order = Sort.by(new Sort.Order(direction, field).nullsLast()).and(Sort.by(Sort.Direction.DESC, "id"));
        Page<MediaAsset> result = assets.findAll(spec, PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100), order));
        Map<Long, List<LiveStreamService.MediaUse>> uses = streams.mediaUses(email, result.map(MediaAsset::getId).toList());
        return result.map(a -> view(a, uses.getOrDefault(a.getId(), List.of())));
    }

    public MediaView get(String email, Long id) {
        MediaAsset a = owned(email, id);
        return view(a, streams.mediaUses(email, List.of(id)).getOrDefault(id, List.of()));
    }

    private MediaView view(MediaAsset a, List<LiveStreamService.MediaUse> uses) {
        return new MediaView(a.getId(), a.getTitle(), a.getContentType().name(), a.getStatus().name(), a.getStatusReason(),
                a.getFileSize() == null ? 0 : a.getFileSize(), a.getDurationSeconds(), a.getWidth(), a.getHeight(), a.getHasAudio(),
                a.getFps(), a.getCreatedAt(),
                a.getThumbnailKey() == null ? null : "/api/media/" + a.getId() + "/thumbnail",
                "/api/media/" + a.getId() + "/file",
                uses.stream().map(LiveStreamService.MediaUse::streamName).toList(),
                uses.stream().anyMatch(LiveStreamService.MediaUse::live));
    }

    public StorageView storage(String email) {
        User u = user(email);
        long used = users.usedStorage(u.getId());
        long limit = plans.getPlanConfig(u.getPlanType()).getMaxStorageBytes();
        double pct = limit <= 0 ? 100 : Math.round(used * 1000.0 / limit) / 10.0;
        return new StorageView(used, limit, pct, used >= limit * WARN_AT, used >= limit, u.getPlanType().name());
    }

    // ------------------------------------------------------------------ resumable uploads (ML-1, ML-5)

    /** Starts an upload: checks the type, reserves the size against the plan, and opens a multipart upload. */
    @Transactional
    public UploadView startUpload(String email, String filename, long size) {
        User u = user(email);
        String name = filename == null ? "" : filename.trim();
        if (name.isEmpty() || name.length() > 255) {
            throw new IllegalArgumentException("The file needs a name (up to 255 characters).");
        }
        MediaAsset.ContentType type = typeOf(name);
        if (size <= 0) {
            throw new IllegalArgumentException("This file is empty.");
        }
        long limit = plans.getPlanConfig(u.getPlanType()).getMaxStorageBytes();
        if (users.reserveStorage(u.getId(), size, limit) != 1) {
            long used = users.usedStorage(u.getId());
            throw new ConflictException("Not enough storage: this file needs " + human(size) + " and you have "
                    + human(Math.max(0, limit - used)) + " free of " + human(limit) + ". Delete files or upgrade your plan.");
        }
        long partSize = Math.max(minPart, (size + MAX_PARTS - 1) / MAX_PARTS);
        MediaUpload up = new MediaUpload();
        up.setId(UUID.randomUUID().toString());
        up.setUserId(u.getId());
        up.setFilename(com.afklive.streamer.util.SafePaths.sanitizeFilename(name));
        up.setContentType(type);
        up.setSizeBytes(size);
        up.setPartSize(partSize);
        up.setStorageKey(storage.newKey(up.getFilename()));
        up.setMultipartId(storage.beginMultipart(up.getStorageKey()));
        ZonedDateTime now = now();
        up.setCreatedAt(now);
        up.setExpiresAt(now.plus(UPLOAD_TTL));
        uploads.save(up);
        return uploadView(up, List.of());
    }

    public UploadView upload(String email, String uploadId) {
        MediaUpload up = ownedUpload(email, uploadId);
        List<FileStorageService.Part> parts = up.getStatus() == MediaUpload.Status.UPLOADING
                ? storage.listParts(up.getStorageKey(), up.getMultipartId()) : List.of();
        return uploadView(up, parts);
    }

    public List<UploadView> openUploads(String email) {
        User u = user(email);
        return uploads.findAllByUserIdAndStatusOrderByCreatedAtDesc(u.getId(), MediaUpload.Status.UPLOADING).stream()
                .map(up -> uploadView(up, storage.listParts(up.getStorageKey(), up.getMultipartId()))).toList();
    }

    /** Where to send one part: straight to storage when it can take it, otherwise through the app. */
    public PartTarget partTarget(String email, String uploadId, int partNumber) {
        MediaUpload up = openUpload(email, uploadId);
        long length = partLength(up, partNumber);
        uploads.extend(up.getId(), now().plus(UPLOAD_TTL)); // active uploads don't expire
        Optional<String> direct = storage.presignPart(up.getStorageKey(), up.getMultipartId(), partNumber, length);
        return direct.map(url -> new PartTarget(partNumber, url, "PUT", length, true))
                .orElseGet(() -> new PartTarget(partNumber, "/api/media/uploads/" + up.getId() + "/parts/" + partNumber, "PUT", length, false));
    }

    /** A part sent through the app (storage without presigned uploads). */
    public String writePart(String email, String uploadId, int partNumber, InputStream body, long contentLength) {
        MediaUpload up = openUpload(email, uploadId);
        long expected = partLength(up, partNumber);
        if (contentLength >= 0 && contentLength != expected) {
            throw new IllegalArgumentException("Part " + partNumber + " should be " + expected + " bytes, not " + contentLength + ".");
        }
        String etag = storage.writePart(up.getStorageKey(), up.getMultipartId(), partNumber,
                new com.afklive.streamer.util.LimitedInputStream(body, expected), expected);
        uploads.extend(up.getId(), now().plus(UPLOAD_TTL));
        return etag;
    }

    /**
     * Joins the parts and adds the file to the library as Processing. {@code etags} are what storage
     * returned for each part (the browser reads them from the PUT responses); parts the app stored
     * itself don't need them.
     */
    public MediaView complete(String email, String uploadId, Map<Integer, String> etags) {
        User u = user(email);
        Long assetId = tx.execute(status -> {
            MediaUpload up = uploads.lockByIdAndUserId(uploadId, u.getId()).orElseThrow(() -> new NotFoundException("Upload not found."));
            if (up.getStatus() == MediaUpload.Status.COMPLETED && up.getMediaAssetId() != null) {
                return up.getMediaAssetId(); // completing twice is harmless
            }
            if (up.getStatus() != MediaUpload.Status.UPLOADING) {
                throw new ConflictException("This upload was cancelled or expired. Start it again.");
            }
            List<FileStorageService.Part> got = storage.listParts(up.getStorageKey(), up.getMultipartId());
            Map<Integer, FileStorageService.Part> byNumber = new java.util.HashMap<>();
            got.forEach(p -> byNumber.put(p.number(), p));
            List<FileStorageService.Part> ordered = new ArrayList<>();
            long total = 0;
            for (int n = 1; n <= up.partCount(); n++) {
                FileStorageService.Part p = byNumber.get(n);
                if (p == null) {
                    throw new ConflictException("Part " + n + " of " + up.partCount() + " hasn't arrived yet.");
                }
                String etag = p.etag() != null ? p.etag() : etags == null ? null : etags.get(n);
                ordered.add(new FileStorageService.Part(n, p.size(), etag));
                total += p.size();
            }
            if (total != up.getSizeBytes()) {
                throw new ConflictException("The parts add up to " + total + " bytes, not " + up.getSizeBytes() + ". Upload the file again.");
            }
            storage.completeMultipart(up.getStorageKey(), up.getMultipartId(), ordered);
            MediaAsset a = new MediaAsset(u, uniqueTitle(email, up.getFilename()), up.getStorageKey(), up.getSizeBytes(), up.getContentType());
            a.setStatus(MediaStatus.PROCESSING);
            a = assets.save(a);
            up.setStatus(MediaUpload.Status.COMPLETED);
            up.setMediaAssetId(a.getId());
            uploads.save(up);
            processor.enqueue(a.getId());
            return a.getId();
        });
        return get(email, assetId);
    }

    @Transactional
    public void cancelUpload(String email, String uploadId) {
        User u = user(email);
        MediaUpload up = uploads.lockByIdAndUserId(uploadId, u.getId()).orElseThrow(() -> new NotFoundException("Upload not found."));
        abort(up);
    }

    private void abort(MediaUpload up) {
        if (up.getStatus() != MediaUpload.Status.UPLOADING) {
            return;
        }
        try {
            storage.abortMultipart(up.getStorageKey(), up.getMultipartId());
        } catch (Exception e) {
            log.warn("Could not abort upload {} in storage", up.getId(), e);
        }
        up.setStatus(MediaUpload.Status.ABORTED);
        uploads.save(up);
        users.addStorage(up.getUserId(), -up.getSizeBytes());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startJanitor() {
        if ("worker".equalsIgnoreCase(role)) {
            return;
        }
        janitor.scheduleWithFixedDelay(() -> {
            try {
                expireUploads(now());
            } catch (Exception e) {
                log.error("Upload cleanup failed", e);
            }
        }, 5, 30, TimeUnit.MINUTES);
    }

    @PreDestroy
    void stopJanitor() {
        janitor.shutdownNow();
    }

    /** Abandoned uploads give their reserved storage back. */
    public int expireUploads(ZonedDateTime now) {
        int n = 0;
        for (String id : uploads.findExpired(now)) {
            Boolean done = tx.execute(status -> uploads.lockById(id)
                    .filter(up -> up.getStatus() == MediaUpload.Status.UPLOADING && up.getExpiresAt().isBefore(now))
                    .map(up -> {
                        abort(up);
                        return true;
                    }).orElse(false));
            if (Boolean.TRUE.equals(done)) {
                n++;
            }
        }
        return n;
    }

    private MediaUpload openUpload(String email, String uploadId) {
        MediaUpload up = ownedUpload(email, uploadId);
        if (up.getStatus() != MediaUpload.Status.UPLOADING) {
            throw new ConflictException(up.getStatus() == MediaUpload.Status.COMPLETED ? "This upload is already finished."
                    : "This upload was cancelled or expired. Start it again.");
        }
        return up;
    }

    private MediaUpload ownedUpload(String email, String uploadId) {
        return uploads.findByIdAndUserId(uploadId, user(email).getId()).orElseThrow(() -> new NotFoundException("Upload not found."));
    }

    static long partLength(MediaUpload up, int partNumber) {
        if (partNumber < 1 || partNumber > up.partCount()) {
            throw new IllegalArgumentException("Part number must be between 1 and " + up.partCount() + ".");
        }
        long start = (partNumber - 1L) * up.getPartSize();
        return Math.min(up.getPartSize(), up.getSizeBytes() - start);
    }

    private UploadView uploadView(MediaUpload up, List<FileStorageService.Part> parts) {
        List<Integer> done = new ArrayList<>();
        long bytes = 0;
        for (FileStorageService.Part p : parts) {
            if (p.number() >= 1 && p.number() <= up.partCount() && p.size() == partLength(up, p.number())) {
                done.add(p.number());
                bytes += p.size();
            }
        }
        if (up.getStatus() == MediaUpload.Status.COMPLETED) {
            bytes = up.getSizeBytes();
        }
        return new UploadView(up.getId(), up.getFilename(), up.getContentType().name(), up.getSizeBytes(), up.getPartSize(),
                up.partCount(), done, bytes, up.getStatus().name(), up.getMediaAssetId(), up.getExpiresAt());
    }

    /** VIDEO, AUDIO or IMAGE by extension, or a plain-language refusal (ML-2). */
    public static MediaAsset.ContentType typeOf(String filename) {
        int dot = filename.lastIndexOf('.');
        String ext = dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (VIDEO_EXT.contains(ext)) {
            return MediaAsset.ContentType.VIDEO;
        }
        if (AUDIO_EXT.contains(ext)) {
            return MediaAsset.ContentType.AUDIO;
        }
        if (IMAGE_EXT.contains(ext)) {
            return MediaAsset.ContentType.IMAGE;
        }
        throw new IllegalArgumentException((ext.isEmpty() ? "Files without an extension" : "." + ext + " files")
                + " aren't supported. Upload a video (MP4, MOV, MKV, WEBM…), audio (MP3, M4A, WAV…) or an image (JPG, PNG, GIF).");
    }

    private String uniqueTitle(String email, String filename) {
        String title = filename;
        int dot = filename.lastIndexOf('.');
        String base = dot > 0 ? filename.substring(0, dot) : filename;
        String ext = dot > 0 ? filename.substring(dot) : "";
        for (int n = 2; assets.existsByUserEmailAndTitle(email, title) && n < 1000; n++) {
            title = base + " (" + n + ")" + ext;
        }
        return title;
    }

    // ------------------------------------------------------------------ rename and delete (ML-6)

    @Transactional
    public MediaView rename(String email, Long id, String title) {
        MediaAsset a = owned(email, id);
        String t = title == null ? "" : title.trim();
        if (t.isEmpty() || t.length() > 255) {
            throw new IllegalArgumentException("Give the file a name (up to 255 characters).");
        }
        a.setTitle(t);
        assets.save(a);
        return get(email, id);
    }

    /**
     * Deletes the files that can go; files a live stream is using, or that are scheduled to upload to
     * YouTube, stay and are listed with the reason. Files used only by streams that aren't live are
     * removed from those streams.
     */
    public DeleteResult delete(String email, Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("Choose at least one file.");
        }
        if (ids.size() > 500) {
            throw new IllegalArgumentException("Delete up to 500 files at a time.");
        }
        Set<Long> unique = new HashSet<>(ids);
        List<MediaAsset> found = assets.findAllByIdInAndUserEmail(unique, email);
        if (found.size() != unique.size()) {
            throw new NotFoundException("Some of those files aren't in your library.");
        }
        Map<Long, List<LiveStreamService.MediaUse>> uses = streams.mediaUses(email, unique);
        List<Long> deleted = new ArrayList<>();
        List<Blocked> blocked = new ArrayList<>();
        long freed = 0;
        List<String> keys = new ArrayList<>();
        for (MediaAsset a : found) {
            List<String> live = uses.getOrDefault(a.getId(), List.of()).stream().filter(LiveStreamService.MediaUse::live)
                    .map(LiveStreamService.MediaUse::streamName).toList();
            if (!live.isEmpty()) {
                blocked.add(new Blocked(a.getId(), a.getTitle(), "In use by " + String.join(", ", live)
                        + ", which is live. Remove it from the stream or stop the stream first."));
                continue;
            }
            List<ScheduledVideo> scheduled = scheduledVideos.findAllByMediaAssetId(a.getId());
            if (scheduled.stream().anyMatch(v -> v.getStatus() == ScheduledVideo.VideoStatus.PENDING
                    || v.getStatus() == ScheduledVideo.VideoStatus.PROCESSING)) {
                blocked.add(new Blocked(a.getId(), a.getTitle(), "Scheduled to upload to YouTube. Cancel that first."));
                continue;
            }
            Long removed = tx.execute(status -> {
                // Going live takes the same user lock, so a stream can't start with this file while it's deleted.
                users.lockByEmail(email);
                MediaAsset fresh = assets.findByIdAndUserEmail(a.getId(), email).orElse(null);
                if (fresh == null) {
                    return null;
                }
                if (streams.mediaUses(email, List.of(fresh.getId())).getOrDefault(fresh.getId(), List.of()).stream()
                        .anyMatch(LiveStreamService.MediaUse::live)) {
                    return -1L;
                }
                streams.forgetMedia(email, fresh.getId());
                for (ScheduledVideo v : scheduledVideos.findAllByMediaAssetId(fresh.getId())) {
                    if (v.getStatus() == ScheduledVideo.VideoStatus.LIBRARY) {
                        scheduledVideos.delete(v);
                    }
                }
                scheduledVideos.flush();
                scheduledVideos.clearMedia(fresh.getId());
                scheduledVideos.clearThumbnail(fresh.getId());
                streamItems.deleteByMediaId(fresh.getId());
                assets.deleteById(fresh.getId());
                assets.flush();
                long size = fresh.getFileSize() == null ? 0 : fresh.getFileSize();
                users.addStorage(fresh.getUser().getId(), -size);
                return size;
            });
            if (removed == null) {
                continue;
            }
            if (removed < 0) {
                blocked.add(new Blocked(a.getId(), a.getTitle(), "A stream using it just went live. Stop it first."));
                continue;
            }
            deleted.add(a.getId());
            freed += removed;
            keys.add(a.getS3Key());
            if (a.getThumbnailKey() != null) {
                keys.add(a.getThumbnailKey());
            }
        }
        for (String key : keys) {
            try {
                storage.deleteFile(key);
            } catch (Exception e) {
                log.warn("Could not delete {} from storage", key, e); // the row is gone; an orphaned object is only cost
            }
        }
        return new DeleteResult(deleted, blocked, freed);
    }

    // ------------------------------------------------------------------ files

    public MediaAsset owned(String email, Long id) {
        return assets.findByIdAndUserEmail(id, email).orElseThrow(() -> new NotFoundException("File not found."));
    }

    private User user(String email) {
        return users.findByEmail(email).orElseThrow(() -> new NotFoundException("Account not found."));
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String what) {
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown " + what + ": " + value);
        }
    }

    static String human(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) {
            return String.format(Locale.ENGLISH, "%.1f GB", bytes / (1024.0 * 1024 * 1024));
        }
        if (bytes >= 1024L * 1024) {
            return String.format(Locale.ENGLISH, "%.0f MB", bytes / (1024.0 * 1024));
        }
        return Math.max(0, bytes / 1024) + " KB";
    }

    private static ZonedDateTime now() {
        return ZonedDateTime.now(ZoneId.of("UTC"));
    }
}
