package com.afklive.streamer.endpoint;

import com.afklive.streamer.model.ScheduledVideo;
import com.afklive.streamer.service.AudioService;
import com.afklive.streamer.service.UserFileService;
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
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
public class AudioControllerTest {

    private MockMvc mockMvc;

    @Mock
    private AudioService audioService;

    @Mock
    private UserFileService userFileService;

    @InjectMocks
    private AudioController audioController;

    @BeforeEach
    public void setup() {
        mockMvc = MockMvcBuilders.standaloneSetup(audioController).build();
    }

    @Test
    public void testGetTrending() throws Exception {
        Mockito.when(audioService.getTrendingTracks()).thenReturn(List.of(Map.of("title", "Song 1")));

        mockMvc.perform(get("/api/audio/trending"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("Song 1"));
    }

    @Test
    public void testGetMyLibrary() throws Exception {
        Principal mockPrincipal = Mockito.mock(Principal.class);
        Mockito.when(mockPrincipal.getName()).thenReturn("test@example.com");

        ScheduledVideo video = new ScheduledVideo();
        video.setTitle("song.mp3");
        Mockito.when(userFileService.listAudioFiles("test@example.com")).thenReturn(List.of(video));

        mockMvc.perform(get("/api/audio/my-library").principal(mockPrincipal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("song.mp3"));
    }
}
