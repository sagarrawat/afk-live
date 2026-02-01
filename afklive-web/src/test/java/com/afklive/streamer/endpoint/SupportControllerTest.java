package com.afklive.streamer.endpoint;

import com.afklive.streamer.service.EmailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.multipart.MultipartFile;

import java.security.Principal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
public class SupportControllerTest {

    private MockMvc mockMvc;

    @Mock
    private EmailService emailService;

    @InjectMocks
    private SupportController supportController;

    @BeforeEach
    public void setup() {
        mockMvc = MockMvcBuilders.standaloneSetup(supportController).build();
    }

    @Test
    public void testSubmitTicket() throws Exception {
        Principal mockPrincipal = Mockito.mock(Principal.class);
        Mockito.when(mockPrincipal.getName()).thenReturn("test@example.com");

        mockMvc.perform(multipart("/api/support/ticket")
                        .param("category", "General")
                        .param("message", "Help me")
                        .principal(mockPrincipal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        Mockito.verify(emailService).sendSupportTicket(eq("test@example.com"), eq("General"), eq("Help me"), any());
    }
}
