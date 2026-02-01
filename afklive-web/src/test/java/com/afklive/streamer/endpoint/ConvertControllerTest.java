package com.afklive.streamer.endpoint;

import com.afklive.streamer.service.VideoConversionService;
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
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
public class ConvertControllerTest {

    private MockMvc mockMvc;

    @Mock
    private VideoConversionService conversionService;

    @InjectMocks
    private ConvertController convertController;

    @BeforeEach
    public void setup() {
        mockMvc = MockMvcBuilders.standaloneSetup(convertController).build();
    }

    @Test
    public void testConvertToShort() throws Exception {
        Principal mockPrincipal = Mockito.mock(Principal.class);
        Mockito.when(mockPrincipal.getName()).thenReturn("test@example.com");

        mockMvc.perform(post("/api/convert/shorts")
                        .param("fileName", "video.mp4")
                        .principal(mockPrincipal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Conversion started"));

        Mockito.verify(conversionService).convertToShort(isNull(), eq("test@example.com"), eq("video.mp4"));
    }

    @Test
    public void testOptimizeVideo() throws Exception {
        Principal mockPrincipal = Mockito.mock(Principal.class);
        Mockito.when(mockPrincipal.getName()).thenReturn("test@example.com");

        mockMvc.perform(post("/api/convert/optimize")
                        .param("fileName", "video.mp4")
                        .param("mode", "landscape")
                        .param("height", "1080")
                        .principal(mockPrincipal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Optimization started"));

        Mockito.verify(conversionService).optimizeVideo(isNull(), eq("test@example.com"), eq("video.mp4"), eq("landscape"), eq(1080));
    }

    @Test
    public void testGetConversionStatus() throws Exception {
        Principal mockPrincipal = Mockito.mock(Principal.class);
        Mockito.when(mockPrincipal.getName()).thenReturn("test@example.com");

        Mockito.when(conversionService.getProgress("test@example.com", "video.mp4")).thenReturn(Optional.of(50));

        mockMvc.perform(get("/api/convert/status")
                        .param("fileName", "video.mp4")
                        .principal(mockPrincipal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.progress").value(50));
    }
}
