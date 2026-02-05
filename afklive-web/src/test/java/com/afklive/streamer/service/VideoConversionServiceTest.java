package com.afklive.streamer.service;

import com.afklive.streamer.repository.ScheduledVideoRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VideoConversionServiceTest {

    @Mock
    private FileStorageService storageService;
    @Mock
    private UserService userService;
    @Mock
    private ScheduledVideoRepository repository;

    @InjectMocks
    private VideoConversionService videoConversionService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        // We need to ensure the cache is initialized if it's not injected by constructor or field injection properly in unit test
        // Since it's a field initialized inline, it should be fine.
    }

    @Test
    void testProgressCache() {
        String username = "testuser";
        String fileName = "video.mp4";
        String key = username + ":" + fileName;

        // Access the private cache field
        Cache<String, Integer> cache = (Cache<String, Integer>) ReflectionTestUtils.getField(videoConversionService, "conversionProgress");
        assertNotNull(cache);

        // Simulate putting progress
        cache.put(key, 50);

        // Check via public method
        Optional<Integer> progress = videoConversionService.getProgress(username, fileName);
        assertTrue(progress.isPresent());
        assertEquals(50, progress.get());

        // Simulate completion
        cache.put(key, 100);
        assertEquals(100, videoConversionService.getProgress(username, fileName).get());

        // Ensure cache works as expected (we can't easily test expiration in unit test without ticking time,
        // but we can verify the object is indeed a Cache)
        assertTrue(cache instanceof com.github.benmanes.caffeine.cache.Cache);
    }
}
