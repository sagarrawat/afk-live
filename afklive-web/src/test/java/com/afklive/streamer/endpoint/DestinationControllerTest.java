package com.afklive.streamer.endpoint;

import com.afklive.streamer.model.StreamDestination;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.StreamDestinationRepository;
import com.afklive.streamer.service.UserService;
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
public class DestinationControllerTest {

    private MockMvc mockMvc;

    @Mock
    private StreamDestinationRepository destinationRepository;

    @Mock
    private UserService userService;

    @InjectMocks
    private DestinationController destinationController;

    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    public void setup() {
        mockMvc = MockMvcBuilders.standaloneSetup(destinationController).build();
    }

    @Test
    public void testGetDestinations() throws Exception {
        Principal mockPrincipal = Mockito.mock(Principal.class);
        Mockito.when(mockPrincipal.getName()).thenReturn("test@example.com");

        User user = new User();
        Mockito.when(userService.getOrCreateUser("test@example.com")).thenReturn(user);
        Mockito.when(destinationRepository.findByUser(user)).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/destinations").principal(mockPrincipal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    public void testCreateDestination() throws Exception {
        Principal mockPrincipal = Mockito.mock(Principal.class);
        Mockito.when(mockPrincipal.getName()).thenReturn("test@example.com");

        User user = new User();
        Mockito.when(userService.getOrCreateUser("test@example.com")).thenReturn(user);

        StreamDestination dest = new StreamDestination();
        dest.setId(1L);
        dest.setName("Twitch");
        Mockito.when(destinationRepository.save(any(StreamDestination.class))).thenReturn(dest);

        Map<String, Object> request = Map.of("name", "Twitch", "key", "key123");

        mockMvc.perform(post("/api/destinations")
                        .principal(mockPrincipal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    public void testDeleteDestination() throws Exception {
        Principal mockPrincipal = Mockito.mock(Principal.class);
        Mockito.when(mockPrincipal.getName()).thenReturn("test@example.com");

        User user = new User();
        user.setUsername("test@example.com");
        StreamDestination dest = new StreamDestination();
        dest.setUser(user);

        Mockito.when(destinationRepository.findById(1L)).thenReturn(Optional.of(dest));

        mockMvc.perform(delete("/api/destinations/1").principal(mockPrincipal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        Mockito.verify(destinationRepository).delete(dest);
    }
}
