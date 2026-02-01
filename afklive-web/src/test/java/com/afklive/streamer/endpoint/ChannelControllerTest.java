package com.afklive.streamer.endpoint;

import com.afklive.streamer.model.SocialChannel;
import com.afklive.streamer.service.ChannelService;
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

import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
public class ChannelControllerTest {

    private MockMvc mockMvc;

    @Mock
    private ChannelService channelService;

    @InjectMocks
    private ChannelController channelController;

    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    public void setup() {
        mockMvc = MockMvcBuilders.standaloneSetup(channelController).build();
    }

    @Test
    public void testGetChannels() throws Exception {
        Principal mockPrincipal = Mockito.mock(Principal.class);
        Mockito.when(mockPrincipal.getName()).thenReturn("test@example.com");

        Mockito.when(channelService.getChannels("test@example.com")).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/channels").principal(mockPrincipal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    public void testAddChannel() throws Exception {
        Principal mockPrincipal = Mockito.mock(Principal.class);
        Mockito.when(mockPrincipal.getName()).thenReturn("test@example.com");

        SocialChannel channel = new SocialChannel();
        channel.setName("Test Channel");
        channel.setPlatform("YOUTUBE");

        Mockito.when(channelService.addChannel(anyString(), anyString(), anyString())).thenReturn(channel);

        Map<String, String> request = Map.of("name", "Test Channel", "platform", "YOUTUBE");

        mockMvc.perform(post("/api/channels")
                        .principal(mockPrincipal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Test Channel"));
    }

    @Test
    public void testRemoveChannel() throws Exception {
        Principal mockPrincipal = Mockito.mock(Principal.class);
        Mockito.when(mockPrincipal.getName()).thenReturn("test@example.com");

        mockMvc.perform(delete("/api/channels/1").principal(mockPrincipal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        Mockito.verify(channelService).removeChannel("test@example.com", 1L);
    }
}
