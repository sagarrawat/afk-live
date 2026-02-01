package com.afklive.streamer.endpoint;

import com.afklive.streamer.service.AiService;
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

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
public class AiControllerTest {

    private MockMvc mockMvc;

    @Mock
    private AiService aiService;

    @InjectMocks
    private AiController aiController;

    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    public void setup() {
        mockMvc = MockMvcBuilders.standaloneSetup(aiController).build();
    }

    @Test
    public void testGenerateTitle() throws Exception {
        Mockito.when(aiService.generateTitle("context")).thenReturn("Generated Title");

        Map<String, String> request = Map.of("type", "title", "context", "context");

        mockMvc.perform(post("/api/ai/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("Generated Title"));
    }

    @Test
    public void testGenerateMissingType() throws Exception {
        Map<String, String> request = Map.of("context", "context");

        mockMvc.perform(post("/api/ai/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    public void testGenerateStreamMetadata() throws Exception {
        Mockito.when(aiService.generateStreamMetadata("context")).thenReturn(Map.of("title", "T", "description", "D"));

        Map<String, String> request = Map.of("context", "context");

        mockMvc.perform(post("/api/ai/stream-metadata")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("T"))
                .andExpect(jsonPath("$.description").value("D"));
    }
}
