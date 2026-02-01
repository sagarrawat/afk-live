package com.afklive.streamer.endpoint;

import com.afklive.streamer.model.ScheduledVideo;
import com.afklive.streamer.repository.ScheduledVideoRepository;
import com.afklive.streamer.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.security.Principal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
public class LibraryControllerTest {

    private MockMvc mockMvc;

    @Mock
    private FileStorageService storageService;
    @Mock
    private ScheduledVideoRepository repository;
    @Mock
    private UserService userService;
    @Mock
    private AiService aiService;
    @Mock
    private ImportService importService;
    @Mock
    private VideoConversionService conversionService;

    @InjectMocks
    private LibraryController libraryController;

    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    public void setup() {
        mockMvc = MockMvcBuilders.standaloneSetup(libraryController).build();
    }

    @Test
    public void testImportFromYouTube() throws Exception {
        Principal mockPrincipal = Mockito.mock(Principal.class);
        Mockito.when(mockPrincipal.getName()).thenReturn("test@example.com");

        Map<String, String> request = Map.of("url", "http://youtube.com/watch?v=123");

        mockMvc.perform(post("/api/library/import-youtube")
                        .principal(mockPrincipal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Import started. Check library shortly."));

        Mockito.verify(importService).downloadFromYouTube("http://youtube.com/watch?v=123", "test@example.com");
    }

    @Test
    public void testGetLibraryVideos() throws Exception {
        Principal mockPrincipal = Mockito.mock(Principal.class);
        Mockito.when(mockPrincipal.getName()).thenReturn("test@example.com");

        Mockito.when(repository.findByUsername("test@example.com")).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/library").principal(mockPrincipal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    public void testDeleteLibraryVideo() throws Exception {
        Principal mockPrincipal = Mockito.mock(Principal.class);
        Mockito.when(mockPrincipal.getName()).thenReturn("test@example.com");

        ScheduledVideo video = new ScheduledVideo();
        video.setUsername("test@example.com");
        video.setS3Key("key");
        video.setFileSize(100L);

        Mockito.when(repository.findById(1L)).thenReturn(Optional.of(video));

        mockMvc.perform(delete("/api/library/1").principal(mockPrincipal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Video deleted"));

        Mockito.verify(storageService).deleteFile("key");
        Mockito.verify(repository).delete(video);
        Mockito.verify(userService).updateStorageUsage("test@example.com", -100L);
    }
}
