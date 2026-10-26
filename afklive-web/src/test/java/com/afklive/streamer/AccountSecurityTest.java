package com.afklive.streamer;

import com.afklive.streamer.model.PlanType;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.UserRepository;
import com.afklive.streamer.security.SessionRememberMeServices;
import com.afklive.streamer.service.AccountSecurityService;
import com.afklive.streamer.service.CustomUserDetailsService;
import com.afklive.streamer.service.EmailService;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.rememberme.TokenBasedRememberMeServices;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ON-6 (UX audit #14): change password, change email, sign out other devices, through the real filter
 * chain, Spring Session JDBC and the database.
 */
@SpringBootTest(properties = "rate-limiter.account-per-minute=1000")
class AccountSecurityTest extends PostgresIntegrationTest {

    static final String PASSWORD = "Old-password-1";
    static final String NEW_PASSWORD = "New-password-2!";

    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired FindByIndexNameSessionRepository<? extends Session> sessions;
    @Autowired SessionRememberMeServices rememberMe;
    @Autowired CustomUserDetailsService accounts;
    @MockitoBean EmailService email;

    MockMvc mvc;
    String address;

    @BeforeEach
    void setUp() {
        Filter sessionFilter = context.getBean("springSessionRepositoryFilter", Filter.class);
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(sessionFilter).apply(springSecurity()).build();
        address = "sec-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        User u = new User(address);
        u.setPlanType(PlanType.FREE);
        u.setPassword(encoder.encode(PASSWORD));
        users.save(u);
    }

    private String json(String s) {
        return s.replace('\'', '"');
    }

    /** A server session for {@code who}, as a sign-in on another device would leave. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private String storedSession(String who) {
        FindByIndexNameSessionRepository repo = sessions;
        Session s = (Session) repo.createSession();
        s.setAttribute("SPRING_SECURITY_CONTEXT", new SecurityContextImpl(
                UsernamePasswordAuthenticationToken.authenticated(who, null, AuthorityUtils.createAuthorityList("ROLE_USER"))));
        repo.save(s);
        return s.getId();
    }

    private static Cookie sessionCookie(String id) {
        return new Cookie("SESSION", Base64.getEncoder().encodeToString(id.getBytes()));
    }

    private String storedPassword() {
        return jdbc.queryForObject("SELECT password FROM users WHERE email = ?", String.class, address);
    }

    // ------------------------------------------------------------------ password

    @Test
    void changingThePasswordNeedsTheCurrentOne() throws Exception {
        mvc.perform(post("/api/account/password").with(user(address)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json("{'currentPassword':'wrong','newPassword':'" + NEW_PASSWORD + "'}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("That isn't your current password."));
        assertThat(encoder.matches(PASSWORD, storedPassword())).isTrue();

        mvc.perform(post("/api/account/password").with(user(address)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json("{'currentPassword':'" + PASSWORD + "','newPassword':'short'}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(AccountSecurityService.PASSWORD_RULE_TEXT));

        mvc.perform(post("/api/account/password").with(user(address)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json("{'currentPassword':'" + PASSWORD + "','newPassword':'" + NEW_PASSWORD + "'}")))
                .andExpect(status().isOk());
        assertThat(encoder.matches(NEW_PASSWORD, storedPassword())).isTrue();

        // The new password signs in; the old one doesn't.
        mvc.perform(post("/login").with(csrf()).param("username", address).param("password", PASSWORD))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/login?error"));
        mvc.perform(post("/login").with(csrf()).param("username", address).param("password", NEW_PASSWORD))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/studio"));
    }

    @Test
    void aGoogleOnlyAccountHasNoPasswordToChange() throws Exception {
        jdbc.update("UPDATE users SET password = NULL WHERE email = ?", address);
        mvc.perform(get("/api/account/security").with(oauth2Login().attributes(a -> a.put("email", address))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasPassword").value(false));
        mvc.perform(post("/api/account/password").with(oauth2Login().attributes(a -> a.put("email", address))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json("{'currentPassword':'x','newPassword':'" + NEW_PASSWORD + "'}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(AccountSecurityService.NO_PASSWORD));
    }

    @Test
    void changingThePasswordSignsOutOtherDevicesButNotThisOne() throws Exception {
        String current = storedSession(address);
        String other = storedSession(address);

        mvc.perform(post("/api/account/password").cookie(sessionCookie(current)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json("{'currentPassword':'" + PASSWORD + "','newPassword':'" + NEW_PASSWORD + "'}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.otherSessions").value(1));

        assertThat(sessions.findById(current)).isNotNull();
        assertThat(sessions.findById(other)).isNull();
    }

    // ------------------------------------------------------------------ other devices

    @Test
    void signOutOthersEndsEveryOtherSessionOfThisAccountOnly() throws Exception {
        String current = storedSession(address);
        String phone = storedSession(address);
        String laptop = storedSession(address);
        String someoneElse = storedSession("other-" + address);

        mvc.perform(get("/api/me").cookie(sessionCookie(phone))).andExpect(status().isOk());

        mvc.perform(post("/api/account/sign-out-others").cookie(sessionCookie(current)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.otherSessions").value(2));

        assertThat(sessions.findById(current)).isNotNull();
        assertThat(sessions.findById(phone)).isNull();
        assertThat(sessions.findById(laptop)).isNull();
        assertThat(sessions.findById(someoneElse)).isNotNull();
        mvc.perform(get("/api/me").cookie(sessionCookie(current))).andExpect(status().isOk());
        mvc.perform(get("/api/me").cookie(sessionCookie(phone))).andExpect(status().is3xxRedirection()); // to sign-in
    }

    /** A "keep me signed in" cookie as the password sign-in issues it. */
    private Cookie rememberMeCookie() throws Exception {
        MvcResult r = mvc.perform(post("/login").with(csrf()).param("username", address).param("password", PASSWORD).param("remember-me", "on"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/studio"))
                .andReturn();
        Cookie c = r.getResponse().getCookie("remember-me");
        assertThat(c).isNotNull();
        assertThat(c.getMaxAge()).isPositive();
        return new Cookie("remember-me", c.getValue());
    }

    @Test
    void signOutOthersAlsoEndsKeepMeSignedInCookiesAndRenewsThisDevicesCookie() throws Exception {
        Cookie lostPhone = rememberMeCookie();
        Cookie thisDevice = rememberMeCookie();
        mvc.perform(get("/api/me").cookie(lostPhone)).andExpect(status().isOk()); // works before

        String current = storedSession(address);
        MvcResult r = mvc.perform(post("/api/account/sign-out-others").cookie(sessionCookie(current), thisDevice).with(csrf()))
                .andExpect(status().isOk())
                .andReturn();

        mvc.perform(get("/api/me").cookie(lostPhone)).andExpect(status().is3xxRedirection()); // to sign-in
        Cookie renewed = r.getResponse().getCookie("remember-me");
        assertThat(renewed).isNotNull();
        assertThat(renewed.getMaxAge()).isPositive();
        mvc.perform(get("/api/me").cookie(new Cookie("remember-me", renewed.getValue()))).andExpect(status().isOk());
    }

    @Test
    void cookiesIssuedBeforeThisReleaseStillWork() throws Exception {
        // Spring's stock remember-me with the same key and the plain password hash: what production issued so far.
        TokenBasedRememberMeServices stock = new TokenBasedRememberMeServices(rememberMe.getKey(), accounts);
        stock.setAlwaysRemember(true);
        MockHttpServletResponse res = new MockHttpServletResponse();
        stock.onLoginSuccess(new MockHttpServletRequest(), res,
                UsernamePasswordAuthenticationToken.authenticated(accounts.loadUserByUsername(address), null, AuthorityUtils.createAuthorityList("ROLE_USER")));
        Cookie old = res.getCookie("remember-me");
        assertThat(old).isNotNull();
        mvc.perform(get("/api/me").cookie(new Cookie("remember-me", old.getValue()))).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ email

    @Test
    void whenEmailIsOffChangingEmailSaysItIsNotAvailableYet() throws Exception {
        when(email.isConfigured()).thenReturn(false);
        mvc.perform(get("/api/account/security").with(user(address))).andExpect(jsonPath("$.emailChangeAvailable").value(false));
        mvc.perform(post("/api/account/email").with(user(address)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json("{'newEmail':'new-" + address + "','password':'" + PASSWORD + "'}")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(AccountSecurityService.EMAIL_OFF));
        verify(email, never()).sendEmailChangeLink(anyString(), anyString());
    }

    @Test
    void changingEmailNeedsThePasswordAndAFreeAddress() throws Exception {
        when(email.isConfigured()).thenReturn(true);
        mvc.perform(post("/api/account/email").with(user(address)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json("{'newEmail':'new-" + address + "','password':'wrong'}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("That isn't your current password."));

        String taken = "taken-" + address;
        User t = new User(taken);
        t.setPlanType(PlanType.FREE);
        users.save(t);
        mvc.perform(post("/api/account/email").with(user(address)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json("{'newEmail':'" + taken.toUpperCase() + "','password':'" + PASSWORD + "'}")))
                .andExpect(status().isConflict());
        verify(email, never()).sendEmailChangeLink(anyString(), anyString());
    }

    @Test
    void theEmailChangesOnlyWhenTheLinkIsOpenedAndEverySignInEnds() throws Exception {
        when(email.isConfigured()).thenReturn(true);
        String next = "moved-" + address;
        String current = storedSession(address);

        mvc.perform(post("/api/account/email").cookie(sessionCookie(current)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json("{'newEmail':' " + next.toUpperCase() + " ','password':'" + PASSWORD + "'}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingEmail").value(next));
        ArgumentCaptor<String> link = ArgumentCaptor.forClass(String.class);
        verify(email).sendEmailChangeLink(eq(next), link.capture());
        verify(email).sendEmailChangeNotice(address, next);

        // Nothing has changed yet.
        assertThat(users.findByEmail(address)).isPresent();
        mvc.perform(get("/api/account/security").cookie(sessionCookie(current))).andExpect(jsonPath("$.pendingEmail").value(next));
        // The token is stored only as a hash.
        String token = link.getValue().substring(link.getValue().indexOf("token=") + 6);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email_change_token_hash = ?", Integer.class, token)).isZero();

        mvc.perform(get("/account/confirm-email").param("token", "not-the-token")).andExpect(status().isOk());
        assertThat(users.findByEmail(address)).isPresent();

        mvc.perform(get("/account/confirm-email").param("token", token)).andExpect(status().isOk());
        assertThat(users.findByEmail(address)).isEmpty();
        assertThat(users.findByEmail(next)).isPresent();
        assertThat(sessions.findById(current)).isNull();

        // The link works once.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE pending_email IS NOT NULL AND email = ?", Integer.class, next)).isZero();
        mvc.perform(post("/login").with(csrf()).param("username", next).param("password", PASSWORD))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/studio"));
    }

    @Test
    void aPendingEmailChangeCanBeCancelled() throws Exception {
        when(email.isConfigured()).thenReturn(true);
        mvc.perform(post("/api/account/email").with(user(address)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json("{'newEmail':'cancel-" + address + "','password':'" + PASSWORD + "'}")))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/account/email").with(user(address)).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/account/security").with(user(address))).andExpect(jsonPath("$.pendingEmail").doesNotExist());
    }

    @Test
    void accountChangesAreRateLimitedLikeSignIn() throws Exception {
        // The per-IP limiter for account forms covers these signed-in changes too.
        com.afklive.streamer.filter.AccountRateLimitFilter f = new com.afklive.streamer.filter.AccountRateLimitFilter(2);
        for (String path : new String[]{"/api/account/password", "/api/account/email", "/api/account/sign-out-others"}) {
            int last = 0;
            for (int i = 0; i < 3; i++) {
                MockHttpServletRequest req = new MockHttpServletRequest("POST", path);
                req.setRemoteAddr("203.0.113." + path.length());
                MockHttpServletResponse res = new MockHttpServletResponse();
                f.doFilter(req, res, new org.springframework.mock.web.MockFilterChain());
                last = res.getStatus();
            }
            assertThat(last).as(path).isEqualTo(429);
        }
    }
}
