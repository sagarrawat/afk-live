package com.afklive.streamer;

import com.afklive.streamer.model.PlanType;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.UserRepository;
import com.afklive.streamer.service.EmailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** UX audit #23: "Contact support" in the app files a ticket that admins see under Support. */
@SpringBootTest(properties = "rate-limiter.account-per-minute=1000")
class SupportTicketTest extends PostgresIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean EmailService email; // same context as AccountSecurityTest

    MockMvc mvc;
    String address;
    Long userId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        address = "help-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        User u = new User(address);
        u.setPlanType(PlanType.FREE);
        userId = users.save(u).getId();
    }

    private Map<String, Object> lastTicket() {
        return jdbc.queryForMap("SELECT category, message, attachment_key, attachment_name FROM support_tickets WHERE user_id = ? ORDER BY id DESC LIMIT 1", userId);
    }

    @Test
    void aTicketWithAScreenshotIsFiled() throws Exception {
        MockMultipartFile shot = new MockMultipartFile("file", "screen.png", "image/png", new byte[]{1, 2, 3});
        mvc.perform(multipart("/api/support/ticket").file(shot).param("category", "Streaming").param("message", "  YouTube shows offline  ")
                        .with(user(address)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        Map<String, Object> t = lastTicket();
        assertThat(t.get("category")).isEqualTo("Streaming");
        assertThat(t.get("message")).isEqualTo("YouTube shows offline");
        assertThat(t.get("attachment_name")).isEqualTo("screen.png");
        assertThat(t.get("attachment_key")).isNotNull();
    }

    @Test
    void anUnknownCategoryIsFiledAsOther() throws Exception {
        mvc.perform(multipart("/api/support/ticket").param("category", "<b>x</b>").param("message", "Help").with(user(address)).with(csrf()))
                .andExpect(status().isOk());
        assertThat(lastTicket().get("category")).isEqualTo("Other");
    }

    @Test
    void emptyOrHugeMessagesAndOtherFilesAreRefusedInPlainWords() throws Exception {
        mvc.perform(multipart("/api/support/ticket").param("category", "Other").param("message", "   ").with(user(address)).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Tell us what happened."));
        mvc.perform(multipart("/api/support/ticket").param("category", "Other").param("message", "x".repeat(5001)).with(user(address)).with(csrf()))
                .andExpect(status().isBadRequest());
        MockMultipartFile script = new MockMultipartFile("file", "run.sh", "text/x-sh", "rm -rf /".getBytes());
        mvc.perform(multipart("/api/support/ticket").file(script).param("category", "Other").param("message", "Help").with(user(address)).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Attach a screenshot (PNG or JPG), a PDF or an MP4 recording."));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM support_tickets WHERE user_id = ?", Integer.class, userId)).isZero();
    }
}
