package com.afklive.streamer;

import com.afklive.streamer.model.PlanType;
import com.afklive.streamer.model.StreamDestination;
import com.afklive.streamer.model.User;
import com.afklive.streamer.model.StreamJob;
import com.afklive.streamer.repository.StreamDestinationRepository;
import com.afklive.streamer.repository.StreamJobRepository;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The app's shell: the SPA routes, /api/me (ON-3) and destination keys never leaving the server. */
@SpringBootTest
class AppShellTest extends PostgresIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired StreamDestinationRepository destinations;
    @Autowired StreamJobRepository jobs;

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
        // One product: the old app and old addresses lead into the new app and site.
        mvc.perform(get("/classic").with(user(email).roles("USER"))).andExpect(redirectedUrl("/studio"));
        mvc.perform(get("/classic.html")).andExpect(redirectedUrl("/studio"));
        mvc.perform(get("/privacy")).andExpect(forwardedUrl("/site/privacy.html"));
        mvc.perform(get("/terms")).andExpect(forwardedUrl("/site/terms.html"));
        mvc.perform(get("/terms.html")).andExpect(redirectedUrl("/terms"));
    }

    @Test
    void publicSiteAndSignInPages() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk()).andExpect(forwardedUrl("/site/index.html"));
        mvc.perform(get("/pricing")).andExpect(status().isOk()).andExpect(forwardedUrl("/site/pricing.html"));
        mvc.perform(get("/features")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/for/lofi")).andExpect(status().isOk()).andExpect(forwardedUrl("/site/for-lofi.html")); // MK-3
        mvc.perform(get("/for/../../etc")).andExpect(status().is4xxClientError());
        mvc.perform(get("/for/nope")).andExpect(status().isNotFound());
        // A person gets the site's 404 page; an API caller still gets JSON.
        mvc.perform(get("/for/nope").accept(MediaType.TEXT_HTML)).andExpect(status().isNotFound())
                .andExpect(content().string(containsString("This page is off air.")));
        mvc.perform(get("/api/streams/999999").with(user(email).roles("USER")).accept(MediaType.TEXT_HTML, MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound()).andExpect(content().string(containsString("\"message\"")));
        mvc.perform(get("/sitemap.xml")).andExpect(status().isOk()).andExpect(content().string(containsString("https://afklive.in/for/lofi")));
        mvc.perform(get("/robots.txt")).andExpect(status().isOk());
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
    void helpCentreIsPublicAndEveryArticleIsInTheSitemap() throws Exception {
        // MK-4: /help and each article, signed out; unknown or odd slugs are a 404.
        mvc.perform(get("/help")).andExpect(status().isOk()).andExpect(forwardedUrl("/site/help.html"));
        mvc.perform(get("/help/youtube-stream-key")).andExpect(status().isOk()).andExpect(forwardedUrl("/site/help-youtube-stream-key.html"));
        mvc.perform(get("/help/nope")).andExpect(status().isNotFound());
        mvc.perform(get("/help/..%2F..%2Fetc")).andExpect(status().is4xxClientError());
        String sitemap = mvc.perform(get("/sitemap.xml")).andReturn().getResponse().getContentAsString();
        java.util.Set<String> listed = new java.util.HashSet<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("https://afklive\\.in/help/([a-z0-9-]+)<").matcher(sitemap);
        while (m.find()) {
            listed.add(m.group(1));
        }
        org.assertj.core.api.Assertions.assertThat(listed).isEqualTo(com.afklive.streamer.endpoint.HomeController.HELP_ARTICLES);
        org.assertj.core.api.Assertions.assertThat(sitemap).contains("https://afklive.in/help<");
        for (String slug : listed) {
            mvc.perform(get("/help/" + slug)).andExpect(status().isOk()).andExpect(forwardedUrl("/site/help-" + slug + ".html"));
        }
    }

    @Test
    void tipsAreDismissedForTheAccountAndGoLivesAreCounted() throws Exception {
        // GL-6: "What you'll see on YouTube" is dismissed once for every device, not per browser.
        mvc.perform(get("/api/me").with(user(email).roles("USER")))
                .andExpect(jsonPath("$.dismissedHints").isEmpty())
                .andExpect(jsonPath("$.goLives").value(0));
        for (int i = 0; i < 2; i++) {
            mvc.perform(patch("/api/me").with(user(email).roles("USER")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"dismissHint\":\"youtube-side\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.dismissedHints.length()").value(1))
                    .andExpect(jsonPath("$.dismissedHints[0]").value("youtube-side"));
        }
        mvc.perform(patch("/api/me").with(user(email).roles("USER")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dismissHint\":\"anything-else\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/me").with(user(email).roles("USER"))).andExpect(jsonPath("$.dismissedHints[0]").value("youtube-side"));
        // The card also shows for the whole of a first go-live: every go-live is a job.
        User u = users.findByEmail(email).orElseThrow();
        jobs.save(new StreamJob(u, "k", null, null, null, false, 0, "First", null, null, java.time.ZonedDateTime.now(), "YouTube", false));
        mvc.perform(get("/api/me").with(user(email).roles("USER"))).andExpect(jsonPath("$.goLives").value(1));
    }

    @Test
    void brandIconsAndTheAppManifestAreReachableSignedOut() throws Exception {
        // Browsers fetch these without a session (tab icon, home-screen install, link previews).
        mvc.perform(get("/favicon.ico")).andExpect(status().isOk());
        // Built by the UI build (absent with -DskipUi), so check it isn't sent to sign-in rather than that it exists.
        for (String path : new String[]{"/site/brand/og.png", "/app/manifest.webmanifest", "/app/icon-192.png"}) {
            int code = mvc.perform(get(path)).andReturn().getResponse().getStatus();
            org.assertj.core.api.Assertions.assertThat(code).as(path).isIn(200, 404);
        }
        mvc.perform(get("/login")).andExpect(content().string(containsString("/site/brand/favicon.svg")));
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

    @Test
    void destinationsAreRecognisedByTheirServer() throws Exception {
        User u = users.findByEmail(email).orElseThrow();
        String[][] cases = {
                {"rtmps://fa723fc1b171.global-contribute.live-video.net/app/sk_us-west_1", "KICK"},
                {"rtmps://edgetee-upload-bom1-1.xx.fbcdn.net:443/rtmp/17912345", "INSTAGRAM"},
                {"rtmp://push-rtmp-l11-va01.tiktokcdn.com/game/stream-123", "TIKTOK"},
                {"rtmp://live.rumble.com/live/r-abc123", "RUMBLE"},
                {"rtmp://live.example.com/app/key-0001", "CUSTOM"},
        };
        for (String[] c : cases) {
            destinations.save(new StreamDestination(c[1], c[0], "RTMP", u));
        }
        var result = mvc.perform(get("/api/destinations").with(user(email).roles("USER"))).andExpect(status().isOk());
        for (int i = 0; i < cases.length; i++) {
            result.andExpect(jsonPath("$[" + i + "].platform").value(cases[i][1]));
        }
    }
}
