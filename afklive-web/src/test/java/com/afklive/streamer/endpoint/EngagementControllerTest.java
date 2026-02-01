package com.afklive.streamer.endpoint;

import com.afklive.streamer.model.EngagementActivity;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.EngagementActivityRepository;
import com.afklive.streamer.service.AiService;
import com.afklive.streamer.service.ChannelService;
import com.afklive.streamer.service.UserService;
import com.afklive.streamer.service.YouTubeService;
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
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
public class EngagementControllerTest {

    private MockMvc mockMvc;

    @Mock
    private UserService userService;

    @Mock
    private YouTubeService youTubeService;

    @Mock
    private AiService aiService;

    @Mock
    private EngagementActivityRepository activityRepository;

    @Mock
    private ChannelService channelService;

    @InjectMocks
    private EngagementController engagementController;

    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    public void setup() {
        mockMvc = MockMvcBuilders.standaloneSetup(engagementController).build();
    }

    @Test
    public void testGetUnrepliedComments() throws Exception {
        Principal mockPrincipal = Mockito.mock(Principal.class);
        Mockito.when(mockPrincipal.getName()).thenReturn("test@example.com");

        User user = new User();
        user.setChannels(Collections.emptyList());
        Mockito.when(userService.getOrCreateUser("test@example.com")).thenReturn(user);

        mockMvc.perform(get("/api/engagement/unreplied").principal(mockPrincipal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    public void testGetReplySuggestions() throws Exception {
        Principal mockPrincipal = Mockito.mock(Principal.class);
        Mockito.when(mockPrincipal.getName()).thenReturn("test@example.com");

        Mockito.when(aiService.generateReplySuggestions("comment")).thenReturn(Collections.singletonList("Suggestion"));

        mockMvc.perform(get("/api/engagement/suggest")
                        .param("text", "comment")
                        .principal(mockPrincipal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suggestions[0]").value("Suggestion"));
    }

    @Test
    public void testUpdateSettings() throws Exception {
        Principal mockPrincipal = Mockito.mock(Principal.class);
        Mockito.when(mockPrincipal.getName()).thenReturn("test@example.com");

        User user = new User();
        Mockito.when(userService.getOrCreateUser("test@example.com")).thenReturn(user);

        Map<String, Object> request = Map.of("autoReplyEnabled", true);

        mockMvc.perform(post("/api/engagement/settings")
                        .principal(mockPrincipal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        Mockito.verify(userService).saveUser(user);
    }

    @Test
    public void testRevertAction() throws Exception {
        Principal mockPrincipal = Mockito.mock(Principal.class);
        Mockito.when(mockPrincipal.getName()).thenReturn("test@example.com");

        EngagementActivity activity = new EngagementActivity();
        activity.setUsername("test@example.com");
        activity.setActionType("REPLY");
        activity.setCreatedCommentId("comment123");

        Mockito.when(activityRepository.findById(1L)).thenReturn(Optional.of(activity));
        Mockito.when(channelService.getCredentialId("test@example.com")).thenReturn("credId");

        mockMvc.perform(post("/api/engagement/revert/1").principal(mockPrincipal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        Mockito.verify(youTubeService).deleteComment("credId", "comment123");
    }
}
