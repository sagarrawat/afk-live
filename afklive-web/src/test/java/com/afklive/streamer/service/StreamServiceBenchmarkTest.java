package com.afklive.streamer.service;

import com.afklive.streamer.model.ScheduledVideo;
import com.afklive.streamer.repository.ScheduledVideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:testdb",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
    "spring.sql.init.mode=never",
    "app.aws.access-key=test",
    "app.aws.secret-key=test",
    "app.storage.endpoint=test",
    "app.storage.region=test",
    "app.storage.bucket=test",
    "app.storage.access-key=test",
    "app.storage.secret-key=test",
    "GOOGLE_CLIENT_ID=test",
    "GOOGLE_CLIENT_SECRET=test",
    "GOOGLE_YOUTUBE_CLIENT_ID=test",
    "GOOGLE_YOUTUBE_CLIENT_SECRET=test",
    "spring.security.oauth2.client.registration.google.client-id=test",
    "spring.security.oauth2.client.registration.google.client-secret=test",
    "spring.security.oauth2.client.registration.google-youtube.client-id=test",
    "spring.security.oauth2.client.registration.google-youtube.client-secret=test",
    "spring.security.oauth2.client.registration.google-youtube.provider=google",
    "spring.security.oauth2.client.registration.google-youtube.authorization-grant-type=authorization_code",
    "spring.security.oauth2.client.registration.google-youtube.redirect-uri={baseUrl}/login/oauth2/code/{registrationId}"
})
@ActiveProfiles("test")
public class StreamServiceBenchmarkTest {

    @Autowired
    private ScheduledVideoRepository scheduledVideoRepository;

    private final String USERNAME = "benchmarkUser";
    private final String TARGET_KEY = "target-video-key";
    private final int RECORD_COUNT = 10000;

    @BeforeEach
    public void setup() {
        List<ScheduledVideo> videos = new ArrayList<>();
        for (int i = 0; i < RECORD_COUNT; i++) {
            ScheduledVideo v = new ScheduledVideo();
            v.setUsername(USERNAME);
            v.setTitle("Video " + i);
            v.setS3Key(i == 5000 ? TARGET_KEY : UUID.randomUUID().toString());
            v.setScheduledTime(ZonedDateTime.now());
            v.setStatus(ScheduledVideo.VideoStatus.PENDING);
            videos.add(v);
        }
        scheduledVideoRepository.saveAll(videos);
        scheduledVideoRepository.flush();
    }

    @Test
    public void benchmarkLegacyFiltering() {
        long start = System.nanoTime();

        Optional<ScheduledVideo> result = scheduledVideoRepository.findByUsername(USERNAME).stream()
                .filter(v -> v.getS3Key() != null && v.getS3Key().equals(TARGET_KEY))
                .findFirst();

        long end = System.nanoTime();
        double ms = (end - start) / 1_000_000.0;

        System.out.println("LEGACY BENCHMARK: Found video in " + ms + " ms");
        assertThat(result).isPresent();
        assertThat(result.get().getS3Key()).isEqualTo(TARGET_KEY);
    }

    @Test
    public void benchmarkOptimizedFiltering() {
        long start = System.nanoTime();

        Optional<ScheduledVideo> result = scheduledVideoRepository.findFirstByUsernameAndS3Key(USERNAME, TARGET_KEY);

        long end = System.nanoTime();
        double ms = (end - start) / 1_000_000.0;

        System.out.println("OPTIMIZED BENCHMARK: Found video in " + ms + " ms");
        assertThat(result).isPresent();
        assertThat(result.get().getS3Key()).isEqualTo(TARGET_KEY);
    }
}
