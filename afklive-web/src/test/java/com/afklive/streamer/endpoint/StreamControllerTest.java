package com.afklive.streamer.endpoint;

import com.afklive.streamer.model.User;
import com.afklive.streamer.service.StreamService;
import com.afklive.streamer.service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.security.Principal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class StreamControllerTest {

    @Mock
    private StreamService streamService;

    @Mock
    private UserService userService;

    @InjectMocks
    private StreamController streamController;

    @Test
    void testStopStreamSpecific() {
        Principal principal = mock(Principal.class);
        when(principal.getName()).thenReturn("test@example.com");

        streamController.stop(principal, 123L);

        verify(streamService, times(1)).stopStream(123L, "test@example.com");
    }

    @Test
    void testStopAllStreams() {
        Principal principal = mock(Principal.class);
        when(principal.getName()).thenReturn("test@example.com");

        streamController.stop(principal, null);

        verify(streamService, times(1)).stopAllStreams("test@example.com");
    }
}
