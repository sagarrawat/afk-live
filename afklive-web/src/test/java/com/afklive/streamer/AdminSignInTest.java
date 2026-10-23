package com.afklive.streamer;

import com.afklive.streamer.model.PlanType;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.UserRepository;
import com.afklive.streamer.security.OAuth2LoginSuccessHandler;
import com.afklive.streamer.security.Totp;
import com.afklive.streamer.service.ChannelService;
import com.afklive.streamer.service.CustomUserDetailsService;
import com.afklive.streamer.service.TwoFactorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

/** Admins land in the app's admin console (/studio/admin) after every kind of sign-in; the old /admin page is gone. */
@SpringBootTest
class AdminSignInTest extends PostgresIntegrationTest {

    static final String PASSWORD = "correct horse battery";

    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired TwoFactorService twoFactor;

    MockMvc mvc;
    String email;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        email = "ops-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        User u = new User(email);
        u.setPlanType(PlanType.FREE);
        u.setPassword(encoder.encode(PASSWORD));
        u.setRole("ROLE_ADMIN");
        users.save(u);
    }

    /** Each test signs in from its own address so the per-IP sign-in limit never interferes. */
    private static RequestPostProcessor from(String ip) {
        return r -> {
            r.setRemoteAddr(ip);
            return r;
        };
    }

    @Test
    void passwordSignInSendsAdminsToTheAdminConsole() throws Exception {
        mvc.perform(post("/login").with(csrf()).with(from("198.51.100.21")).param("username", email).param("password", PASSWORD))
                .andExpect(redirectedUrl("/studio/admin"));
    }

    @Test
    void theTwoStepPageSendsAdminsToTheAdminConsole() throws Exception {
        var setup = twoFactor.beginSetup(email);
        twoFactor.enable(email, Totp.code(setup.secret(), Instant.now().getEpochSecond()));
        MvcResult r = mvc.perform(post("/login").with(csrf()).with(from("198.51.100.22")).param("username", email).param("password", PASSWORD))
                .andExpect(redirectedUrl("/studio/admin"))
                .andReturn();
        MockHttpSession session = (MockHttpSession) r.getRequest().getSession(false);
        String code = Totp.code(setup.secret(), Instant.now().getEpochSecond() + Totp.STEP_SECONDS);
        mvc.perform(post("/login/2fa").session(session).with(csrf()).param("code", code))
                .andExpect(redirectedUrl("/studio/admin"));
    }

    @Test
    void googleSignInSendsAdminsToTheAdminConsoleAndOthersToTheStudio() throws Exception {
        assertThat(googleSignIn("ROLE_ADMIN")).isEqualTo("/studio/admin");
        assertThat(googleSignIn("ROLE_USER")).isEqualTo("/studio");
    }

    private static String googleSignIn(String role) throws Exception {
        UserRepository repo = mock(UserRepository.class);
        User u = new User("g@example.com");
        u.setRole(role);
        when(repo.findByEmail("g@example.com")).thenReturn(Optional.of(u));
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        OAuth2LoginSuccessHandler handler = new OAuth2LoginSuccessHandler(repo, mock(ChannelService.class), mock(CustomUserDetailsService.class));
        var principal = new DefaultOAuth2User(AuthorityUtils.createAuthorityList("OIDC_USER"),
                Map.of("sub", "1", "email", "g@example.com", "name", "G"), "sub");
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        try {
            handler.onAuthenticationSuccess(request, response, new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "google"));
        } finally {
            SecurityContextHolder.clearContext();
        }
        return response.getRedirectedUrl();
    }
}
