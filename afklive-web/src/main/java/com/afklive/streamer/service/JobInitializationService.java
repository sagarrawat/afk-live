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
     * Recurring jobs for features removed from the product (scheduled YouTube uploads and comment
     * engagement). They kept running every 30-60 s, spending YouTube API quota and auto-deleting
     * comments rated negative, with no UI to turn them off. Removed from JobRunr's store on startup.
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
