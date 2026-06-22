package com.afklive.streamer.service;

import com.afklive.streamer.model.StreamJob;
import com.afklive.streamer.model.User;
import com.afklive.streamer.model.PlanType;
import com.afklive.streamer.repository.StreamJobQueueRepository;
import com.afklive.streamer.repository.StreamJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class WatchdogAndBillingServiceTest {

    @Mock
    private StreamJobRepository streamJobRepository;

    @Mock
    private StreamJobQueueRepository streamJobQueueRepository;

    @Mock
    private UserService userService;

    @Mock
    private ApplicationContext applicationContext;

    @Mock
    private EmailService emailService;

    @InjectMocks
    private WatchdogAndBillingService watchdogService;

    private User testUser;
    private StreamJob staleJob;

    @BeforeEach
    void setUp() {
        testUser = new User();
        testUser.setEmail("test@example.com");
        testUser.setPlanType(PlanType.FREE);
        testUser.setUnpaidBalance(new BigDecimal("10.00"));
        testUser.setCurrency("USD");

        staleJob = new StreamJob();
        staleJob.setId(1L);
        staleJob.setUser(testUser);
        staleJob.setStatus(StreamJob.StreamStatus.LIVE);
        staleJob.setLastHeartbeat(ZonedDateTime.now(ZoneId.of("UTC")).minusSeconds(90));
        staleJob.setRetryCount(0);
        staleJob.setMaxRetries(3);

        org.springframework.test.util.ReflectionTestUtils.setField(watchdogService, "applicationContext", applicationContext);
    }

    @Test
    void testProcessSingleJobInTransaction_RecoversStaleJob() {
        when(streamJobRepository.findById(1L)).thenReturn(Optional.of(staleJob));

        watchdogService.processSingleJobInTransaction(1L, ZonedDateTime.now(ZoneId.of("UTC")));

        // Status should change to RECOVERY_PENDING for recovery
        assertEquals(StreamJob.StreamStatus.RECOVERY_PENDING, staleJob.getStatus());
        assertEquals(1, staleJob.getRetryCount());

        verify(streamJobRepository, times(1)).save(staleJob);
        verify(streamJobQueueRepository, times(1)).save(any());
    }

    @Test
    void testProcessSingleJobInTransaction_FailsJobExceedingMaxRetries() {
        staleJob.setRetryCount(3); // Reached max retries
        when(streamJobRepository.findById(1L)).thenReturn(Optional.of(staleJob));
        when(applicationContext.getBean(EmailService.class)).thenReturn(emailService);

        watchdogService.processSingleJobInTransaction(1L, ZonedDateTime.now(ZoneId.of("UTC")));

        assertEquals(StreamJob.StreamStatus.FAILED, staleJob.getStatus());
        verify(streamJobRepository, times(1)).save(staleJob);
        
        verify(emailService, times(1)).sendStreamStatusEmail(
                eq("test@example.com"),
                any(),
                contains("FAILED"),
                contains("Worker crashed and max retries")
        );
    }
}
