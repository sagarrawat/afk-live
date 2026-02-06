package com.afklive.streamer.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jobrunr.scheduling.JobScheduler;
import org.jobrunr.scheduling.cron.Cron;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class JobInitializationService {

    private final JobScheduler jobScheduler;
    private final VideoSchedulerService videoSchedulerService;
    private final EngagementService engagementService;
    private final StreamSchedulerService streamSchedulerService;

    @PostConstruct
    public void scheduleJobs() {
        log.info("Initializing JobRunr recurring jobs...");

        jobScheduler.scheduleRecurrently("video-upload-job", Cron.minutely(),
            () -> videoSchedulerService.processScheduledVideos());

        // 30 seconds interval for live engagement
        jobScheduler.scheduleRecurrently("live-engagement-job", "*/30 * * * * *",
            () -> engagementService.processLiveEngagement());

        jobScheduler.scheduleRecurrently("general-engagement-job", Cron.minutely(),
            () -> engagementService.processEngagement());

        jobScheduler.scheduleRecurrently("stream-schedule-job", Cron.minutely(),
            () -> streamSchedulerService.processScheduledStreams());

        log.info("JobRunr recurring jobs initialized.");
    }
}
