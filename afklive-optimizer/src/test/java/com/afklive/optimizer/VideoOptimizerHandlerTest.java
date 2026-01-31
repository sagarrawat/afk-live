package com.afklive.optimizer;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class VideoOptimizerHandlerTest {

    @Mock
    private S3Client s3Client;

    @Mock
    private Context context;

    @Mock
    private LambdaLogger logger;

    private VideoOptimizerHandler handler;

    @BeforeEach
    public void setUp() {
        when(context.getLogger()).thenReturn(logger);

        // Use spy to override executeCommand
        handler = spy(new VideoOptimizerHandler(s3Client, "test-bucket"));
    }

    @Test
    public void testHandleRequest_Success() throws Exception {
        // Arrange
        Map<String, String> event = new HashMap<>();
        event.put("file_name", "test-video.mp4");
        event.put("mode", "landscape");
        event.put("height", "720");
        event.put("username", "testuser");

        // Mock executeCommand to prevent actual FFmpeg execution and create a dummy output file
        doAnswer(invocation -> {
            File output = invocation.getArgument(1);
            Files.writeString(output.toPath(), "dummy video content");
            return null;
        }).when(handler).executeCommand(anyList(), any(File.class));

        // Act
        OptimizationResponse result = handler.handleRequest(event, context);

        // Assert
        assertEquals("success", result.getStatus());
        assertEquals(19L, result.getFileSize());
        assertNotNull(result.getOptimizedKey());
        assertEquals("test-video.mp4", result.getOriginalKey());

        // Verify interactions
        verify(s3Client).getObject(any(GetObjectRequest.class), any(Path.class));
        verify(s3Client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        verify(handler).executeCommand(anyList(), any(File.class));
    }

    @Test
    public void testHandleRequest_WithOutputKey() throws Exception {
        // Arrange
        Map<String, String> event = new HashMap<>();
        event.put("file_name", "test-video.mp4");
        event.put("output_key", "custom-output-key.mp4");
        event.put("username", "testuser");

        doAnswer(invocation -> {
            File output = invocation.getArgument(1);
            Files.writeString(output.toPath(), "dummy content");
            return null;
        }).when(handler).executeCommand(anyList(), any(File.class));

        // Act
        OptimizationResponse result = handler.handleRequest(event, context);

        // Assert
        assertEquals("success", result.getStatus());
        assertEquals("custom-output-key.mp4", result.getOptimizedKey());

        // Verify upload used the custom key
        verify(s3Client).putObject(argThat((PutObjectRequest r) -> r.key().equals("custom-output-key.mp4")), any(RequestBody.class));
    }

    @Test
    public void testHandleRequest_Failure() throws Exception {
        // Arrange
        Map<String, String> event = new HashMap<>();
        event.put("file_name", "fail-video.mp4");

        // Mock executeCommand to throw exception
        doThrow(new RuntimeException("FFmpeg failed")).when(handler).executeCommand(anyList(), any(File.class));

        // Act
        OptimizationResponse result = handler.handleRequest(event, context);

        // Assert
        assertEquals("error", result.getStatus());
        assertTrue(result.getMessage().contains("FFmpeg failed"));

        // Verify interactions
        verify(s3Client).getObject(any(GetObjectRequest.class), any(Path.class));
        // PutObject should NOT be called
        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }
}
