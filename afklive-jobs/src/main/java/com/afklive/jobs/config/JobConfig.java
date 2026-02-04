package com.afklive.jobs.config;

import com.afklive.streamer.service.EngagementService;
import com.afklive.streamer.service.StreamSchedulerService;
import com.afklive.streamer.service.VideoSchedulerService;
import jakarta.annotation.PostConstruct;
import org.jobrunr.scheduling.JobScheduler;
import org.jobrunr.scheduling.cron.Cron;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

@Configuration
public class JobConfig {

    private final JobScheduler jobScheduler;
    private final EngagementService engagementService;
    private final VideoSchedulerService videoSchedulerService;
    private final StreamSchedulerService streamSchedulerService;

    public JobConfig(JobScheduler jobScheduler,
                     EngagementService engagementService,
                     VideoSchedulerService videoSchedulerService,
                     StreamSchedulerService streamSchedulerService) {
        this.jobScheduler = jobScheduler;
        this.engagementService = engagementService;
        this.videoSchedulerService = videoSchedulerService;
        this.streamSchedulerService = streamSchedulerService;
    }

    @PostConstruct
    public void scheduleRecurrently() {
        jobScheduler.scheduleRecurrently("engagement-live", Cron.minutely(), engagementService::processLiveEngagement);
        jobScheduler.scheduleRecurrently("engagement-comments", Cron.minutely(), engagementService::processEngagement);
        jobScheduler.scheduleRecurrently("video-scheduler", Cron.minutely(), videoSchedulerService::processScheduledVideos);
        jobScheduler.scheduleRecurrently("stream-scheduler", Cron.minutely(), streamSchedulerService::processScheduledStreams);
    }

    @Bean(name = "serviceAuthorizedClientManager")
    public AuthorizedClientServiceOAuth2AuthorizedClientManager authorizedClientManager(
            ClientRegistrationRepository clientRegistrationRepository,
            OAuth2AuthorizedClientService authorizedClientService) {

        AuthorizedClientServiceOAuth2AuthorizedClientManager authorizedClientManager =
                new AuthorizedClientServiceOAuth2AuthorizedClientManager(
                        clientRegistrationRepository, authorizedClientService);

        OAuth2AuthorizedClientProvider authorizedClientProvider =
                OAuth2AuthorizedClientProviderBuilder.builder()
                        .refreshToken()
                        .clientCredentials()
                        .build();

        authorizedClientManager.setAuthorizedClientProvider(authorizedClientProvider);

        return authorizedClientManager;
    }
}
