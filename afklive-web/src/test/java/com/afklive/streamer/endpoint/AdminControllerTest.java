package com.afklive.streamer.endpoint;

import com.afklive.streamer.model.StreamJob;
import com.afklive.streamer.repository.StreamJobRepository;
import com.afklive.streamer.repository.UserRepository;
import com.afklive.streamer.service.StreamService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.view.InternalResourceViewResolver;

import java.util.Collections;
import java.util.Optional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
public class AdminControllerTest {

    private MockMvc mockMvc;

    @Mock
    private UserRepository userRepository;

    @Mock
    private StreamJobRepository streamJobRepo;

    @Mock
    private StreamService streamService;

    @InjectMocks
    private AdminController adminController;

    @BeforeEach
    public void setup() {
        InternalResourceViewResolver viewResolver = new InternalResourceViewResolver();
        viewResolver.setPrefix("/templates/");
        viewResolver.setSuffix(".html");

        mockMvc = MockMvcBuilders.standaloneSetup(adminController)
                .setViewResolvers(viewResolver)
                .build();
    }

    @Test
    public void testAdminDashboard() throws Exception {
        Mockito.when(userRepository.findAll()).thenReturn(Collections.emptyList());
        Mockito.when(streamJobRepo.findAllByIsLiveTrue()).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/admin"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin"))
                .andExpect(model().attributeExists("users"))
                .andExpect(model().attributeExists("activeStreamList"))
                .andExpect(model().attributeExists("stats"));
    }

    @Test
    public void testForceStopStream() throws Exception {
        StreamJob mockJob = new StreamJob();
        mockJob.setUsername("test@example.com");
        Mockito.when(streamJobRepo.findById(1L)).thenReturn(Optional.of(mockJob));

        mockMvc.perform(post("/admin/streams/1/stop"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin"));

        Mockito.verify(streamService).stopStream(1L, "test@example.com");
    }
}
