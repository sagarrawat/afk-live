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
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.security.Principal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
public class DestinationControllerTest {

    private MockMvc mockMvc;

    @Mock
    private StreamDestinationRepository destinationRepository;

    @Mock
    private UserService userService;

    @InjectMocks
    private DestinationController destinationController;

    @BeforeEach
    public void setup() {
        mockMvc = MockMvcBuilders.standaloneSetup(destinationController).build();
    }

    @Test
    public void testCreateDestination_DuplicateKey_ReturnsBadRequest() throws Exception {
        User user = new User();
        user.setUsername("testuser");

        when(userService.getOrCreateUser(anyString())).thenReturn(user);

        // Simulate that the key already exists
        StreamDestination existingDest = new StreamDestination("Existing", "dup-key", "RTMP", user);
        when(destinationRepository.findByStreamKeyAndUser("dup-key", user)).thenReturn(List.of(existingDest));

        Map<String, Object> body = new HashMap<>();
        body.put("name", "New Dest");
        body.put("key", "dup-key");
        body.put("type", "RTMP");

        Principal principal = () -> "testuser";

        mockMvc.perform(post("/api/destinations")
                .principal(principal)
                .contentType(MediaType.APPLICATION_JSON)
                .content(new ObjectMapper().writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }
}
