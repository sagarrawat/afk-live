package com.afklive.streamer.service;

import com.afklive.streamer.model.PlanType;
import com.afklive.streamer.model.StreamJob;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.ScheduledVideoRepository;
import com.afklive.streamer.repository.StreamJobRepository;
import com.afklive.streamer.repository.UserRepository;
import com.afklive.streamer.repository.StreamDestinationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.web.multipart.MultipartFile;

import java.time.ZonedDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PayAsYouGoTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private StreamJobRepository streamJobRepository;

    @Mock
    private UserService userServiceMock;

    // Other dependencies for StreamService
    @Mock private UserFileService userFileService;
    @Mock private FileStorageService storageService;
    @Mock private AudioService audioService;
    @Mock private ScheduledVideoRepository scheduledVideoRepository;
    @Mock private YouTubeService youTubeService;
    @Mock private StreamDestinationRepository streamDestinationRepo;
    @Mock private EmailService emailService;

    @InjectMocks
    private StreamService streamService;

    private UserService realUserService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        // Use a "real" UserService (partially mocked) for logic tests
        // But for StreamService injection we use userServiceMock
        realUserService = new UserService(userRepository, null, emailService);
    }

    @Test
    void testCreditLimitLogic() {
        User user = new User("test@logic.com");
        user.setCreditLimit(50.0);

        when(userRepository.findById("test@logic.com")).thenReturn(Optional.of(user));

        user.setUnpaidBalance(40.0);
        assertTrue(realUserService.checkCreditLimit("test@logic.com"));

        user.setUnpaidBalance(50.0);
        assertFalse(realUserService.checkCreditLimit("test@logic.com"));

        user.setUnpaidBalance(60.0);
        assertFalse(realUserService.checkCreditLimit("test@logic.com"));
    }

    @Test
    void testBalanceUpdates() {
        when(userRepository.existsById("test@balance.com")).thenReturn(true);

        // Logic moved to UserRepository, but we can verify calls
        realUserService.addUnpaidBalance("test@balance.com", 5.5);
        verify(userRepository).addUnpaidBalance("test@balance.com", 5.5);

        realUserService.clearUnpaidBalance("test@balance.com", 5.5);
        verify(userRepository).deductUnpaidBalance("test@balance.com", 5.5);
    }

    @Test
    void testStartStreamBlocksIfCreditLimitExceeded() {
        String username = "test@stream.com";
        User user = new User(username);
        user.setPlanType(PlanType.FREE);

        when(userServiceMock.getOrCreateUser(username)).thenReturn(user);
        when(userServiceMock.checkCreditLimit(username)).thenReturn(false);

        assertThrows(IllegalStateException.class, () -> {
            streamService.startStream(username, java.util.List.of("key"), "vid", null, null, 0, null, true, "original", 720, null, null, null, false, null, false);
        });

        verify(userServiceMock, times(1)).checkCreditLimit(username);
    }

    @Test
    void testStopStreamCalculatesCost() {
        String username = "test@cost.com";
        long jobId = 1L;

        StreamJob job = new StreamJob();
        job.setId(jobId);
        job.setUsername(username);
        job.setLive(true);
        job.setPid(999999L); // Invalid PID likely
        // Start time 2 hours ago
        job.setStartTime(ZonedDateTime.now().minusHours(2));

        when(streamJobRepository.findById(jobId)).thenReturn(Optional.of(job));

        User user = new User(username);
        user.setPlanType(PlanType.FREE);
        when(userServiceMock.getOrCreateUser(username)).thenReturn(user);

        // Execute
        streamService.stopStream(jobId, username);

        // Verify cost: 2 hours * 1.25 = 2.50
        // Allow small delta for execution time
        verify(userServiceMock).addUnpaidBalance(eq(username), doubleThat(cost -> Math.abs(cost - 2.50) < 0.1));

        assertNotNull(job.getEndTime());
        assertNotNull(job.getCost());
        assertEquals(2.50, job.getCost(), 0.1);
        assertFalse(job.isLive());
    }

    @Test
    void testFractionalCostCalculation() {
        String username = "test@fractional.com";
        long jobId = 2L;

        StreamJob job = new StreamJob();
        job.setId(jobId);
        job.setUsername(username);
        job.setLive(true);
        job.setPid(12345L);
        // Start time 15 minutes ago
        job.setStartTime(ZonedDateTime.now().minusMinutes(15));
        job.setLastBillingTime(job.getStartTime());

        when(streamJobRepository.findById(jobId)).thenReturn(Optional.of(job));

        User user = new User(username);
        user.setPlanType(PlanType.FREE);
        when(userServiceMock.getOrCreateUser(username)).thenReturn(user);

        // Execute
        streamService.stopStream(jobId, username);

        // Verify cost: 0.25 hours * 1.25 = 0.3125
        verify(userServiceMock).addUnpaidBalance(eq(username), doubleThat(cost -> Math.abs(cost - 0.3125) < 0.0001));

        assertEquals(0.3125, job.getCost(), 0.0001);
    }
}
