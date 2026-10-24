package com.afklive.streamer;

import com.afklive.streamer.model.PlanType;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.UserRepository;
import com.afklive.streamer.service.ReferralService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GR-18: invite links, referrals recorded at sign-up, and the opt-in credit line. */
@SpringBootTest
class ReferralTest extends PostgresIntegrationTest {

    @Autowired ReferralService referrals;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired WebApplicationContext context;
    @Autowired com.afklive.streamer.filter.ReferralCookieFilter referralFilter;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        // Spring Boot registers filter beans in the real server; MockMvc needs it added.
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(referralFilter).apply(springSecurity()).build();
    }

    private User newUser() {
        User u = new User("ref-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com");
        u.setPlanType(PlanType.FREE);
        return users.save(u);
    }

    private int referralsOf(Long referrer) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM referrals WHERE referrer_id = ?", Integer.class, referrer);
    }

    @Test
    void everyAccountHasOneStableInviteLink() {
        User u = newUser();
        String code = referrals.code(u.getId());
        assertThat(code).matches("[a-z2-9]{8}");
        assertThat(referrals.code(u.getId())).isEqualTo(code);
        assertThat(referrals.link(u.getId())).endsWith("/?ref=" + code);
        assertThat(referrals.code(newUser().getId())).isNotEqualTo(code);
    }

    @Test
    void aSignUpThroughAnInviteIsRecordedOnceAndNeverForOneself() {
        User inviter = newUser();
        String code = referrals.code(inviter.getId());
        User joined = newUser();
        assertThat(referrals.recordSignup(joined.getEmail(), "nosuchcode")).isFalse();
        assertThat(referrals.recordSignup(inviter.getEmail(), code)).as("not for oneself").isFalse();
        assertThat(referrals.recordSignup(joined.getEmail(), "bad code!")).isFalse();
        assertThat(referrals.recordSignup(joined.getEmail(), code)).isTrue();
        User someoneElse = newUser();
        assertThat(referrals.recordSignup(joined.getEmail(), referrals.code(someoneElse.getId()))).as("one inviter per account").isFalse();
        assertThat(referralsOf(inviter.getId())).isEqualTo(1);

        ReferralService.Summary s = referrals.summary(inviter.getId());
        assertThat(s.joined()).isEqualTo(1);
        assertThat(s.waitingForFirstLive()).isEqualTo(1);
        assertThat(s.credited()).isZero();
        assertThat(s.payoutsLive()).as("no payout until RE-8 confirms a first live").isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM referrals WHERE referred_id = ?", String.class, joined.getId())).isEqualTo("JOINED");
    }

    @Test
    void anInviteLinkLeavesACookieThatRegistrationReads() throws Exception {
        User inviter = newUser();
        String code = referrals.code(inviter.getId());
        mvc.perform(get("/pricing").param("ref", code))
                .andExpect(cookie().value(ReferralService.COOKIE, code))
                .andExpect(cookie().httpOnly(ReferralService.COOKIE, true));
        // A second invite doesn't replace the first.
        mvc.perform(get("/pricing").param("ref", "othercode1").cookie(new Cookie(ReferralService.COOKIE, code)))
                .andExpect(cookie().doesNotExist(ReferralService.COOKIE));
        mvc.perform(get("/pricing").param("ref", "<script>")).andExpect(cookie().doesNotExist(ReferralService.COOKIE));

        String email = "ref-new-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        mvc.perform(post("/register").with(csrf()).cookie(new Cookie(ReferralService.COOKIE, code))
                .param("email", email).param("password", "Str0ng!pass").param("name", "New Creator"));
        assertThat(users.findByEmail(email)).isPresent();
        assertThat(referralsOf(inviter.getId())).isEqualTo(1);
    }

    @Test
    void theCreditLineIsOffByDefaultAndOnlyJoinsADescriptionBeingWritten() throws Exception {
        User u = newUser();
        assertThat(referrals.creditLine(u.getId())).isFalse();
        assertThat(referrals.withCreditLine(u.getId(), "Lofi all night")).isEqualTo("Lofi all night");

        mvc.perform(put("/api/referrals/credit-line").with(user(u.getEmail())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.creditLine").value(true))
                .andExpect(jsonPath("$.creditText").value(org.hamcrest.Matchers.startsWith("Streamed 24/7 with AFK Live: ")));
        String with = referrals.withCreditLine(u.getId(), "Lofi all night");
        assertThat(with).isEqualTo("Lofi all night\n\n" + ReferralService.creditText(referrals.link(u.getId())));
        assertThat(referrals.withCreditLine(u.getId(), with)).as("never twice").isEqualTo(with);
        assertThat(referrals.withCreditLine(u.getId(), null)).as("no description written just for the line").isNull();
        String full = "x".repeat(4990);
        assertThat(referrals.withCreditLine(u.getId(), full)).as("never cuts the creator's words").isEqualTo(full);
    }

    @Test
    void theInvitePageIsTheOwnersOnly() throws Exception {
        mvc.perform(get("/api/referrals")).andExpect(status().is3xxRedirection());
        User u = newUser();
        mvc.perform(get("/api/referrals").with(user(u.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.link").value(org.hamcrest.Matchers.endsWith(referrals.code(u.getId()))))
                .andExpect(jsonPath("$.creditLine").value(false));
    }
}
