package com.afklive.streamer.endpoint;

import com.afklive.streamer.model.StreamJob;
import com.afklive.streamer.repository.ScheduledStreamRepository;
import com.afklive.streamer.repository.ScheduledVideoRepository;
import com.afklive.streamer.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.security.Principal;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
public class StreamControllerTest {

    private MockMvc mockMvc;

    @Mock
    private StreamService streamService;
    @Mock
    private FileUploadService fileUploadService;
    @Mock
    private VideoConversionService videoConversionService;
    @Mock
    private UserFileService userFileService;
    @Mock
    private StreamManagerService streamManager;
    @Mock
    private ScheduledStreamRepository scheduledStreamRepo;
    @Mock
    private ScheduledVideoRepository scheduledVideoRepository;
    @Mock
    private UserService userService;
    @Mock
    private YouTubeService youTubeService;
    @Mock
    private ChannelService channelService;

    @InjectMocks
    private StreamController streamController;

    @BeforeEach
    public void setup() {
        mockMvc = MockMvcBuilders.standaloneSetup(streamController).build();
    }

    @Test
    public void testGetStatus() throws Exception {
        Principal mockPrincipal = Mockito.mock(Principal.class);
        Mockito.when(mockPrincipal.getName()).thenReturn("test@example.com");

        Mockito.when(streamService.getActiveStreams("test@example.com")).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/status").principal(mockPrincipal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("OFFLINE"));
    }

    @Test
    public void testStopStream() throws Exception {
        Principal mockPrincipal = Mockito.mock(Principal.class);
        Mockito.when(mockPrincipal.getName()).thenReturn("test@example.com");

        mockMvc.perform(post("/api/stop").principal(mockPrincipal))
                .andExpect(status().isOk());

        Mockito.verify(streamManager).endStream("test@example.com");
        Mockito.verify(streamService).stopAllStreams("test@example.com");
    }
}
