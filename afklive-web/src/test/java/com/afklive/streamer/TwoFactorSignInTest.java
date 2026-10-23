package com.afklive.streamer;

import com.afklive.streamer.model.PlanType;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.UserRepository;
import com.afklive.streamer.security.Totp;
import com.afklive.streamer.service.TwoFactorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** ON-6: two-step sign-in through the real filter chain, form login and database. */
@SpringBootTest
class TwoFactorSignInTest extends PostgresIntegrationTest {

    static final String PASSWORD = "correct horse battery";

    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired TwoFactorService twoFactor;
    @Autowired JdbcTemplate jdbc;

    MockMvc mvc;
    String email;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        email = "tf-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        User u = new User(email);
        u.setPlanType(PlanType.FREE);
        u.setPassword(encoder.encode(PASSWORD));
        users.save(u);
    }

    /** Turns two-step sign-in on through the service; returns the secret and the recovery codes. */
    private record Enabled(String secret, List<String> recoveryCodes) {
    }

    private Enabled enable() {
        TwoFactorService.Setup setup = twoFactor.beginSetup(email);
        List<String> codes = twoFactor.enable(email, Totp.code(setup.secret(), now()));
        return new Enabled(setup.secret(), codes);
    }

    private static long now() {
        return Instant.now().getEpochSecond();
    }

    /** A code that hasn't been used yet (one step ahead, inside the drift window). */
    private static String freshCode(String secret) {
        return Totp.code(secret, now() + Totp.STEP_SECONDS);
    }

    private MockHttpSession signIn() throws Exception {
        MvcResult r = mvc.perform(post("/login").with(csrf()).param("username", email).param("password", PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/studio"))
                .andReturn();
        return (MockHttpSession) r.getRequest().getSession(false);
    }

    @Test
    void withoutTwoStepThePasswordIsEnough() throws Exception {
        MockHttpSession session = signIn();
        mvc.perform(get("/api/me").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.email").value(email));
    }

    @Test
    void passwordAloneCantReachTheAppUntilTheCodeIsEntered() throws Exception {
        Enabled e = enable();
        MockHttpSession session = signIn();

        mvc.perform(get("/api/me").session(session)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/streams").session(session)).andExpect(status().isUnauthorized());
        mvc.perform(get("/studio").session(session)).andExpect(redirectedUrl("/login/2fa"));
        mvc.perform(get("/login/2fa").session(session)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Enter your code")));

        mvc.perform(post("/login/2fa").session(session).with(csrf()).param("code", "000000"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("didn&#39;t match")));
        mvc.perform(get("/api/me").session(session)).andExpect(status().isUnauthorized());

        mvc.perform(post("/login/2fa").session(session).with(csrf()).param("code", freshCode(e.secret())))
                .andExpect(redirectedUrl("/studio"));
        mvc.perform(get("/api/me").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.email").value(email));
    }

    @Test
    void googleSignInAlsoNeedsTheCode() throws Exception {
        enable();
        var google = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login()
                .attributes(a -> a.put("email", email));
        mvc.perform(get("/api/me").with(google)).andExpect(status().isUnauthorized());
        mvc.perform(get("/studio").with(google)).andExpect(redirectedUrl("/login/2fa"));
    }

    @Test
    void aRecoveryCodeWorksOnce() throws Exception {
        Enabled e = enable();
        assertThat(e.recoveryCodes()).hasSize(10).doesNotHaveDuplicates();
        String recovery = e.recoveryCodes().get(3);

        MockHttpSession first = signIn();
        mvc.perform(post("/login/2fa").session(first).with(csrf()).param("code", recovery.toUpperCase()))
                .andExpect(redirectedUrl("/studio"));
        mvc.perform(get("/api/me").session(first)).andExpect(status().isOk());
        assertThat(twoFactor.status(email).recoveryCodesLeft()).isEqualTo(9);

        MockHttpSession second = signIn();
        mvc.perform(post("/login/2fa").session(second).with(csrf()).param("code", recovery)).andExpect(status().isOk());
        mvc.perform(get("/api/me").session(second)).andExpect(status().isUnauthorized());

        // Only hashes are stored.
        List<String> stored = jdbc.queryForList("SELECT code_hash FROM two_factor_recovery_codes c JOIN users u ON u.id = c.user_id "
                + "WHERE u.email = ?", String.class, email);
        assertThat(stored).hasSize(10).noneMatch(h -> e.recoveryCodes().stream().anyMatch(c -> h.contains(c.replace("-", ""))));
    }

    @Test
    void theSecretIsEncryptedAtRest() {
        Enabled e = enable();
        String raw = jdbc.queryForObject("SELECT two_factor_secret FROM users WHERE email = ?", String.class, email);
        assertThat(raw).startsWith("enc:v2:").doesNotContain(e.secret());
    }

    @Test
    void tooManyWrongCodesLockCodeEntry() throws Exception {
        Enabled e = enable();
        MockHttpSession session = signIn();
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/login/2fa").session(session).with(csrf()).param("code", "00000" + i)).andExpect(status().isOk());
        }
        // Even the right code is refused while locked.
        mvc.perform(post("/login/2fa").session(session).with(csrf()).param("code", freshCode(e.secret())))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Too many wrong codes")));
        mvc.perform(get("/api/me").session(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void setupFromSettingsNeedsAConfirmingCodeAndTurningOffNeedsOneToo() throws Exception {
        MockHttpSession session = signIn();
        mvc.perform(get("/api/account/security").session(session)).andExpect(jsonPath("$.twoFactorEnabled").value(false));

        MvcResult setup = mvc.perform(post("/api/account/2fa/setup").session(session).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uri").value(org.hamcrest.Matchers.startsWith("otpauth://totp/AFK%20Live:")))
                .andReturn();
        String secret = com.jayway.jsonpath.JsonPath.read(setup.getResponse().getContentAsString(), "$.secret");
        mvc.perform(get("/api/account/2fa/qr.png").session(session))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.IMAGE_PNG));

        // Not on until confirmed.
        mvc.perform(get("/api/account/security").session(session)).andExpect(jsonPath("$.twoFactorEnabled").value(false));
        mvc.perform(post("/api/account/2fa/enable").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"123\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/account/2fa/enable").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + Totp.code(secret, now()) + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recoveryCodes.length()").value(10));

        // This session confirmed the code, so it stays signed in.
        mvc.perform(get("/api/account/security").session(session))
                .andExpect(jsonPath("$.twoFactorEnabled").value(true))
                .andExpect(jsonPath("$.recoveryCodesLeft").value(10));

        mvc.perform(post("/api/account/2fa/disable").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"111111\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/account/2fa/disable").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + freshCode(secret) + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.twoFactorEnabled").value(false));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM two_factor_recovery_codes c JOIN users u ON u.id = c.user_id WHERE u.email = ?",
                Integer.class, email)).isZero();
    }
}
