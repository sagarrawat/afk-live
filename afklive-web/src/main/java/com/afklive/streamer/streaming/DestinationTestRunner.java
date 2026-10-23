package com.afklive.streamer.streaming;

import com.afklive.streamer.repository.DestinationTestRepository;
import com.afklive.streamer.service.DestinationTestService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs destination tests on stream workers (behind the egress firewall), at most two at a time per worker. */
@Component
@ConditionalOnExpression("'${app.streaming.role:all}' != 'web'")
@Slf4j
public class DestinationTestRunner {

    static final int MAX_CONCURRENT = 2;

    private final DestinationTestRepository tests;
    private final DestinationTestService service;
    private final String workerId;
    private final boolean autoStart;
    private final AtomicInteger running = new AtomicInteger();
    private final ScheduledExecutorService loop = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "destination-tests");
        t.setDaemon(true);
        return t;
    });

    public DestinationTestRunner(DestinationTestRepository tests, DestinationTestService service, StreamWorker worker,
                                 @Value("${app.streaming.worker.auto-start:true}") boolean autoStart) {
        this.tests = tests;
        this.service = service;
        this.workerId = worker.workerId();
        this.autoStart = autoStart;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (autoStart) {
            loop.scheduleWithFixedDelay(this::poll, 2, 2, TimeUnit.SECONDS);
        }
    }

    /** Claims and starts pending tests while there's capacity. Public so tests can drive it. */
    public void poll() {
        try {
            for (Long id : tests.findPendingIds()) {
                if (running.get() >= MAX_CONCURRENT) {
                    return;
                }
                if (tests.claim(id, workerId, ZonedDateTime.now(ZoneId.of("UTC"))) == 1) {
                    running.incrementAndGet();
                    Thread.ofPlatform().daemon(true).name("destination-test-" + id).start(() -> {
                        try {
                            service.run(id);
                        } catch (Exception e) {
                            log.warn("Destination test {} failed to run: {}", id, e.getMessage());
                        } finally {
                            running.decrementAndGet();
                        }
                    });
                }
            }
        } catch (Exception e) {
            log.debug("Destination test poll failed: {}", e.getMessage());
        }
    }

    public int running() {
        return running.get();
    }

    @PreDestroy
    void stop() {
        loop.shutdownNow();
    }
}
