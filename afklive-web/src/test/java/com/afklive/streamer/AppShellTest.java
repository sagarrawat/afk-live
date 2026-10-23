package com.afklive.streamer;

import com.afklive.streamer.model.PlanType;
import com.afklive.streamer.model.StreamDestination;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.StreamDestinationRepository;
import com.afklive.streamer.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The app's shell: the SPA routes, /api/me (ON-3) and destination keys never leaving the server. */
@SpringBootTest
class AppShellTest extends PostgresIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired StreamDestinationRepository destinations;

    MockMvc mvc;
    String email;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        email = "shell-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        User u = new User(email);
        u.setPlanType(PlanType.ESSENTIALS);
        users.save(u);
    }

    @Test
    void appRoutesAllLoadTheSinglePageApp() throws Exception {
        for (String path : new String[]{"/studio", "/studio/streams/5/playlist", "/studio/settings/billing"}) {
            mvc.perform(get(path).with(user(email).roles("USER")))
                    .andExpect(status().isOk())
                    .andExpect(forwardedUrl("/app/index.html"));
        }
        mvc.perform(get("/studio/library")).andExpect(status().is3xxRedirection()); // sign in first
        mvc.perform(get("/studio").with(user("ops@example.com").roles("ADMIN"))).andExpect(status().isOk());
        mvc.perform(get("/classic").with(user(email).roles("USER"))).andExpect(forwardedUrl("/classic.html"));
    }

    @Test
    void publicSiteAndSignInPages() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk()).andExpect(forwardedUrl("/site/index.html"));
        mvc.perform(get("/pricing")).andExpect(status().isOk()).andExpect(forwardedUrl("/site/pricing.html"));
        mvc.perform(get("/features")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/").with(user(email).roles("USER")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/studio"));
        mvc.perform(get("/").with(user("ops@example.com").roles("ADMIN")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/studio/admin"));
        for (String page : new String[]{"/login", "/register", "/forgot-password"}) {
            mvc.perform(get(page))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("/site/assets/site.css")))
                    .andExpect(content().string(containsString("name=\"_csrf\"")));
        }
        mvc.perform(get("/login").param("error", ""))
                .andExpect(content().string(containsString("match. Try again")));
    }

    @Test
    void meReturnsThePlanAndSavesPreferences() throws Exception {
        mvc.perform(get("/api/me").with(user(email).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.plan.id").value("ESSENTIALS"))
                .andExpect(jsonPath("$.plan.maxHeight").value(1080))
                .andExpect(jsonPath("$.onboarded").value(false))
                .andExpect(jsonPath("$.timeZone").doesNotExist());

        mvc.perform(patch("/api/me").with(user(email).roles("USER")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"timeZone\":\"Asia/Kolkata\",\"currency\":\"inr\",\"onboarded\":true,\"name\":\"Aarti\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timeZone").value("Asia/Kolkata"))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.onboarded").value(true))
                .andExpect(jsonPath("$.name").value("Aarti"));

        mvc.perform(patch("/api/me").with(user(email).roles("USER")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"timeZone\":\"Mars/Olympus\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(patch("/api/me").with(user(email).roles("USER")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency\":\"EUR\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void destinationKeysAreNeverSentToTheBrowser() throws Exception {
        User u = users.findByEmail(email).orElseThrow();
        destinations.save(new StreamDestination("YouTube", "rtmp://a.rtmp.youtube.com/live2/abcd-efgh-ijkl-8f2k", "RTMP", u));
        destinations.save(new StreamDestination("Bare key", "wxyz-1234-5678-9a1c", "RTMP", u));
        mvc.perform(get("/api/destinations").with(user(email).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("abcd-efgh"))))
                .andExpect(content().string(not(containsString("wxyz-1234"))))
                .andExpect(content().string(not(containsString("streamKey"))))
                .andExpect(jsonPath("$[0].platform").value("YOUTUBE"))
                .andExpect(jsonPath("$[0].server").value("rtmp://a.rtmp.youtube.com/live2"))
                .andExpect(jsonPath("$[0].keyHint").value("••••8f2k"))
                .andExpect(jsonPath("$[1].keyHint").value("••••9a1c"))
                .andExpect(jsonPath("$[1].platform").value("YOUTUBE"));
    }
}
