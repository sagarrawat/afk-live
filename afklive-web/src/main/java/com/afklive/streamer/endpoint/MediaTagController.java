package com.afklive.streamer.endpoint;

import com.afklive.streamer.service.MediaTagService;
import com.afklive.streamer.util.SecurityUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;

/**
 * ML-8: tags for library files. {@code GET/POST /api/media/tags}, {@code PATCH/DELETE
 * /api/media/tags/{id}}, and {@code POST /api/media/tags/{id}/files} (add) or {@code
 * /api/media/tags/{id}/files/remove} with {@code {"ids":[...]}} for one or many files. Filter the
 * library with {@code GET /api/media?tag={id}} (or {@code tag=none} for untagged files).
 */
@RestController
@RequestMapping("/api/media/tags")
public class MediaTagController {

    private final MediaTagService tags;

    public MediaTagController(MediaTagService tags) {
        this.tags = tags;
    }

    public record NameRequest(String name) {
    }

    public record FilesRequest(List<Long> ids) {
    }

    @GetMapping
    public List<MediaTagService.TagView> list(Principal principal) {
        return tags.list(email(principal));
    }

    @PostMapping
    public ResponseEntity<MediaTagService.TagView> create(@RequestBody NameRequest body, Principal principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(tags.create(email(principal), body.name()));
    }

    @PatchMapping("/{id}")
    public MediaTagService.TagView rename(@PathVariable Long id, @RequestBody NameRequest body, Principal principal) {
        return tags.rename(email(principal), id, body.name());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, Principal principal) {
        tags.delete(email(principal), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/files")
    public MediaTagService.TagResult tagFiles(@PathVariable Long id, @RequestBody FilesRequest body, Principal principal) {
        return tags.tagFiles(email(principal), id, body.ids());
    }

    @PostMapping("/{id}/files/remove")
    public MediaTagService.TagResult untagFiles(@PathVariable Long id, @RequestBody FilesRequest body, Principal principal) {
        return tags.untagFiles(email(principal), id, body.ids());
    }

    private static String email(Principal principal) {
        if (principal == null) {
            throw new org.springframework.security.access.AccessDeniedException("Sign in first.");
        }
        return SecurityUtils.getEmail(principal);
    }
}
