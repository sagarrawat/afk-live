package com.afklive.streamer.service;

import com.afklive.streamer.model.Tag;
import com.afklive.streamer.repository.TagRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class TagService {

    private final TagRepository tagRepository;

    @Transactional
    public Set<Tag> parseAndSaveTags(String tagsString) {
        Set<Tag> tags = new HashSet<>();
        if (tagsString == null || tagsString.trim().isEmpty()) {
            return tags;
        }

        String[] parts = tagsString.split(",");
        for (String part : parts) {
            String tagName = part.trim();
            if (!tagName.isEmpty()) {
                tags.add(getOrCreateTag(tagName));
            }
        }
        return tags;
    }

    @Transactional
    public Tag getOrCreateTag(String name) {
        return tagRepository.findByName(name)
                .orElseGet(() -> tagRepository.save(new Tag(name)));
    }
}
