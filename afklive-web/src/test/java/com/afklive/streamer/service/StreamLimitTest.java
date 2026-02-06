package com.afklive.streamer.service;

import com.afklive.streamer.model.PlanType;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.StreamJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.web.multipart.MultipartFile;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class StreamLimitTest {

    @Mock
    private AppConfigService appConfigService;

    @Mock
    private StreamJobRepository streamJobRepo;

    @Mock
    private UserService userService;

    // Dependencies for startStream validation/execution
    @Mock private UserFileService userFileService;
    @Mock private FileStorageService storageService;
    @Mock private AudioService audioService;
    @Mock private com.afklive.streamer.repository.ScheduledVideoRepository scheduledVideoRepository;
    @Mock private YouTubeService youTubeService;
    @Mock private com.afklive.streamer.repository.StreamDestinationRepository streamDestinationRepo;
    @Mock private PlanService planService;

    @InjectMocks
    private StreamService streamService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void testStartStream_UnderLimit_PayAsYouGo_Allowed() {
        String username = "test@user.com";
        User user = new User(username);
        user.setPlanType(PlanType.FREE);

        when(appConfigService.getGlobalStreamLimit()).thenReturn(10);
        when(streamJobRepo.countByIsLiveTrue()).thenReturn(5L);
        when(userService.getOrCreateUser(username)).thenReturn(user);
        when(userService.checkCreditLimit(username)).thenReturn(true);
        // Mock plan service to avoid NPE later in method
        when(planService.getPlanConfig(any())).thenReturn(new com.afklive.streamer.model.PlanConfig(PlanType.FREE, "Free", "0", "Monthly", 100L, 10, 10, 1, 720));

        try {
             streamService.startStream(username, java.util.List.of("key"), "video", null, null, 0, null, true, "original", 720, null, null, null, false, null, false);
        } catch (Exception e) {
             // Ignore other exceptions, just ensure it's not the capacity one
             if (e.getMessage().contains("Platform capacity reached")) {
                 throw new RuntimeException("Should not be blocked by capacity limit");
             }
        }
    }

    @Test
    void testStartStream_OverLimit_PayAsYouGo_Blocked() {
        String username = "test@user.com";
        User user = new User(username);
        user.setPlanType(PlanType.FREE);

        when(appConfigService.getGlobalStreamLimit()).thenReturn(10);
        when(streamJobRepo.countByIsLiveTrue()).thenReturn(10L);
        when(userService.getOrCreateUser(username)).thenReturn(user);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> {
            streamService.startStream(username, java.util.List.of("key"), "video", null, null, 0, null, true, "original", 720, null, null, null, false, null, false);
        });

        assert(ex.getMessage().contains("Platform capacity reached"));
    }

    @Test
    void testStartStream_OverLimit_Essentials_Allowed() {
        String username = "test@essential.com";
        User user = new User(username);
        user.setPlanType(PlanType.ESSENTIALS);

        when(appConfigService.getGlobalStreamLimit()).thenReturn(10);
        when(streamJobRepo.countByIsLiveTrue()).thenReturn(10L);
        when(userService.getOrCreateUser(username)).thenReturn(user);

        // Mock plan service
        when(planService.getPlanConfig(any())).thenReturn(new com.afklive.streamer.model.PlanConfig(PlanType.ESSENTIALS, "Essentials", "10", "Monthly", 100L, 10, 10, 1, 1080));

        try {
             streamService.startStream(username, java.util.List.of("key"), "video", null, null, 0, null, true, "original", 1080, null, null, null, false, null, false);
        } catch (Exception e) {
             // Ignore other exceptions
             if (e.getMessage().contains("Platform capacity reached")) {
                 throw new RuntimeException("Should not be blocked");
             }
        }
    }
}
