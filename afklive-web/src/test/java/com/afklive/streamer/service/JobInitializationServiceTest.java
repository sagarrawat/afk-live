package com.afklive.streamer.service;

import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** AI-3: the automatic reply jobs never come back; only the stream schedule runs. */
class JobInitializationServiceTest {

    @Test
    void theEngagementJobsAreRemovedAndOnlyTheStreamScheduleIsRecurring() {
        JobScheduler scheduler = mock(JobScheduler.class);
        new JobInitializationService(scheduler, mock(StreamSchedulerService.class)).scheduleJobs();

        assertThat(JobInitializationService.RETIRED_JOBS).contains("live-engagement-job", "general-engagement-job");
        for (String id : JobInitializationService.RETIRED_JOBS) {
            verify(scheduler).deleteRecurringJob(id);
        }
        verify(scheduler).scheduleRecurrently(eq("stream-schedule-job"), anyString(), any(org.jobrunr.jobs.lambdas.JobLambda.class));
        org.mockito.Mockito.verifyNoMoreInteractions(scheduler);
    }
}
