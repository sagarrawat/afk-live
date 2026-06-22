package com.afklive.streamer.service;

import com.afklive.streamer.dto.ApiResponse;
import com.afklive.streamer.model.StreamJob;
import com.afklive.streamer.model.User;
import com.afklive.streamer.model.PlanType;
import com.afklive.streamer.repository.StreamJobQueueRepository;
import com.afklive.streamer.repository.StreamJobRepository;
import com.afklive.streamer.repository.StreamDestinationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class StreamServiceTest {

    @Mock
    private StreamJobRepository streamJobRepo;
    
    @Mock
    private StreamJobQueueRepository streamJobQueueRepo;
    
    @Mock
    private UserService userService;
    
    @Mock
    private AppConfigService appConfigService;
    
    @Mock
    private StreamDestinationRepository streamDestinationRepo;
    
    @Mock
    private YouTubeService youTubeService;
    
    @Mock
    private EmailService emailService;
    
    @Mock
    private ObjectMapper objectMapper;
    
    @Mock
    private PlanService planService;

    @InjectMocks
    private StreamService streamService;

    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = new User();
        testUser.setUsername("test@example.com");
        testUser.setPlanType(com.afklive.streamer.model.PlanType.FREE);
        
        com.afklive.streamer.model.PlanConfig planConfig = new com.afklive.streamer.model.PlanConfig();
        planConfig.setDiscountPercentage(0);
        org.mockito.Mockito.lenient().when(planService.getPlanConfig(any())).thenReturn(planConfig);
        testUser.setEmail("test@example.com");
        testUser.setPlanType(PlanType.FREE);
        testUser.setUnpaidBalance(new BigDecimal("10.00"));
        testUser.setCurrency("USD");
    }

    @Test
    void testStartStreamChecksPlatformCapacityForFreeTier() {
        // Setup Free User
        when(streamJobRepo.countByUserEmailAndStatusLive("test@example.com")).thenReturn(0L);
        when(userService.getOrCreateUser("test@example.com")).thenReturn(testUser);
        
        // Mock global capacity exceeded
        when(appConfigService.getGlobalStreamLimit()).thenReturn(10);
        when(streamJobRepo.countByStatusLive()).thenReturn(10L); // Max capacity reached

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> {
            streamService.startStream(
                "test@example.com",
                List.of("dest-key-123"),
                "video.mp4",
                null, "1.0", 1, null, false, "original", 720,
                "Title", "Desc", "public", false, null, false
            );
        });

        assertEquals("Platform capacity reached. Please upgrade to Essentials.", exception.getMessage());
    }

    @Test
    void testStartStreamChecksCreditLimitForFreeTier() {
        when(streamJobRepo.countByUserEmailAndStatusLive("test@example.com")).thenReturn(0L);
        when(userService.getOrCreateUser("test@example.com")).thenReturn(testUser);
        
        when(appConfigService.getGlobalStreamLimit()).thenReturn(10);
        when(streamJobRepo.countByStatusLive()).thenReturn(5L); // Within capacity
        
        // Mock Credit Limit Exceeded
        when(userService.checkCreditLimit("test@example.com")).thenReturn(false);

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> {
            streamService.startStream(
                "test@example.com",
                List.of("dest-key-123"),
                "video.mp4",
                null, "1.0", 1, null, false, "original", 720,
                "Title", "Desc", "public", false, null, false
            );
        });

        assertEquals("Credit limit exceeded.", exception.getMessage());
    }

    @Test
    void testStartStreamSuccessfullyEnqueuesJob() throws Exception {
        when(streamJobRepo.countByUserEmailAndStatusLive("test@example.com")).thenReturn(0L);
        when(userService.getOrCreateUser("test@example.com")).thenReturn(testUser);
        when(appConfigService.getGlobalStreamLimit()).thenReturn(10);
        when(streamJobRepo.countByStatusLive()).thenReturn(5L);
        when(userService.checkCreditLimit("test@example.com")).thenReturn(true);
        when(appConfigService.getCurrentHourlyRate("USD")).thenReturn(new BigDecimal("1.00"));
        
        // Mock saving the job
        StreamJob mockJob = new StreamJob();
        mockJob.setId(100L);
        when(streamJobRepo.save(any(StreamJob.class))).thenReturn(mockJob);
        
        // Mock building JSON
        when(objectMapper.writeValueAsString(anyMap())).thenReturn("{\"spec\":\"test\"}");

        ApiResponse<?> response = streamService.startStream(
                "test@example.com",
                List.of("dest-key-123"),
                "video.mp4",
                null, "1.0", 1, null, false, "original", 720,
                "Title", "Desc", "public", false, null, false
        );

        assertEquals("Stream queued successfully", response.message());
        
        // Verifies queue insertion
        verify(streamJobQueueRepo, times(1)).save(any());
        // Verifies email is sent
        verify(emailService, times(1)).sendStreamStatusEmail(anyString(), anyString(), eq("QUEUED for Processing"), anyString());
    }
}
