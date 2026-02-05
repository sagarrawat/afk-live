package com.afklive.streamer.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AiServiceTest {

    @Mock
    private RestTemplate restTemplate;

    private AiService aiService;

    @BeforeEach
    void setUp() {
        // Initialize with a dummy key to enable AI logic
        aiService = new AiService("dummy-key", restTemplate);
    }

    @Test
    void generateTwitterStyleReply_shouldReturnNull_WhenApiFails() {
        // Arrange
        when(restTemplate.postForObject(anyString(), any(), eq(java.util.Map.class)))
                .thenThrow(new RestClientException("API Error"));

        // Act
        String result = aiService.generateTwitterStyleReply("Hello", "Stream Context");

        // Assert
        assertNull(result, "Should return null when API fails");
    }

    @Test
    void generateTitle_shouldReturnFallback_WhenApiFails() {
        // Arrange
        when(restTemplate.postForObject(anyString(), any(), eq(java.util.Map.class)))
                .thenThrow(new RestClientException("API Error"));

        // Act
        String result = aiService.generateTitle("Test Context");

        // Assert
        assertNotNull(result);
        assertFalse(result.contains("Generate exactly ONE"), "Should not return the prompt");
        // Check if it returned one of the fallback templates
        boolean isFallback = result.startsWith("Amazing Video about") ||
                             result.startsWith("Why ") ||
                             result.startsWith("The Ultimate Guide to") ||
                             result.startsWith("Unboxing") ||
                             result.endsWith("Explained in 5 Minutes");
        assertTrue(isFallback, "Should return a fallback title");
    }

    @Test
    void analyzeSentiment_shouldReturnNeutral_WhenApiFails() {
         // Arrange
        when(restTemplate.postForObject(anyString(), any(), eq(java.util.Map.class)))
                .thenThrow(new RestClientException("API Error"));

        // Act
        String result = aiService.analyzeSentiment("This is a test comment");

        // Assert
        assertEquals("NEUTRAL", result);
    }
}
