package com.afklive.streamer.endpoint;

import com.afklive.streamer.model.SocialChannel;
import com.afklive.streamer.service.ChannelService;
import com.afklive.streamer.service.YouTubeService;
import com.google.api.services.youtubeAnalytics.v2.model.QueryResponse;
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

import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
public class AnalyticsControllerTest {

    private MockMvc mockMvc;

    @Mock
    private YouTubeService youTubeService;

    @Mock
    private ChannelService channelService;

    @InjectMocks
    private AnalyticsController analyticsController;

    @BeforeEach
    public void setup() {
        mockMvc = MockMvcBuilders.standaloneSetup(analyticsController).build();
    }

    @Test
    public void testGetAnalytics() throws Exception {
        Principal mockPrincipal = Mockito.mock(Principal.class);
        Mockito.when(mockPrincipal.getName()).thenReturn("test@example.com");

        SocialChannel channel = new SocialChannel();
        channel.setPlatform("YOUTUBE");
        channel.setCredentialId("credId");
        Mockito.when(channelService.getChannels("test@example.com")).thenReturn(List.of(channel));

        QueryResponse mockResponse = new QueryResponse();
        // date, views, subs, watchTime
        mockResponse.setRows(List.of(List.of("2023-01-01", 100, 10, 500)));
        Mockito.when(youTubeService.getChannelAnalytics(anyString(), anyString(), anyString()))
                .thenReturn(mockResponse);

        mockMvc.perform(get("/api/analytics").principal(mockPrincipal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.totalViews").value(100))
                .andExpect(jsonPath("$.summary.totalSubs").value(10))
                .andExpect(jsonPath("$.summary.totalWatchTime").value(500));
    }

    @Test
    public void testGetAnalyticsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/analytics"))
                .andExpect(status().isUnauthorized());
    }
}
