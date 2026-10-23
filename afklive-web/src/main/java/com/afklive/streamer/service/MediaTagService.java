package com.afklive.streamer.service;

import com.afklive.streamer.exception.ConflictException;
import com.afklive.streamer.exception.NotFoundException;
import com.afklive.streamer.model.MediaTag;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.MediaAssetRepository;
import com.afklive.streamer.repository.MediaTagRepository;
import com.afklive.streamer.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * ML-8: tags a creator puts on library files to organise them by show or mood. Every lookup is
 * scoped to the signed-in user: another user's tag or file is "not found", never touched.
 */
@Service
public class MediaTagService {

    static final int MAX_FILES = 500;

    private final MediaTagRepository tags;
    private final MediaAssetRepository assets;
    private final UserRepository users;

    public MediaTagService(MediaTagRepository tags, MediaAssetRepository assets, UserRepository users) {
        this.tags = tags;
        this.assets = assets;
        this.users = users;
    }

    public record TagView(Long id, String name, long fileCount) {
    }

    /** How many files a bulk tag or untag changed. */
    public record TagResult(Long tagId, String name, int changed) {
    }

    public List<TagView> list(String email) {
        return tags.listWithCounts(user(email).getId()).stream()
                .map(r -> new TagView(((Number) r[0]).longValue(), (String) r[1], ((Number) r[2]).longValue()))
                .toList();
    }

    @Transactional
    public TagView create(String email, String rawName) {
        String name = cleanName(rawName);
        // The user lock keeps two quick creates from both passing the limit check.
        User u = users.lockByEmail(email).orElseThrow(() -> new NotFoundException("Account not found."));
        if (tags.findFirstByUserIdAndNameIgnoreCase(u.getId(), name).isPresent()) {
            throw new ConflictException("You already have a tag called \"" + name + "\".");
        }
        if (tags.countByUserId(u.getId()) >= MediaTag.MAX_PER_USER) {
            throw new ConflictException("You can have up to " + MediaTag.MAX_PER_USER + " tags. Delete one you no longer use first.");
        }
        try {
            MediaTag t = tags.saveAndFlush(new MediaTag(u.getId(), name));
            return new TagView(t.getId(), t.getName(), 0);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("You already have a tag called \"" + name + "\".");
        }
    }

    @Transactional
    public TagView rename(String email, Long id, String rawName) {
        String name = cleanName(rawName);
        User u = user(email);
        MediaTag t = owned(u, id);
        tags.findFirstByUserIdAndNameIgnoreCase(u.getId(), name)
                .filter(other -> !other.getId().equals(t.getId()))
                .ifPresent(other -> {
                    throw new ConflictException("You already have a tag called \"" + other.getName() + "\".");
                });
        t.setName(name);
        try {
            tags.saveAndFlush(t);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("You already have a tag called \"" + name + "\".");
        }
        return list(email).stream().filter(v -> v.id().equals(id)).findFirst().orElseThrow();
    }

    /** Removes the tag from every file; the files themselves stay. */
    @Transactional
    public void delete(String email, Long id) {
        User u = user(email);
        tags.delete(owned(u, id));
    }

    @Transactional
    public TagResult tagFiles(String email, Long tagId, Collection<Long> fileIds) {
        User u = user(email);
        MediaTag t = owned(u, tagId);
        int changed = 0;
        for (Long fileId : ownedFiles(email, fileIds)) {
            changed += tags.addLink(u.getId(), t.getId(), fileId);
        }
        return new TagResult(t.getId(), t.getName(), changed);
    }

    @Transactional
    public TagResult untagFiles(String email, Long tagId, Collection<Long> fileIds) {
        User u = user(email);
        MediaTag t = owned(u, tagId);
        Set<Long> ids = ownedFiles(email, fileIds);
        return new TagResult(t.getId(), t.getName(), tags.removeLinks(u.getId(), t.getId(), ids));
    }

    /** The tag, if it's this user's; used to check a library filter. */
    public MediaTag owned(User u, Long id) {
        if (id == null) {
            throw new NotFoundException("Tag not found.");
        }
        return tags.findByIdAndUserId(id, u.getId()).orElseThrow(() -> new NotFoundException("Tag not found."));
    }

    private Set<Long> ownedFiles(String email, Collection<Long> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            throw new IllegalArgumentException("Choose at least one file.");
        }
        Set<Long> unique = new LinkedHashSet<>(fileIds);
        unique.remove(null);
        if (unique.isEmpty()) {
            throw new IllegalArgumentException("Choose at least one file.");
        }
        if (unique.size() > MAX_FILES) {
            throw new IllegalArgumentException("Tag up to " + MAX_FILES + " files at a time.");
        }
        if (assets.findAllByIdInAndUserEmail(unique, email).size() != unique.size()) {
            throw new NotFoundException("Some of those files aren't in your library.");
        }
        return unique;
    }

    /** Trimmed, inner spaces collapsed, 1..40 characters, no control characters. */
    static String cleanName(String raw) {
        String name = raw == null ? "" : raw.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Give the tag a name.");
        }
        if (name.length() > MediaTag.MAX_NAME) {
            throw new IllegalArgumentException("Keep tag names to " + MediaTag.MAX_NAME + " characters or fewer.");
        }
        return name;
    }

    private User user(String email) {
        return users.findByEmail(email).orElseThrow(() -> new NotFoundException("Account not found."));
    }
}
