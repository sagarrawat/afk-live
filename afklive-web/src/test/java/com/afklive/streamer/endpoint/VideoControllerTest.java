package com.afklive.streamer.endpoint;

import com.afklive.streamer.model.ScheduledVideo;
import com.afklive.streamer.repository.ScheduledVideoRepository;
import com.afklive.streamer.service.AudioService;
import com.afklive.streamer.service.FileStorageService;
import com.afklive.streamer.service.UserService;
import com.afklive.streamer.service.YouTubeService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
public class VideoControllerTest {

    private MockMvc mockMvc;

    @Mock
    private FileStorageService storageService;
    @Mock
    private ScheduledVideoRepository repository;
    @Mock
    private YouTubeService youTubeService;
    @Mock
    private UserService userService;
    @Mock
    private AudioService audioService;

    @InjectMocks
    private VideoController videoController;

    @BeforeEach
    public void setup() {
        mockMvc = MockMvcBuilders.standaloneSetup(videoController).build();
    }

    @Test
    public void testGetVideoCategories() throws Exception {
        Principal mockPrincipal = Mockito.mock(Principal.class);
        Mockito.when(mockPrincipal.getName()).thenReturn("test@example.com");

        Mockito.when(youTubeService.getVideoCategories("test@example.com", "US")).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/youtube/categories").principal(mockPrincipal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    public void testGetScheduledVideos() throws Exception {
        Principal mockPrincipal = Mockito.mock(Principal.class);
        Mockito.when(mockPrincipal.getName()).thenReturn("test@example.com");

        Mockito.when(repository.findByUsername("test@example.com")).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/videos").principal(mockPrincipal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    public void testGetYouTubeStatus() throws Exception {
        Principal mockPrincipal = Mockito.mock(Principal.class);
        Mockito.when(mockPrincipal.getName()).thenReturn("test@example.com");

        Mockito.when(youTubeService.isConnected("test@example.com")).thenReturn(true);

        mockMvc.perform(get("/api/youtube/status").principal(mockPrincipal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connected").value(true));
    }
}
