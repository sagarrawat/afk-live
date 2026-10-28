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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** BI-9 launch offers (price math, GST, eligibility, renewals, invoices, admin) and BI-10 grandfathered plan limits. */
@SpringBootTest(properties = {"app.billing.seller-state=29", "app.billing.seller-gstin=29ABCDE1234F1Z5"})
class LaunchOfferTest extends PostgresIntegrationTest {

    static final String ADMIN = "owner@afklive.test";

    @MockitoBean PaymentGateway gateway;
    @Autowired BillingService billing;
    @Autowired OfferService offers;
    @Autowired AdminService admin;
    @Autowired PlanService plans;
    @Autowired UserRepository users;
    @Autowired SubscriptionRepository subscriptions;
    @Autowired PaymentAuditRepository audits;
    @Autowired InvoiceRepository invoices;
    @Autowired LaunchOfferRepository offerRepo;
    @Autowired OfferRedemptionRepository redemptions;
    @Autowired AdminActionRepository adminActions;
    @Autowired PlanConfigRepository planConfigs;
    @Autowired FairUse fairUse;
    @Autowired PlatformTransactionManager txManager;
    @Autowired WebApplicationContext context;

    MockMvc mvc;
    List<PlanConfig> savedPlans;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        when(gateway.createCheckout(anyString(), anyLong(), anyString())).thenReturn("https://pay.test/checkout");
        endAllOffers();
        savedPlans = planConfigs.findAll();
    }

    /** Other tests share this database: no offer stays open and the plans go back to what they were. */
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
                o.setEndedAt(past.isAfter(o.getStartsAt()) ? past : o.getStartsAt().plusSeconds(1));
                offerRepo.save(o);
            }
        }
    }

    private static ZonedDateTime now() {
        return ZonedDateTime.now(ZoneId.of("UTC"));
    }

    private User account() {
        User u = new User("offer-" + UUID.randomUUID() + "@example.com");
        u.setPlanType(PlanType.FREE);
        return users.save(u);
    }

    private OfferService.OfferRow launch(String code, boolean automatic) {
        return offers.create(ADMIN, new OfferService.OfferInput("Launch offer", code, automatic, 10, 3, true, null,
                now().minusSeconds(1), now().plusDays(30)), "Launch week", now());
    }

    private PaymentAudit pay(User u, String plan, String cycle, String code) {
        BillingService.Checkout c = billing.checkout(u.getEmail(), plan, cycle, code);
        PaymentAudit a = audits.findByTransactionId(c.orderId()).orElseThrow();
        a.setStatus("COMPLETED");
        audits.save(a);
        billing.applyPaid(a);
        return a;
    }

    private PaymentAudit pay(User u, String plan, String cycle) {
        return pay(u, plan, cycle, null);
    }

    /** Makes the current period end in two days, so a renewal can be paid. */
    private void nearEnd(User u) {
        Subscription s = subscriptions.findByUserId(u.getId()).orElseThrow();
        s.setCurrentPeriodStart(now().minusDays(28));
        s.setCurrentPeriodEnd(now().plusDays(2));
        subscriptions.save(s);
    }

    private User reload(User u) {
        return users.findById(u.getId()).orElseThrow();
    }

    // ------------------------------------------------------------------ price math and GST

    @Test
    void discountIsOnTheRealListPriceRoundedDownToTheRupee() {
        assertThat(OfferService.discountedPrice(49_900, 10, "INR")).isEqualTo(44_900);      // ₹449.10 → ₹449
        assertThat(OfferService.discountedPrice(149_900, 10, "INR")).isEqualTo(134_900);    // ₹1,349
        assertThat(OfferService.discountedPrice(399_900, 10, "INR")).isEqualTo(359_900);    // ₹3,599
        assertThat(OfferService.discountedPrice(499_000, 10, "INR")).isEqualTo(449_100);    // yearly ₹4,491
        assertThat(OfferService.discountedPrice(1_499_000, 10, "INR")).isEqualTo(1_349_100);
        assertThat(OfferService.discountedPrice(3_999_000, 10, "INR")).isEqualTo(3_599_100);
        assertThat(OfferService.discountedPrice(900, 10, "USD")).isEqualTo(810);             // $8.10: cents kept
        assertThat(OfferService.discountedPrice(49_900, 0, "INR")).isEqualTo(49_900);
        // Never less than the advertised percentage off.
        for (long list : new long[]{49_900, 149_900, 399_900, 499_000}) {
            long price = OfferService.discountedPrice(list, 10, "INR");
            assertThat((list - price) * 100).isGreaterThanOrEqualTo(list * 10);
        }
    }

    @Test
    void gstIsWorkedOutOnTheDiscountedPrice() {
        InvoiceService.Tax t = InvoiceService.split(44_900, "INR", "27", "29");
        assertThat(t.taxable()).isEqualTo(38_051);
        assertThat(t.igst()).isEqualTo(6_849);
        assertThat(t.taxable() + t.igst()).isEqualTo(44_900);
        InvoiceService.Tax intra = InvoiceService.split(44_900, "INR", "29", "29");
        assertThat(intra.cgst() + intra.sgst()).isEqualTo(6_849);
        Invoice inv = new Invoice();
        inv.setCurrency("INR");
        inv.setListAmount(49_900L);
        inv.setDiscountAmount(5_000);
        inv.setTaxableAmount(38_051);
        InvoiceService.DiscountLines d = InvoiceService.discountLines(inv);
        assertThat(d.gross()).isEqualTo(42_288);        // ₹499 list price before GST
        assertThat(d.discount()).isEqualTo(4_237);      // discount before GST
        assertThat(d.gross() - d.discount()).isEqualTo(inv.getTaxableAmount());
    }

    // ------------------------------------------------------------------ quote, checkout, invoice, renewals

    @Test
    void noOfferMeansListPricesAndNothingExtraOnTheQuote() throws Exception {
        User u = account();
        BillingService.Quote q = billing.quote(u.getEmail(), "ESSENTIALS", "MONTHLY");
        assertThat(q.listPrice()).isEqualTo(49_900);
        assertThat(q.discount()).isZero();
        assertThat(q.offer()).isNull();
        assertThat(q.amount()).isEqualTo(49_900);
        mvc.perform(get("/api/billing/offer")).andExpect(status().isNoContent());
    }

    @Test
    void newSubscriberGetsTheOfferForThreeMonthsThenTheListPrice() throws Exception {
        launch(null, true);
        User u = account();
        BillingService.Quote q = billing.quote(u.getEmail(), "ESSENTIALS", "MONTHLY");
        assertThat(q.kind()).isEqualTo("NEW");
        assertThat(q.listPrice()).isEqualTo(49_900);
        assertThat(q.discount()).isEqualTo(5_000);
        assertThat(q.price()).isEqualTo(44_900);
        assertThat(q.amount()).isEqualTo(44_900);
        assertThat(q.gst()).isEqualTo(6_849);
        assertThat(q.renewalPrice()).isEqualTo(49_900);
        assertThat(q.offer().percentOff()).isEqualTo(10);
        assertThat(q.offer().payments()).isEqualTo(3);
        assertThat(q.offer().paymentsLeft()).isEqualTo(2);

        PaymentAudit first = pay(u, "ESSENTIALS", "MONTHLY");
        verify(gateway).createCheckout(eq(first.getTransactionId()), eq(44_900L), anyString()); // the order is for the discounted amount
        first = audits.findByTransactionId(first.getTransactionId()).orElseThrow();
        assertThat(first.getDiscountAmount()).isEqualTo(5_000);
        assertThat(first.getListAmount()).isEqualTo(49_900);
        assertThat(first.getOfferId()).isNotNull();
        Invoice inv = invoices.findByPaymentOrderId(first.getTransactionId()).orElseThrow();
        assertThat(inv.getTotal()).isEqualTo(44_900);
        assertThat(inv.getListAmount()).isEqualTo(49_900);
        assertThat(inv.getDiscountAmount()).isEqualTo(5_000);
        assertThat(inv.getDiscountLabel()).isEqualTo("Launch offer (10% off)");
        assertThat(inv.getTaxableAmount()).isEqualTo(38_051);
        assertThat(inv.getIgst() + inv.getCgst() + inv.getSgst()).isEqualTo(6_849);
        assertThat(new String(billing.invoicePdf(u.getEmail(), inv.getId()), java.nio.charset.StandardCharsets.ISO_8859_1)).startsWith("%PDF");
        assertThat(subscriptions.findByUserId(u.getId()).orElseThrow().getPeriodPrice()).isEqualTo(44_900);

        // Renewals 2 and 3 are discounted; the reminder says when it goes back to the list price.
        BillingService.Overview o = billing.overview(u.getEmail());
        assertThat(o.nextCharge().amount()).isEqualTo(44_900);
        assertThat(o.offer().left()).isEqualTo(2);
        nearEnd(u);
        assertThat(billing.quote(u.getEmail(), "ESSENTIALS", "MONTHLY").amount()).isEqualTo(44_900);
        pay(u, "ESSENTIALS", "MONTHLY");
        nearEnd(u);
        Subscription s = subscriptions.findByUserId(u.getId()).orElseThrow();
        assertThat(billing.renewalMessage(s, PlanType.ESSENTIALS, Subscription.Cycle.MONTHLY))
                .contains("₹449 incl. GST").contains("last discounted month").contains("renews at ₹499 incl. GST a month");
        BillingService.Quote third = billing.quote(u.getEmail(), "ESSENTIALS", "MONTHLY");
        assertThat(third.amount()).isEqualTo(44_900);
        assertThat(third.offer().paymentsLeft()).isZero();
        pay(u, "ESSENTIALS", "MONTHLY");
        assertThat(redemptions.findById(u.getId()).orElseThrow().getPeriodsUsed()).isEqualTo(3);

        // The fourth payment is at the list price, even though the offer is still open.
        nearEnd(u);
        BillingService.Quote fourth = billing.quote(u.getEmail(), "ESSENTIALS", "MONTHLY");
        assertThat(fourth.amount()).isEqualTo(49_900);
        assertThat(fourth.offer()).isNull();
        assertThat(billing.overview(u.getEmail()).nextCharge().amount()).isEqualTo(49_900);
        assertThat(billing.renewalMessage(subscriptions.findByUserId(u.getId()).orElseThrow(), PlanType.ESSENTIALS, Subscription.Cycle.MONTHLY))
                .isEqualTo("Pay ₹499 incl. GST for the next month of Creator from Billing to stay on it.");
    }

    @Test
    void yearlyGetsTheDiscountOnceOnTheFirstYear() {
        launch(null, true);
        User u = account();
        BillingService.Quote q = billing.quote(u.getEmail(), "ESSENTIALS", "YEARLY");
        assertThat(q.amount()).isEqualTo(449_100);
        assertThat(q.offer().payments()).isEqualTo(1);
        assertThat(q.offer().paymentsLeft()).isZero();
        pay(u, "ESSENTIALS", "YEARLY");
        Subscription s = subscriptions.findByUserId(u.getId()).orElseThrow();
        s.setCurrentPeriodEnd(now().plusDays(2));
        subscriptions.save(s);
        assertThat(billing.quote(u.getEmail(), "ESSENTIALS", "YEARLY").amount()).isEqualTo(499_000);
    }

    @Test
    void anUpgradeIsAtTheListPriceAndCreditsWhatWasPaid() {
        launch(null, true);
        User u = account();
        pay(u, "ESSENTIALS", "MONTHLY");
        Subscription s = subscriptions.findByUserId(u.getId()).orElseThrow();
        s.setCurrentPeriodStart(now().minusDays(15));
        s.setCurrentPeriodEnd(now().plusDays(15));
        subscriptions.save(s);
        BillingService.Quote up = billing.quote(u.getEmail(), "PRO", "MONTHLY");
        assertThat(up.kind()).isEqualTo("UPGRADE");
        assertThat(up.discount()).isZero();
        assertThat(up.listPrice()).isEqualTo(149_900);
        assertThat(up.credit()).isBetween(22_000L, 22_900L); // about half of the ₹449 paid, not of ₹499
    }

    // ------------------------------------------------------------------ eligibility

    @Test
    void onlyAccountsThatNeverHadAPlanGetIt() {
        launch("LAUNCH10", true);
        User lapsed = account();
        pay(lapsed, "ESSENTIALS", "MONTHLY");
        // Their plan ended: back on Free, but they had a plan, so the offer isn't for them again.
        Subscription s = subscriptions.findByUserId(lapsed.getId()).orElseThrow();
        s.setStatus(Subscription.Status.CANCELED);
        subscriptions.save(s);
        User l = reload(lapsed);
        l.setPlanType(PlanType.FREE);
        users.save(l);
        assertThat(billing.quote(lapsed.getEmail(), "ESSENTIALS", "MONTHLY").amount()).isEqualTo(49_900);
        assertThatThrownBy(() -> billing.quote(lapsed.getEmail(), "ESSENTIALS", "MONTHLY", "LAUNCH10")).isInstanceOf(ConflictException.class);

        // An account an admin once put on a plan isn't new either.
        User comped = account();
        admin.setPlan(ADMIN, comped.getId(), "PRO", now().plusDays(5), "Partner trial");
        admin.setPlan(ADMIN, comped.getId(), "FREE", null, "Trial over");
        assertThat(billing.quote(comped.getEmail(), "ESSENTIALS", "MONTHLY").discount()).isZero();
    }

    @Test
    void codesAndDatesAreChecked() {
        offers.create(ADMIN, new OfferService.OfferInput("Creator launch", "CREATOR10", false, 10, 3, false, List.of("ESSENTIALS"),
                now().minusSeconds(1), now().plusDays(10)), "Creator push", now());
        offers.create(ADMIN, new OfferService.OfferInput("Later", "LATER10", false, 10, 3, true, null,
                now().plusDays(2), now().plusDays(10)), "Next month", now());
        User u = account();
        // A code-only offer doesn't apply by itself and isn't advertised.
        assertThat(billing.quote(u.getEmail(), "ESSENTIALS", "MONTHLY").discount()).isZero();
        assertThat(offers.publicOffer("INR", null, now())).isEmpty();
        assertThat(billing.quote(u.getEmail(), "ESSENTIALS", "MONTHLY", "creator10").amount()).isEqualTo(44_900);
        assertThatThrownBy(() -> billing.quote(u.getEmail(), "PRO", "MONTHLY", "CREATOR10")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("doesn't apply");
        assertThatThrownBy(() -> billing.quote(u.getEmail(), "ESSENTIALS", "YEARLY", "CREATOR10")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> billing.quote(u.getEmail(), "ESSENTIALS", "MONTHLY", "LATER10")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("isn't valid");
        assertThatThrownBy(() -> billing.quote(u.getEmail(), "ESSENTIALS", "MONTHLY", "NOPE")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anOfferEndedEarlyStopsForNewPeopleButKeepsEarnedRenewals() throws Exception {
        OfferService.OfferRow row = launch(null, true);
        User early = account();
        pay(early, "PRO", "MONTHLY");
        offers.end(ADMIN, row.id(), "Margins", now());
        User late = account();
        assertThat(billing.quote(late.getEmail(), "PRO", "MONTHLY").amount()).isEqualTo(149_900);
        mvc.perform(get("/api/billing/offer")).andExpect(status().isNoContent());
        nearEnd(early);
        assertThat(billing.quote(early.getEmail(), "PRO", "MONTHLY").amount()).isEqualTo(134_900);
    }

    @Test
    void twoCheckoutsForOneAccountUseOneOffer() throws Exception {
        launch(null, true);
        User u = account();
        BillingService.Checkout a = billing.checkout(u.getEmail(), "ESSENTIALS", "MONTHLY");
        BillingService.Checkout b = billing.checkout(u.getEmail(), "ESSENTIALS", "MONTHLY");
        assertThat(a.quote().amount()).isEqualTo(44_900);
        assertThat(b.quote().amount()).isEqualTo(44_900);
        for (BillingService.Checkout c : List.of(a, b)) {
            PaymentAudit p = audits.findByTransactionId(c.orderId()).orElseThrow();
            p.setStatus("COMPLETED");
            audits.save(p);
            billing.applyPaid(p);
        }
        OfferRedemption r = redemptions.findById(u.getId()).orElseThrow();
        assertThat(redemptions.findAll().stream().filter(x -> x.getUserId().equals(u.getId()))).hasSize(1);
        assertThat(r.getPeriodsUsed()).isEqualTo(2); // both discounted payments count against the same 3

        // The claim itself is safe when payments race on several instances: exactly one insert wins.
        User v = account();
        Long offerId = r.getOfferId();
        TransactionTemplate tx = new TransactionTemplate(txManager);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier go = new CyclicBarrier(2);
        Callable<Integer> claim = () -> {
            go.await(5, TimeUnit.SECONDS);
            return tx.execute(st -> redemptions.claim(v.getId(), offerId, "ESSENTIALS", "MONTHLY", 10, 3, UUID.randomUUID().toString()));
        };
        Future<Integer> x = pool.submit(claim);
        Future<Integer> y = pool.submit(claim);
        assertThat(x.get(10, TimeUnit.SECONDS) + y.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        pool.shutdown();
    }

    // ------------------------------------------------------------------ public endpoint and admin

    @Test
    void publicOfferShowsRealPricesAndWhoCanHaveIt() throws Exception {
        launch(null, true);
        mvc.perform(get("/api/billing/offer").param("currency", "INR")).andExpect(status().isOk())
                .andExpect(jsonPath("$.percentOff").value(10))
                .andExpect(jsonPath("$.monthlyPeriods").value(3))
                .andExpect(jsonPath("$.plans[0].id").value("ESSENTIALS"))
                .andExpect(jsonPath("$.plans[0].listMonthlyLabel").value("₹499"))
                .andExpect(jsonPath("$.plans[0].offerMonthlyLabel").value("₹449"))
                .andExpect(jsonPath("$.plans[0].offerYearlyLabel").value("₹4,491"))
                .andExpect(jsonPath("$.plans[1].offerMonthlyLabel").value("₹1,349"))
                .andExpect(jsonPath("$.plans[2].offerMonthlyLabel").value("₹3,599"))
                .andExpect(jsonPath("$.eligible").doesNotExist());
        User payer = account();
        pay(payer, "ESSENTIALS", "MONTHLY");
        mvc.perform(get("/api/billing/offer").with(user(payer.getEmail()))).andExpect(jsonPath("$.eligible").value(false));
        User fresh = account();
        mvc.perform(get("/api/billing/offer").with(user(fresh.getEmail()))).andExpect(jsonPath("$.eligible").value(true));
    }

    @Test
    void adminCreatesAndEndsOffersWithAReasonAndRealDates() {
        assertThatThrownBy(() -> offers.create(ADMIN, new OfferService.OfferInput("Launch", null, true, 10, 3, true, null, null, now().plusDays(5)),
                "", now())).hasMessageContaining("Say why");
        assertThatThrownBy(() -> offers.create(ADMIN, new OfferService.OfferInput("Launch", null, true, 10, 3, true, null, null, null),
                "Launch", now())).hasMessageContaining("end date");
        assertThatThrownBy(() -> offers.create(ADMIN, new OfferService.OfferInput("Launch", null, true, 10, 3, true, null, null, now().plusDays(400)),
                "Launch", now())).hasMessageContaining("at most");
        assertThatThrownBy(() -> offers.create(ADMIN, new OfferService.OfferInput("Launch", null, true, 80, 3, true, null, null, now().plusDays(5)),
                "Launch", now())).hasMessageContaining("between 1%");
        assertThatThrownBy(() -> offers.create(ADMIN, new OfferService.OfferInput("Launch", null, false, 10, 3, true, null, null, now().plusDays(5)),
                "Launch", now())).hasMessageContaining("needs a code");
        assertThatThrownBy(() -> offers.create(ADMIN, new OfferService.OfferInput("Launch", null, true, 10, 3, true, List.of("FREE"), null, now().plusDays(5)),
                "Launch", now())).hasMessageContaining("Free");

        OfferService.OfferRow row = launch(null, true);
        assertThat(row.status()).isEqualTo("ACTIVE");
        assertThat(row.plans()).containsExactly("ESSENTIALS", "PRO", "AGENCY");
        assertThatThrownBy(() -> launch(null, true)).isInstanceOf(ConflictException.class); // one automatic offer at a time
        assertThat(adminActions.findTop50ByTargetTypeAndTargetIdOrderByCreatedAtDesc("offer", String.valueOf(row.id())))
                .extracting(AdminAction::getAction).containsExactly("OFFER_CREATE");
        OfferService.OfferRow ended = offers.end(ADMIN, row.id(), "Enough sign-ups", now());
        assertThat(ended.status()).isEqualTo("ENDED");
        assertThat(adminActions.findTop50ByTargetTypeAndTargetIdOrderByCreatedAtDesc("offer", String.valueOf(row.id())))
                .extracting(AdminAction::getAction).containsExactly("OFFER_END", "OFFER_CREATE");
        assertThatThrownBy(() -> offers.end(ADMIN, row.id(), "Again", now())).isInstanceOf(ConflictException.class);
    }

    // ------------------------------------------------------------------ BI-10 grandfathering

    @Test
    void existingSubscribersKeepTheirLimitsWhenNewSignUpsGetTheRecommendedOnes() {
        User creatorBefore = account();
        pay(creatorBefore, "ESSENTIALS", "MONTHLY");
        User agencyBefore = account();
        pay(agencyBefore, "AGENCY", "MONTHLY");
        // A row from before terms were recorded (or written by the previous release during a deploy).
        User legacy = account();
        pay(legacy, "PRO", "MONTHLY");
        Subscription ls = subscriptions.findByUserId(legacy.getId()).orElseThrow();
        ls.setTerms(null);
        subscriptions.save(ls);
        int creatorDestinationsBefore = plans.getPlanConfig(PlanType.ESSENTIALS).getMaxDestinations();
        int proDestinationsBefore = plans.getPlanConfig(PlanType.PRO).getMaxDestinations();

        AdminService.LimitsPreview preview = admin.recommendedLimitsPreview();
        assertThat(preview.alreadyApplied()).isFalse();
        AdminService.LimitsPreviewRow creatorRow = preview.plans().stream().filter(r -> r.plan().equals("ESSENTIALS")).findFirst().orElseThrow();
        assertThat(creatorRow.changes()).contains("destinations " + creatorDestinationsBefore + " → 1");
        assertThat(creatorRow.protectedSubscribers()).isGreaterThanOrEqualTo(1);
        assertThatThrownBy(() -> admin.applyRecommendedLimits(ADMIN, "")).hasMessageContaining("Say why");

        admin.applyRecommendedLimits(ADMIN, "Margins for new sign-ups (owner decision 2026-10-28)");
        assertThat(admin.recommendedLimitsPreview().alreadyApplied()).isTrue();
        assertThatThrownBy(() -> admin.applyRecommendedLimits(ADMIN, "again")).isInstanceOf(ConflictException.class);
        assertThat(adminActions.findTop50ByTargetTypeAndTargetIdOrderByCreatedAtDesc("setting", "plan_configs"))
                .extracting(AdminAction::getAction).contains("PLAN_LIMITS_NEW_SIGNUPS");

        // New sign-ups get the new limits.
        PlanConfig creatorNow = plans.getPlanConfig(PlanType.ESSENTIALS);
        assertThat(creatorNow.getMaxDestinations()).isEqualTo(1);
        assertThat(creatorNow.getMonthlyTrafficGb()).isEqualTo(2_000);
        assertThat(plans.getPlanConfig(PlanType.AGENCY).getMaxResolution()).isEqualTo(1080);
        assertThat(plans.getPlanConfig(PlanType.FREE).getMonthlyTrafficGb()).isEqualTo(50);
        User creatorAfter = account();
        pay(creatorAfter, "ESSENTIALS", "MONTHLY");
        PlanConfig newTerms = plans.limitsFor(reload(creatorAfter));
        assertThat(newTerms.getMaxDestinations()).isEqualTo(1);
        assertThat(newTerms.getMonthlyTrafficGb()).isEqualTo(2_000);
        assertThat(fairUse.status(reload(creatorAfter), now()).allowanceGb()).isEqualTo(2_000.0);

        // Existing subscribers keep what they bought, also after renewing.
        PlanConfig kept = plans.limitsFor(reload(creatorBefore));
        assertThat(kept.getMaxDestinations()).isEqualTo(creatorDestinationsBefore);
        assertThat(kept.getMonthlyTrafficGb()).isNull();
        assertThat(fairUse.status(reload(creatorBefore), now()).allowanceGb()).isNull();
        assertThat(plans.limitsFor(reload(agencyBefore)).getMaxResolution()).isEqualTo(2160);
        assertThat(plans.limitsFor(reload(legacy)).getMaxDestinations()).isEqualTo(proDestinationsBefore); // snapshotted by the apply step
        nearEnd(creatorBefore);
        pay(creatorBefore, "ESSENTIALS", "MONTHLY");
        assertThat(plans.limitsFor(reload(creatorBefore)).getMaxDestinations()).isEqualTo(creatorDestinationsBefore);
        assertThat(billing.overview(creatorBefore.getEmail()).usage().streamsMax()).isEqualTo(kept.getMaxActiveStreams());

        // An upgrade never takes away what the old terms gave; a lapse and a new purchase gets today's limits.
        Subscription cs = subscriptions.findByUserId(creatorBefore.getId()).orElseThrow();
        cs.setCurrentPeriodStart(now().minusDays(10));
        cs.setCurrentPeriodEnd(now().plusDays(20));
        subscriptions.save(cs);
        pay(creatorBefore, "PRO", "MONTHLY");
        assertThat(plans.limitsFor(reload(creatorBefore)).getMaxDestinations()).isEqualTo(Math.max(2, creatorDestinationsBefore));
        assertThat(plans.limitsFor(reload(creatorBefore)).getMaxFps()).isEqualTo(60);

        // A later raise of a plan's limits reaches grandfathered subscribers too (the more generous side wins).
        PlanConfig agency = planConfigs.findById(PlanType.AGENCY).orElseThrow();
        agency.setMaxActiveStreams(12);
        plans.updatePlan(agency);
        assertThat(plans.limitsFor(reload(agencyBefore)).getMaxActiveStreams()).isEqualTo(12);
        assertThat(plans.limitsFor(reload(agencyBefore)).getMaxResolution()).isEqualTo(2160);
    }

    @Test
    void termsMergeTakesTheMoreGenerousSide() {
        PlanConfig old = new PlanConfig();
        old.applyDefaults(PlanType.PRO);
        old.setMonthlyTrafficGb(null);
        PlanTerms t = PlanTerms.of(old, now());
        PlanConfig next = new PlanConfig();
        next.applyDefaults(PlanType.PRO);
        next.setMaxDestinations(2);
        next.setMonthlyTrafficGb(6_000);
        next.setMaxStorageBytes(old.getMaxStorageBytes() * 2);
        PlanConfig merged = t.merge(next);
        assertThat(merged.getMaxDestinations()).isEqualTo(old.getMaxDestinations());
        assertThat(merged.getMonthlyTrafficGb()).isNull();
        assertThat(merged.getMaxStorageBytes()).isEqualTo(next.getMaxStorageBytes());
        assertThat(next.getMaxDestinations()).isEqualTo(2); // the cached row isn't changed
        PlanConfig free = new PlanConfig();
        free.applyDefaults(PlanType.FREE);
        PlanTerms ft = PlanTerms.of(free, now());
        PlanConfig unlimitedHours = new PlanConfig();
        unlimitedHours.applyDefaults(PlanType.FREE);
        unlimitedHours.setMonthlyHours(0);
        assertThat(ft.merge(unlimitedHours).getMonthlyHours()).isZero();
    }
}
