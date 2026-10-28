package com.afklive.streamer;

import com.afklive.streamer.exception.ConflictException;
import com.afklive.streamer.model.*;
import com.afklive.streamer.payment.PaymentGateway;
import com.afklive.streamer.repository.*;
import com.afklive.streamer.service.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Admin management of launch offers (edit with the lock after redemption, results) and of a subscriber's recorded plan
 * terms (raise, reset, data allowance, the grandfathered list). BI-9, BI-10, AD-2.
 */
@SpringBootTest(properties = {"app.billing.seller-state=29", "app.billing.seller-gstin=29ABCDE1234F1Z5"})
class AdminOffersAndTermsTest extends PostgresIntegrationTest {

    static final String ADMIN = "owner@afklive.test";

    @MockitoBean PaymentGateway gateway;
    @Autowired BillingService billing;
    @Autowired OfferService offers;
    @Autowired OfferResults results;
    @Autowired PlanTermsAdmin terms;
    @Autowired PlanService plans;
    @Autowired UserRepository users;
    @Autowired SubscriptionRepository subscriptions;
    @Autowired PaymentAuditRepository audits;
    @Autowired LaunchOfferRepository offerRepo;
    @Autowired AdminActionRepository adminActions;
    @Autowired PlanConfigRepository planConfigs;
    @Autowired WebApplicationContext context;

    MockMvc mvc;
    List<PlanConfig> savedPlans;
    String tag;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        when(gateway.createCheckout(anyString(), anyLong(), anyString())).thenReturn("https://pay.test/checkout");
        endAllOffers();
        savedPlans = planConfigs.findAll();
        tag = UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void tearDown() {
        endAllOffers();
        for (PlanConfig c : savedPlans) {
            plans.updatePlan(c);
        }
    }

    private void endAllOffers() {
        ZonedDateTime past = now().minusSeconds(1);
        for (LaunchOffer o : offerRepo.findAll()) {
            if (o.getEndedAt() == null || o.getEndedAt().isAfter(past)) {
                o.setEndedAt(past); // before the start for a scheduled one: it never opens (and overlaps nothing)
                offerRepo.save(o);
            }
        }
    }

    private static ZonedDateTime now() {
        return ZonedDateTime.now(ZoneId.of("UTC"));
    }

    private String scope() {
        return "aot-" + tag + "-%";
    }

    private User account(String name) {
        User u = new User("aot-" + tag + "-" + name + "@example.com");
        u.setPlanType(PlanType.FREE);
        return users.save(u);
    }

    private OfferService.OfferRow offer(int months, ZonedDateTime start, ZonedDateTime end) {
        return offers.create(ADMIN, new OfferService.OfferInput("Launch offer", null, true, 10, months, true, null, start, end),
                "Launch week", now());
    }

    private PaymentAudit pay(User u, String plan, String cycle) {
        BillingService.Checkout c = billing.checkout(u.getEmail(), plan, cycle, null);
        PaymentAudit a = audits.findByTransactionId(c.orderId()).orElseThrow();
        a.setStatus("COMPLETED");
        audits.save(a);
        billing.applyPaid(a);
        return audits.findByTransactionId(c.orderId()).orElseThrow();
    }

    private void nearEnd(User u) {
        Subscription s = subscriptions.findByUserId(u.getId()).orElseThrow();
        s.setCurrentPeriodStart(now().minusDays(28));
        s.setCurrentPeriodEnd(now().plusDays(2));
        subscriptions.save(s);
    }

    private User reload(User u) {
        return users.findById(u.getId()).orElseThrow();
    }

    private static OfferService.OfferInput change() {
        return new OfferService.OfferInput(null, null, null, null, null, null, null, null, null);
    }

    private List<AdminAction> audit(String action, Object target) {
        return adminActions.findAll().stream().filter(a -> action.equals(a.getAction()) && String.valueOf(target).equals(a.getTargetId())).toList();
    }

    // ------------------------------------------------------------------ admin only

