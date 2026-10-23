package com.afklive.streamer;

import com.afklive.streamer.model.PlanType;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The /api/streams HTTP surface through the real security chain. */
@SpringBootTest
class LiveStreamControllerTest extends PostgresIntegrationTest {

    private static final String EMAIL = "streams-api@example.com";
    private static final String OTHER = "streams-api-other@example.com";

    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        for (String e : new String[]{EMAIL, OTHER}) {
            if (users.findByEmail(e).isEmpty()) {
                User u = new User(e);
                u.setPlanType(PlanType.ESSENTIALS);
                users.save(u);
            }
        }
    }

    @Test
    void signInIsRequired() throws Exception {
        mvc.perform(get("/api/streams")).andExpect(status().is3xxRedirection());
    }

    @Test
    void createConfigureAndAttemptToGoLive() throws Exception {
        String body = mvc.perform(post("/api/streams").with(user(EMAIL).roles("USER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Chill beats\",\"qualityHeight\":1080}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Chill beats"))
                .andExpect(jsonPath("$.live.state").value("DRAFT"))
                .andExpect(jsonPath("$.scenes.length()").value(4))
                .andReturn().getResponse().getContentAsString();
        long id = Long.parseLong(body.replaceAll(".*?\"id\":(\\d+).*", "$1"));

        // Nothing to play and nowhere to send it: refused with every check for the UI.
        mvc.perform(post("/api/streams/" + id + "/live").with(user(EMAIL).roles("USER")).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.preflight.ok").value(false))
                .andExpect(jsonPath("$.preflight.checks[?(@.id=='playlist')].fix").value("playlist"));

        // Someone else's stream is simply not found.
        mvc.perform(get("/api/streams/" + id).with(user(OTHER).roles("USER")))
                .andExpect(status().isNotFound());

        mvc.perform(post("/api/streams").with(user(EMAIL).roles("USER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"4K\",\"qualityHeight\":2160}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("1080p")));
    }
}
