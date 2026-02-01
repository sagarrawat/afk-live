package com.afklive.streamer.endpoint;

import com.afklive.streamer.model.SocialChannel;
import com.afklive.streamer.service.ChannelService;
import com.afklive.streamer.service.YouTubeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.services.youtube.model.CommentThreadListResponse;
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

import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
public class CommentControllerTest {

    private MockMvc mockMvc;

    @Mock
    private YouTubeService youTubeService;

    @Mock
    private ChannelService channelService;

    @InjectMocks
    private CommentController commentController;

    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    public void setup() {
        mockMvc = MockMvcBuilders.standaloneSetup(commentController).build();
    }

    @Test
    public void testGetComments() throws Exception {
        Principal mockPrincipal = Mockito.mock(Principal.class);
        Mockito.when(mockPrincipal.getName()).thenReturn("test@example.com");

        SocialChannel channel = new SocialChannel();
        channel.setPlatform("YOUTUBE");
        channel.setCredentialId("credId");
        Mockito.when(channelService.getChannels("test@example.com")).thenReturn(List.of(channel));

        CommentThreadListResponse response = new CommentThreadListResponse();
        response.setItems(Collections.emptyList());
        Mockito.when(youTubeService.getCommentThreads("credId")).thenReturn(response);

        mockMvc.perform(get("/api/comments").principal(mockPrincipal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    public void testReplyToComment() throws Exception {
        Principal mockPrincipal = Mockito.mock(Principal.class);
        Mockito.when(mockPrincipal.getName()).thenReturn("test@example.com");

        SocialChannel channel = new SocialChannel();
        channel.setPlatform("YOUTUBE");
        channel.setCredentialId("credId");
        Mockito.when(channelService.getChannels("test@example.com")).thenReturn(List.of(channel));

        Map<String, String> request = Map.of("text", "Reply text");

        mockMvc.perform(post("/api/comments/parent123/reply")
                        .principal(mockPrincipal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        Mockito.verify(youTubeService).replyToComment("credId", "parent123", "Reply text");
    }

    @Test
    public void testDeleteComment() throws Exception {
        Principal mockPrincipal = Mockito.mock(Principal.class);
        Mockito.when(mockPrincipal.getName()).thenReturn("test@example.com");

        SocialChannel channel = new SocialChannel();
        channel.setPlatform("YOUTUBE");
        channel.setCredentialId("credId");
        Mockito.when(channelService.getChannels("test@example.com")).thenReturn(List.of(channel));

        mockMvc.perform(delete("/api/comments/comment123").principal(mockPrincipal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        Mockito.verify(youTubeService).deleteComment("credId", "comment123");
    }
}
