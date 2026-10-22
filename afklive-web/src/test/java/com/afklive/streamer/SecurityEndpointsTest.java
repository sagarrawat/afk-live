package com.afklive.streamer;

import com.afklive.streamer.model.PlanType;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end checks of the security fixes through the real filter chain and database.
 */
@SpringBootTest
@TestPropertySource(properties = "rate-limiter.expensive-per-minute=3")
class SecurityEndpointsTest extends PostgresIntegrationTest {

    private static final String EMAIL = "security-test@example.com";

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private com.afklive.streamer.filter.RateLimitFilter rateLimitFilter;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).addFilters(rateLimitFilter).build();
        if (userRepository.findByEmail(EMAIL).isEmpty()) {
            User u = new User(EMAIL);
            u.setPlanType(PlanType.FREE);
            userRepository.save(u);
        }
    }

    @Test
    void planCannotBeUpgradedWithoutPaying() throws Exception {
        int statusCode = mvc.perform(post("/api/pricing/upgrade").with(user(EMAIL).roles("USER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"planId\":\"ESSENTIALS\"}"))
                .andReturn().getResponse().getStatus();
        assertThat(statusCode).isGreaterThanOrEqualTo(400);
        assertThat(userRepository.findByEmail(EMAIL).orElseThrow().getPlanType()).isEqualTo(PlanType.FREE);
    }

    @Test
    void paymentInitiationRequiresSignIn() throws Exception {
        mvc.perform(post("/api/payment/initiate").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"planId\":\"ESSENTIALS\"}"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void paymentInitiationRequiresCsrfToken() throws Exception {
        mvc.perform(post("/api/payment/initiate").with(user(EMAIL).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"planId\":\"ESSENTIALS\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void ffmpegLogsAreAdminOnly() throws Exception {
        mvc.perform(get("/api/logs").with(user(EMAIL).roles("USER"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/logs").with(user("admin@example.com").roles("ADMIN"))).andExpect(status().isOk());
    }

    @Test
    void mockEndpointsAreGoneAndNotPublic() throws Exception {
        mvc.perform(get("/api/mock/analytics")).andExpect(status().is3xxRedirection());
    }

    @Test
    void importRejectsUrlsOutsideTheExpectedSite() throws Exception {
        mvc.perform(post("/api/library/import-youtube").with(user("importer@example.com").roles("USER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"http://169.254.169.254/latest/meta-data\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void expensiveActionsAreRateLimitedPerUser() throws Exception {
        String body = "{\"url\":\"http://127.0.0.1/x\"}";
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/library/import-drive").with(user("ratelimited@example.com").roles("USER")).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content(body));
        }
        mvc.perform(post("/api/library/import-drive").with(user("ratelimited@example.com").roles("USER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));

        // Another user is unaffected.
        mvc.perform(post("/api/library/import-drive").with(user("someone-else@example.com").roles("USER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }
}