    @Test
    void everyNewEndpointIsForAdminsOnly() throws Exception {
        User creator = account("creator");
        String[] gets = {"/api/admin/offers/1/results", "/api/admin/users/" + creator.getId() + "/terms", "/api/admin/grandfathered"};
        String[] posts = {"/api/admin/offers/1", "/api/admin/users/" + creator.getId() + "/terms/raise",
                "/api/admin/users/" + creator.getId() + "/terms/reset", "/api/admin/users/" + creator.getId() + "/terms/data-allowance"};
        for (String url : gets) {
            mvc.perform(get(url).with(user(creator.getEmail()))).andExpect(status().isForbidden());
        }
        for (String url : posts) {
            mvc.perform(post(url).with(user(creator.getEmail())).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
                    .andExpect(status().isForbidden());
        }
        assertThat(adminActions.findAll()).noneMatch(a -> a.getAdminEmail() != null && a.getAdminEmail().equals(creator.getEmail()));

        OfferService.OfferRow o = offer(3, now().minusSeconds(1), now().plusDays(30));
        mvc.perform(post("/api/admin/offers/" + o.id()).with(user(ADMIN).roles("ADMIN")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"offer\":{\"name\":\"Diwali offer\"},\"reason\":\"Festive name\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Diwali offer")).andExpect(jsonPath("$.termsLocked").isEmpty());
        mvc.perform(get("/api/admin/offers/" + o.id() + "/results").with(user(ADMIN).roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.redemptions").value(0));
        mvc.perform(get("/api/admin/users/" + creator.getId() + "/terms").with(user(ADMIN).roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.applies").value(false));
        mvc.perform(get("/api/admin/grandfathered?plan=PRO").with(user(ADMIN).roles("ADMIN"))).andExpect(status().isOk());
        // No reason, no change.
        mvc.perform(post("/api/admin/offers/" + o.id()).with(user(ADMIN).roles("ADMIN")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"offer\":{\"name\":\"Other\"}}")).andExpect(status().isBadRequest());
        assertThat(offerRepo.findById(o.id()).orElseThrow().getName()).isEqualTo("Diwali offer");
    }

    // ------------------------------------------------------------------ editing offers

    @Test
    void beforeAnyoneTakesItEverythingCanChangeAndEachChangeIsAudited() {
        OfferService.OfferRow o = offer(3, now().minusSeconds(1), now().plusDays(30));
        OfferService.OfferRow e = offers.edit(ADMIN, o.id(), new OfferService.OfferInput("Diwali offer", "diwali" + tag.substring(0, 4), null, 20, 2,
                false, List.of("PRO", "AGENCY"), null, now().plusDays(10)), "Festive season", now());
        assertThat(e.name()).isEqualTo("Diwali offer");
        assertThat(e.code()).isEqualTo(("DIWALI" + tag.substring(0, 4)).toUpperCase());
        assertThat(e.percentOff()).isEqualTo(20);
        assertThat(e.monthlyPeriods()).isEqualTo(2);
        assertThat(e.includeYearly()).isFalse();
        assertThat(e.plans()).containsExactly("PRO", "AGENCY");
        assertThat(e.termsLocked()).isNull();
        List<AdminAction> rows = audit("OFFER_EDIT", o.id());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getAdminEmail()).isEqualTo(ADMIN);
        assertThat(rows.get(0).getDetail()).contains("discount 10% → 20%").contains("plans ESSENTIALS,PRO,AGENCY → PRO,AGENCY")
                .contains("months 3 → 2").contains("Festive season");

        assertThatThrownBy(() -> offers.edit(ADMIN, o.id(), change(), "no-op", now())).hasMessageContaining("Nothing changed");
        assertThatThrownBy(() -> offers.edit(ADMIN, o.id(), new OfferService.OfferInput("x", null, null, null, null, null, null, null, null), "", now()))
                .hasMessageContaining("Say why");
        assertThatThrownBy(() -> offers.edit(ADMIN, o.id(), new OfferService.OfferInput(null, null, null, 60, null, null, null, null, null), "too much", now()))
                .hasMessageContaining("between 1% and 50%");
    }

    @Test
    void onceRedeemedTheDiscountPlansAndMonthsAreLockedButNameAndEndDateStillChange() {
        OfferService.OfferRow o = offer(3, now().minusSeconds(1), now().plusDays(30));
        pay(account("payer"), "ESSENTIALS", "MONTHLY");

        OfferService.OfferRow row = offers.list(now()).stream().filter(r -> r.id().equals(o.id())).findFirst().orElseThrow();
        assertThat(row.redemptions()).isEqualTo(1);
        assertThat(row.termsLocked()).contains("1 account has paid with this offer");

        for (OfferService.OfferInput in : List.of(
                new OfferService.OfferInput(null, null, null, 20, null, null, null, null, null),
                new OfferService.OfferInput(null, null, null, null, 6, null, null, null, null),
                new OfferService.OfferInput(null, null, null, null, null, false, null, null, null),
                new OfferService.OfferInput(null, null, false, null, null, null, null, null, null),
                new OfferService.OfferInput(null, null, null, null, null, null, List.of("PRO"), null, null))) {
            assertThatThrownBy(() -> offers.edit(ADMIN, o.id(), in, "Change terms", now()))
                    .isInstanceOf(ConflictException.class).hasMessageContaining("fixed");
        }
        LaunchOffer stored = offerRepo.findById(o.id()).orElseThrow();
        assertThat(stored.getPercentOff()).isEqualTo(10);
        assertThat(stored.getPlans()).isEqualTo("ESSENTIALS,PRO,AGENCY");
        assertThat(stored.getMonthlyPeriods()).isEqualTo(3);

        // Same values as now are not a change, so they don't trip the lock.
        ZonedDateTime later = now().plusDays(60);
        OfferService.OfferRow e = offers.edit(ADMIN, o.id(), new OfferService.OfferInput("Launch offer, extended", null, null, 10, 3, true,
                List.of("PRO", "AGENCY", "ESSENTIALS"), null, later), "Extend for Diwali", now());
        assertThat(e.name()).isEqualTo("Launch offer, extended");
        assertThat(e.endsAt().toInstant()).isEqualTo(later.toInstant());
        // Shortening works too.
        offers.edit(ADMIN, o.id(), new OfferService.OfferInput(null, null, null, null, null, null, null, null, now().plusDays(5)), "Shorter", now());
        assertThat(audit("OFFER_EDIT", o.id())).hasSize(2);
    }

    @Test
    void aCheckoutStartedWithTheOfferLocksItToo() {
        OfferService.OfferRow o = offer(3, now().minusSeconds(1), now().plusDays(30));
        billing.checkout(account("started").getEmail(), "PRO", "MONTHLY", null); // not paid yet
        assertThatThrownBy(() -> offers.edit(ADMIN, o.id(), new OfferService.OfferInput(null, null, null, 30, null, null, null, null, null), "More", now()))
                .isInstanceOf(ConflictException.class).hasMessageContaining("checkout");
    }

    @Test
    void datesFollowTheRules() {
        // Started: the start can't move; the end can't go into the past; the window is still 92 days at most.
        ZonedDateTime started = now().minusDays(1);
        OfferService.OfferRow o = offer(3, now().minusSeconds(1), now().plusDays(30));
        assertThatThrownBy(() -> offers.edit(ADMIN, o.id(), new OfferService.OfferInput(null, null, null, null, null, null, null, now().plusDays(1), null), "Later", now()))
                .isInstanceOf(ConflictException.class).hasMessageContaining("has started");
        assertThatThrownBy(() -> offers.edit(ADMIN, o.id(), new OfferService.OfferInput(null, null, null, null, null, null, null, null, now().minusHours(1)), "Back", now()))
                .hasMessageContaining("can't be in the past");
        assertThatThrownBy(() -> offers.edit(ADMIN, o.id(), new OfferService.OfferInput(null, null, null, null, null, null, null, null, now().plusDays(120)), "Long", now()))
                .hasMessageContaining("at most 92 days");
        assertThat(started).isBefore(now());

        // Scheduled: the start can move (not into the past).
        endAllOffers();
        OfferService.OfferRow s = offer(3, now().plusDays(5), now().plusDays(20));
        assertThat(s.status()).isEqualTo("SCHEDULED");
        OfferService.OfferRow moved = offers.edit(ADMIN, s.id(), new OfferService.OfferInput(null, null, null, null, null, null, null, now().plusDays(3), null), "Earlier", now());
        assertThat(moved.startsAt()).isBefore(s.startsAt());
        assertThatThrownBy(() -> offers.edit(ADMIN, s.id(), new OfferService.OfferInput(null, null, null, null, null, null, null, now().minusDays(1), null), "Past", now()))
                .hasMessageContaining("can't start in the past");

        // Another automatic offer can't be overlapped by moving dates.
        OfferService.OfferRow next = offer(3, now().plusDays(30), now().plusDays(40));
        assertThatThrownBy(() -> offers.edit(ADMIN, s.id(), new OfferService.OfferInput(null, null, null, null, null, null, null, null, now().plusDays(35)), "Overlap", now()))
                .isInstanceOf(ConflictException.class).hasMessageContaining("Another automatic offer");
        assertThat(next.id()).isNotNull();

        // Ended: stays ended.
        offers.end(ADMIN, s.id(), "Stop", now());
        assertThatThrownBy(() -> offers.edit(ADMIN, s.id(), new OfferService.OfferInput("Again", null, null, null, null, null, null, null, null), "Reopen", now()))
                .isInstanceOf(ConflictException.class).hasMessageContaining("stay ended");
    }

    // ------------------------------------------------------------------ results

    @Test
    void resultsAddUpRedemptionsDiscountsRenewalsAndConversion() {
        OfferService.OfferRow o = offer(2, now().minusSeconds(1), now().plusDays(30));
        User a = account("a");   // monthly Creator: two discounted payments, then a full-price renewal
        User b = account("b");   // yearly Pro: one discounted payment (the first year)
        User c = account("c");   // monthly Creator: one discounted payment, one still to come
        account("d");            // signed up during the offer, never paid
        PaymentAudit a1 = pay(a, "ESSENTIALS", "MONTHLY");
        nearEnd(a);
        PaymentAudit a2 = pay(a, "ESSENTIALS", "MONTHLY");
        nearEnd(a);
        PaymentAudit a3 = pay(a, "ESSENTIALS", "MONTHLY");
        PaymentAudit b1 = pay(b, "PRO", "YEARLY");
        PaymentAudit c1 = pay(c, "ESSENTIALS", "MONTHLY");
        assertThat(a3.getDiscountAmount()).isZero();
        assertThat(List.of(a1, a2, b1, c1)).allSatisfy(p -> assertThat(p.getDiscountAmount()).isPositive());

        OfferResults.Results r = results.results(o.id(), now(), scope());
        assertThat(r.redemptions()).isEqualTo(3);
        assertThat(r.byPlan()).extracting(x -> x.plan() + "/" + x.cycle() + "=" + x.accounts())
                .containsExactly("ESSENTIALS/MONTHLY=2", "PRO/YEARLY=1");
        assertThat(r.discount()).hasSize(1);
        OfferResults.Money inr = r.discount().get(0);
        assertThat(inr.currency()).isEqualTo("INR");
        assertThat(inr.payments()).isEqualTo(4);
        long gross = 0, net = 0, paid = 0;
        for (PaymentAudit p : List.of(a1, a2, b1, c1)) {
            gross += p.getDiscountAmount();
            net += InvoiceService.split(p.getListAmount(), "INR", null, null).taxable()
                    - InvoiceService.split(p.getListAmount() - p.getDiscountAmount(), "INR", null, null).taxable();
            paid += p.getAmount();
        }
        assertThat(inr.discountWithGst()).isEqualTo(gross);
        assertThat(inr.discountWithoutGst()).isEqualTo(net);
        assertThat(inr.discountWithoutGst()).isLessThan(inr.discountWithGst());
        assertThat(inr.paid()).isEqualTo(paid);

        assertThat(r.inDiscountedPeriod()).isEqualTo(2);       // b and c; a's latest payment was at full price
        assertThat(r.discountedPaymentsToCome()).isEqualTo(1);  // c
        assertThat(r.finishedOffer()).isEqualTo(2);             // a and b
        assertThat(r.renewedAtFullPrice()).isEqualTo(1);        // a
        assertThat(r.upgradedAfter()).isZero();
        assertThat(r.signUps()).isEqualTo(4);
        assertThat(r.signUpsTookOffer()).isEqualTo(3);
        assertThat(r.conversionPercent()).isEqualTo(75.0);
        assertThat(r.signUpsPaidAnyPlan()).isEqualTo(3);
        assertThat(r.status()).isEqualTo("ACTIVE");
    }

    // ------------------------------------------------------------------ a subscriber's recorded terms

    private PlanConfig plan(PlanType t) {
        return planConfigs.findById(t).orElseThrow();
    }

    /** A Pro subscriber who bought when Pro gave 5 destinations and no data limit; Pro now gives 2 and 6,000 GB. */
    private User grandfatheredPro() {
        PlanConfig pro = plan(PlanType.PRO);
        pro.setMaxDestinations(5);
        pro.setMaxResolution(1080);
        pro.setMonthlyTrafficGb(null);
        plans.updatePlan(pro);
        User u = account("pro");
        pay(u, "PRO", "MONTHLY");
        pro = plan(PlanType.PRO);
        pro.setMaxDestinations(2);
        pro.setMonthlyTrafficGb(6_000);
        plans.updatePlan(pro);
        return reload(u);
    }

    @Test
    void theUserPageShowsRecordedLimitsBesideThePlansAndTheGrandfatheredListFindsThem() {
        User u = grandfatheredPro();
        PlanTermsAdmin.TermsView v = terms.view(u.getId());
        assertThat(v.applies()).isTrue();
        assertThat(v.capturedAt()).isNotNull();
        assertThat(v.recorded().destinations()).isEqualTo(5);
        assertThat(v.recorded().trafficGb()).isNull();
        assertThat(v.current().destinations()).isEqualTo(2);
        assertThat(v.effective().destinations()).isEqualTo(5);
        assertThat(v.effective().trafficGb()).isNull();
        assertThat(v.extras()).contains("5 destinations (plan: 2)", "no streaming data limit (plan: 6000 GB)");
        assertThat(v.resetReduces()).contains("destinations 5 → 2", "streaming data no limit → 6000 GB");

        PlanTermsAdmin.Grandfathered g = terms.grandfathered("PRO", scope());
        assertThat(g.rows()).extracting(PlanTermsAdmin.GrandfatheredRow::email).containsExactly(u.getEmail());
        assertThat(g.rows().get(0).extras()).contains("5 destinations (plan: 2)");
        assertThat(g.total()).isEqualTo(1);
        assertThat(terms.grandfathered("AGENCY", scope()).rows()).isEmpty();
        assertThat(terms.grandfathered(null, scope()).countByPlan()).containsEntry("PRO", 1L);

        User free = account("free");
        assertThat(terms.view(free.getId()).applies()).isFalse();
        assertThatThrownBy(() -> terms.raise(ADMIN, free.getId(), new PlanTermsAdmin.Raise(null, 3, null, null, null, null, null), "Gift"))
                .isInstanceOf(ConflictException.class).hasMessageContaining("Free accounts");
    }

    @Test
    void raisingALimitGivesMoreKeepsItThroughRenewalsAndIsAudited() {
        User u = grandfatheredPro();
        terms.raise(ADMIN, u.getId(), new PlanTermsAdmin.Raise(null, 8, 2160, null, null, null, null), "Agency trial: 4K");
        assertThat(plans.limitsFor(reload(u)).getMaxResolution()).isEqualTo(2160);
        assertThat(plans.limitsFor(reload(u)).getMaxDestinations()).isEqualTo(8);
        List<AdminAction> rows = audit("TERMS_RAISE", u.getId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getDetail()).contains("quality 1080p → 2160p").contains("destinations 5 → 8").contains("Agency trial: 4K");

        // Not more than now: refused, nothing changes.
        assertThatThrownBy(() -> terms.raise(ADMIN, u.getId(), new PlanTermsAdmin.Raise(null, 3, null, null, null, null, null), "Lower"))
                .hasMessageContaining("isn't more than they have now");
        assertThatThrownBy(() -> terms.raise(ADMIN, u.getId(), new PlanTermsAdmin.Raise(null, null, 1700, null, null, null, null), "Odd"))
                .hasMessageContaining("720, 1080, 1440 or 2160");
        assertThat(plans.limitsFor(reload(u)).getMaxDestinations()).isEqualTo(8);

        nearEnd(u);
        pay(u, "PRO", "MONTHLY");
        assertThat(plans.limitsFor(reload(u)).getMaxResolution()).isEqualTo(2160);
    }

    @Test
    void resetReplacesTheRecordAndCanReduceOnlyWhenConfirmed() {
        User u = grandfatheredPro();
        assertThatThrownBy(() -> terms.reset(ADMIN, u.getId(), "Abuse", false))
                .isInstanceOf(ConflictException.class).hasMessageContaining("destinations 5 → 2").hasMessageContaining("Confirm");
        assertThat(plans.limitsFor(reload(u)).getMaxDestinations()).isEqualTo(5);
        assertThat(audit("TERMS_RESET", u.getId())).isEmpty();

        PlanTermsAdmin.TermsView v = terms.reset(ADMIN, u.getId(), "Abuse of the old limits", true);
        PlanConfig limits = plans.limitsFor(reload(u));
        assertThat(limits.getMaxDestinations()).isEqualTo(2);
        assertThat(limits.getMonthlyTrafficGb()).isEqualTo(6_000);
        assertThat(v.extras()).isEmpty();
        assertThat(v.resetReduces()).isEmpty();
        assertThat(terms.grandfathered("PRO", scope()).rows()).isEmpty();
        List<AdminAction> rows = audit("TERMS_RESET", u.getId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getDetail()).contains("taken away: ").contains("destinations 5 → 2").contains("recorded before: ").contains(", 5 destinations")
                .contains("Abuse of the old limits");

        // Nothing to take away: no confirmation needed.
        terms.reset(ADMIN, u.getId(), "Again", false);
        assertThat(audit("TERMS_RESET", u.getId()).get(1).getDetail()).contains("nothing taken away");
    }

    @Test
    void aSubscribersDataAllowanceCanBeSetAndCleared() {
        User u = grandfatheredPro(); // no data limit of their own; Pro now has 6,000 GB
        assertThatThrownBy(() -> terms.dataAllowance(ADMIN, u.getId(), 8_000, "Fair use", false))
                .isInstanceOf(ConflictException.class).hasMessageContaining("streaming data no limit → 8000 GB");
        terms.dataAllowance(ADMIN, u.getId(), 8_000, "Fair use", true);
        assertThat(plans.limitsFor(reload(u)).getMonthlyTrafficGb()).isEqualTo(8_000);
        // Below the plan's: the plan's applies (the more generous).
        terms.dataAllowance(ADMIN, u.getId(), 100, "Fair use", true);
        assertThat(plans.limitsFor(reload(u)).getMonthlyTrafficGb()).isEqualTo(6_000);
        // Raising or clearing gives more: no confirmation needed.
        terms.dataAllowance(ADMIN, u.getId(), 20_000, "Big event", false);
        assertThat(plans.limitsFor(reload(u)).getMonthlyTrafficGb()).isEqualTo(20_000);
        terms.dataAllowance(ADMIN, u.getId(), null, "Partner", false);
        assertThat(plans.limitsFor(reload(u)).getMonthlyTrafficGb()).isNull();
        assertThat(audit("TERMS_DATA_ALLOWANCE", u.getId())).hasSize(4)
                .anySatisfy(a -> assertThat(a.getDetail()).contains("recorded data allowance 20000 GB → no limit").contains("Partner"));
        assertThatThrownBy(() -> terms.dataAllowance(ADMIN, u.getId(), null, "Again", false)).hasMessageContaining("Nothing changed");
    }
}
