package com.afklive.streamer.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class JobInitializationService {

    private final VideoSchedulerService videoSchedulerService;
    private final StreamSchedulerService streamSchedulerService;

    @Scheduled(cron = "0 * * * * *")
    public void processScheduledVideos() {
        videoSchedulerService.processScheduledVideos();
    }

    @Scheduled(cron = "0 * * * * *")
    public void processScheduledStreams() {
        streamSchedulerService.processScheduledStreams();
    }
}
