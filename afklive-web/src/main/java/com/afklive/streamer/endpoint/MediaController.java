package com.afklive.streamer.endpoint;

import com.afklive.streamer.model.MediaAsset;
import com.afklive.streamer.service.FileStorageService;
import com.afklive.streamer.service.MediaService;
import com.afklive.streamer.util.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.security.Principal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The media library API (ML-1..ML-6).
 * <p>
 * Uploads: {@code POST /uploads} with the name and size, then for each part {@code POST
 * /uploads/{id}/parts/{n}} to get where to PUT it (straight to storage when it supports presigned
 * uploads; otherwise {@code PUT /uploads/{id}/parts/{n}} here), then {@code POST
 * /uploads/{id}/complete} with the ETags. {@code GET /uploads/{id}} lists the parts already stored,
 * so a page refresh or a dropped connection resumes where it stopped.
 */
@RestController
@RequestMapping("/api/media")
public class MediaController {

    private final MediaService media;
    private final FileStorageService storage;

    public MediaController(MediaService media, FileStorageService storage) {
        this.media = media;
        this.storage = storage;
    }

    @GetMapping
    public Page<MediaService.MediaView> list(Principal principal,
                                             @RequestParam(required = false) String q,
                                             @RequestParam(required = false) String type,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(required = false) String tag,
                                             @RequestParam(required = false) String sort,
                                             @RequestParam(required = false) String dir,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "24") int size) {
        return media.list(email(principal), q, type, status, tag, sort, dir, page, size);
    }

    @GetMapping("/storage")
    public MediaService.StorageView storage(Principal principal) {
        return media.storage(email(principal));
    }

    @GetMapping("/{id}")
    public MediaService.MediaView get(@PathVariable Long id, Principal principal) {
        return media.get(email(principal), id);
    }

    @PatchMapping("/{id}")
    public MediaService.MediaView rename(@PathVariable Long id, @RequestBody Map<String, String> body, Principal principal) {
        return media.rename(email(principal), id, body.get("title"));
    }

    @DeleteMapping("/{id}")
    public MediaService.DeleteResult delete(@PathVariable Long id, Principal principal) {
        return deleted(media.delete(email(principal), List.of(id)));
    }

    public record DeleteRequest(List<Long> ids) {
    }

    /** Bulk delete; files that can't go are returned in {@code blocked} with the reason. */
    @PostMapping("/delete")
    public MediaService.DeleteResult deleteMany(@RequestBody DeleteRequest body, Principal principal) {
        return deleted(media.delete(email(principal), body.ids()));
    }

    private static MediaService.DeleteResult deleted(MediaService.DeleteResult r) {
        if (r.deleted().isEmpty() && !r.blocked().isEmpty()) {
            throw new com.afklive.streamer.exception.ConflictException(r.blocked().get(0).reason());
        }
        return r;
    }

    @GetMapping("/{id}/thumbnail")
    public ResponseEntity<?> thumbnail(@PathVariable Long id, Principal principal) {
        MediaAsset a = media.owned(email(principal), id);
        if (a.getThumbnailKey() == null) {
            return ResponseEntity.notFound().build();
        }
        return serve(a.getThumbnailKey(), MediaType.IMAGE_JPEG, Duration.ofHours(1));
    }

    /** The file itself, for the preview player (ML-3); supports range requests. */
    @GetMapping("/{id}/file")
    public ResponseEntity<?> file(@PathVariable Long id, Principal principal) {
        MediaAsset a = media.owned(email(principal), id);
        MediaType type = MediaTypeFactory.getMediaType(a.getTitle()).orElse(MediaType.APPLICATION_OCTET_STREAM);
        return serve(a.getS3Key(), type, Duration.ZERO);
    }

    private ResponseEntity<?> serve(String key, MediaType type, Duration cache) {
        Optional<String> url = storage.generatePresignedUrl(key);
        if (url.isPresent()) {
            return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, url.get())
                    .cacheControl(CacheControl.noStore()).build();
        }
        Resource r = storage.loadFileAsResource(key);
        return ResponseEntity.ok().contentType(type)
                .cacheControl(cache.isZero() ? CacheControl.noCache().cachePrivate() : CacheControl.maxAge(cache).cachePrivate())
                .body(r);
    }

    // ------------------------------------------------------------------ uploads

    public record StartUpload(String filename, long size) {
    }

    @PostMapping("/uploads")
    public ResponseEntity<MediaService.UploadView> start(@RequestBody StartUpload body, Principal principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(media.startUpload(email(principal), body.filename(), body.size()));
    }

    @GetMapping("/uploads")
    public List<MediaService.UploadView> open(Principal principal) {
        return media.openUploads(email(principal));
    }

    @GetMapping("/uploads/{id}")
    public MediaService.UploadView upload(@PathVariable String id, Principal principal) {
        return media.upload(email(principal), id);
    }

    @PostMapping("/uploads/{id}/parts/{n}")
    public MediaService.PartTarget part(@PathVariable String id, @PathVariable int n, Principal principal) {
        return media.partTarget(email(principal), id, n);
    }

    @PutMapping("/uploads/{id}/parts/{n}")
    public ResponseEntity<Map<String, String>> putPart(@PathVariable String id, @PathVariable int n, HttpServletRequest request,
                                                       Principal principal) throws IOException {
        String etag = media.writePart(email(principal), id, n, request.getInputStream(), request.getContentLengthLong());
        return ResponseEntity.ok().eTag(etag).body(Map.of("etag", etag));
    }

    public record Complete(Map<Integer, String> etags) {
    }

    @PostMapping("/uploads/{id}/complete")
    public MediaService.MediaView complete(@PathVariable String id, @RequestBody(required = false) Complete body, Principal principal) {
        return media.complete(email(principal), id, body == null ? null : body.etags());
    }

    @DeleteMapping("/uploads/{id}")
    public ResponseEntity<Void> cancel(@PathVariable String id, Principal principal) {
        media.cancelUpload(email(principal), id);
        return ResponseEntity.noContent().build();
    }

    private static String email(Principal principal) {
        if (principal == null) {
            throw new org.springframework.security.access.AccessDeniedException("Sign in first.");
        }
        return SecurityUtils.getEmail(principal);
    }
}
