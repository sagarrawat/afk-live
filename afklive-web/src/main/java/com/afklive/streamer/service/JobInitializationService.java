package com.afklive.streamer.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jobrunr.scheduling.JobScheduler;
import org.jobrunr.scheduling.cron.Cron;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class JobInitializationService {

    /**
     * Ids of recurring jobs for features removed from the product: scheduled YouTube uploads, and the
     * automatic live-chat replies and comment replies/auto-delete (AI-3: YouTube's spam policy and API
     * Developer Policies III.I.2 forbid automated chat and comments). The code they ran
     * ({@code EngagementService}) is gone; nothing schedules them. Their ids are still removed from
     * JobRunr's store on startup so a store restored from an old backup can't try to run code that no
     * longer exists.
     */
    static final List<String> RETIRED_JOBS = List.of("video-upload-job", "live-engagement-job", "general-engagement-job");

    private final JobScheduler jobScheduler;
    private final StreamSchedulerService streamSchedulerService;

    @PostConstruct
    public void scheduleJobs() {
        for (String id : RETIRED_JOBS) {
            try {
                jobScheduler.deleteRecurringJob(id);
            } catch (Exception e) {
                log.debug("Recurring job {} was not registered", id);
            }
        }

        jobScheduler.scheduleRecurrently("stream-schedule-job", Cron.minutely(),
            () -> streamSchedulerService.processScheduledStreams());

        log.info("JobRunr recurring jobs initialized.");
    }
}
