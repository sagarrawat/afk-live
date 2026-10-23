package com.afklive.streamer;

import com.afklive.streamer.exception.ConflictException;
import com.afklive.streamer.model.BillingProfile;
import com.afklive.streamer.model.Invoice;
import com.afklive.streamer.model.PaymentAudit;
import com.afklive.streamer.model.PlanType;
import com.afklive.streamer.model.Subscription;
import com.afklive.streamer.model.User;
import com.afklive.streamer.payment.PaymentGateway;
import com.afklive.streamer.repository.InvoiceRepository;
import com.afklive.streamer.repository.NotificationRepository;
import com.afklive.streamer.repository.PaymentAuditRepository;
import com.afklive.streamer.repository.SubscriptionRepository;
import com.afklive.streamer.repository.UsageRepository;
import com.afklive.streamer.repository.UserRepository;
import com.afklive.streamer.service.BillingService;
import com.afklive.streamer.service.StreamService;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** BI-1..BI-6: plans, checkout quotes, payments, invoices, period ends, usage metering. */
@SpringBootTest(properties = {"app.billing.seller-state=29", "app.billing.seller-gstin=29ABCDE1234F1Z5"})
class BillingServiceTest extends PostgresIntegrationTest {

    @MockitoBean PaymentGateway gateway;
    @Autowired BillingService billing;
    @Autowired UserRepository users;
    @Autowired SubscriptionRepository subscriptions;
    @Autowired PaymentAuditRepository audits;
    @Autowired InvoiceRepository invoices;
    @Autowired UsageRepository usage;
    @Autowired NotificationRepository notifications;
    @Autowired StreamService streams;
    @Autowired WebApplicationContext context;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        when(gateway.createCheckout(anyString(), anyLong(), anyString())).thenReturn("https://pay.test/checkout");
    }

    private User account() {
        User u = new User("bill-" + UUID.randomUUID() + "@example.com");
        u.setPlanType(PlanType.FREE);
        return users.save(u);
    }

    private static ZonedDateTime now() {
        return ZonedDateTime.now(ZoneId.of("UTC"));
    }

    /** Pays for a checkout as the gateway webhook would. */
    private PaymentAudit pay(User u, String plan, String cycle) {
        BillingService.Checkout c = billing.checkout(u.getEmail(), plan, cycle);
        PaymentAudit a = audits.findByTransactionId(c.orderId()).orElseThrow();
        a.setStatus("COMPLETED");
        audits.save(a);
        billing.applyPaid(a);
        return a;
    }

    private Subscription sub(User u) {
        return subscriptions.findByUserId(u.getId()).orElseThrow();
    }

    /** Moves the user's current period so it started {@code daysAgo} and lasts {@code days}. */
    private Subscription shiftPeriod(User u, int daysAgo, int days) {
        Subscription s = sub(u);
        s.setCurrentPeriodStart(now().minusDays(daysAgo));
        s.setCurrentPeriodEnd(now().minusDays(daysAgo).plusDays(days));
        return subscriptions.save(s);
    }

    // ------------------------------------------------------------------ catalog

    @Test
    void catalogListsEveryPlanWithPrices() {
        var inr = billing.catalog("INR");
        assertThat(inr).extracting(BillingService.PlanView::id).containsExactly("FREE", "ESSENTIALS", "PRO", "AGENCY");
        assertThat(inr.get(0).monthlyLabel()).isEqualTo("Free");
        assertThat(inr.get(1).monthly()).isEqualTo(49_900);
        assertThat(inr.get(1).monthlyLabel()).isEqualTo("₹499");
        assertThat(inr.get(0).watermark()).isTrue();
        assertThat(inr.get(0).monthlyHours()).isEqualTo(10);
        assertThat(billing.catalog("USD").get(1).monthlyLabel()).isEqualTo("$9");
        assertThat(BillingService.priceLabel(750, "USD")).isEqualTo("$7.50"); // cents kept when there are any
        assertThat(BillingService.priceLabel(399_900, "INR")).isEqualTo("₹3,999");
    }

    @Test
    void plansEndpointIsPublic() throws Exception {
        mvc.perform(get("/api/billing/plans")).andExpect(status().isOk())
                .andExpect(jsonPath("$[1].name").value("Creator"));
    }

    // ------------------------------------------------------------------ checkout and payment

    @Test
    void newSubscriptionStartsNowAndIssuesAnInvoice() {
        User u = account();
        BillingService.Quote q = billing.quote(u.getEmail(), "ESSENTIALS", "MONTHLY");
        assertThat(q.kind()).isEqualTo(BillingService.KIND_NEW);
        assertThat(q.amount()).isEqualTo(49_900);

        PaymentAudit paid = pay(u, "ESSENTIALS", "MONTHLY");
        verify(gateway).createCheckout(eq(paid.getTransactionId()), eq(49_900L), anyString());

        Subscription s = sub(u);
        assertThat(s.getStatus()).isEqualTo(Subscription.Status.ACTIVE);
        assertThat(s.getPlanType()).isEqualTo(PlanType.ESSENTIALS);
        assertThat(s.getCurrentPeriodEnd()).isAfter(now().plusDays(27));
        assertThat(users.findById(u.getId()).orElseThrow().getPlanType()).isEqualTo(PlanType.ESSENTIALS);

        Invoice inv = invoices.findByPaymentOrderId(paid.getTransactionId()).orElseThrow();
        assertThat(inv.getTotal()).isEqualTo(49_900);
        assertThat(inv.getNumber()).matches("AFK/\\d{4}-\\d{2}/\\d{6}");
        assertThat(inv.getTaxableAmount() + inv.getCgst() + inv.getSgst() + inv.getIgst()).isEqualTo(49_900);
        // no billing profile: place of supply unknown, so IGST
        assertThat(inv.getIgst()).isPositive();
        assertThat(notifications.findAll()).anyMatch(n -> n.getUserId().equals(u.getId()) && "PAYMENT_RECEIVED".equals(n.getType()));
    }

    @Test
    void applyingTheSamePaymentTwiceIssuesOneInvoice() {
        User u = account();
        PaymentAudit paid = pay(u, "ESSENTIALS", "MONTHLY");
        billing.applyPaid(paid);
        assertThat(invoices.findAllByUserIdOrderByIssuedAtDesc(u.getId())).hasSize(1);
    }

    @Test
    void freePlanCannotBeBought() {
        User u = account();
        assertThatThrownBy(() -> billing.quote(u.getEmail(), "FREE", "MONTHLY")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> billing.quote(u.getEmail(), "NOPE", "MONTHLY")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> billing.quote(u.getEmail(), "PRO", "WEEKLY")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void upgradeCreditsTheUnusedPartOfThePeriod() {
        User u = account();
        pay(u, "ESSENTIALS", "MONTHLY");
        shiftPeriod(u, 15, 30); // half used

        BillingService.Quote q = billing.quote(u.getEmail(), "PRO", "MONTHLY");
        assertThat(q.kind()).isEqualTo(BillingService.KIND_UPGRADE);
        assertThat(q.credit()).isBetween(24_900L, 25_000L);
        assertThat(q.amount()).isEqualTo(q.price() - q.credit());

        PaymentAudit paid = pay(u, "PRO", "MONTHLY");
        assertThat(paid.getAmount()).isEqualTo(q.amount());
        Subscription s = sub(u);
        assertThat(s.getPlanType()).isEqualTo(PlanType.PRO);
        assertThat(s.getCurrentPeriodStart()).isAfter(now().minusMinutes(1)); // upgrades start a new period now
        assertThat(invoices.findByPaymentOrderId(paid.getTransactionId()).orElseThrow().getDescription()).contains("credit for unused time");
    }

    @Test
    void upgradeNeverChargesLessThanTheMinimum() {
        User u = account();
        pay(u, "PRO", "YEARLY"); // a fresh year of Pro is worth more than a month of Agency
        BillingService.Quote q = billing.quote(u.getEmail(), "AGENCY", "MONTHLY");
        assertThat(q.amount()).isEqualTo(100);
        assertThat(q.credit() + q.amount()).isEqualTo(q.price());
    }

    @Test
    void smallerPlansGoThroughDowngradeNotCheckout() {
        User u = account();
        pay(u, "PRO", "MONTHLY");
        assertThatThrownBy(() -> billing.quote(u.getEmail(), "ESSENTIALS", "MONTHLY")).isInstanceOf(ConflictException.class);
    }

    @Test
    void renewalOnlyOpensNearThePeriodEndAndContinuesFromIt() {
        User u = account();
        pay(u, "ESSENTIALS", "MONTHLY");
        assertThatThrownBy(() -> billing.quote(u.getEmail(), "ESSENTIALS", "MONTHLY")).isInstanceOf(ConflictException.class);

        shiftPeriod(u, 25, 30); // ends in 5 days
        Subscription before = sub(u);
        BillingService.Quote q = billing.quote(u.getEmail(), "ESSENTIALS", "MONTHLY");
        assertThat(q.kind()).isEqualTo(BillingService.KIND_RENEW);
        pay(u, "ESSENTIALS", "MONTHLY");
        Subscription after = sub(u);
        assertThat(after.getCurrentPeriodStart()).isEqualTo(before.getCurrentPeriodEnd());
        assertThat(after.getCurrentPeriodEnd()).isEqualTo(before.getCurrentPeriodEnd().plusMonths(1));
    }

    // ------------------------------------------------------------------ period ends

    @Test
    void reminderIsSentOnceBeforeThePeriodEnds() {
        User u = account();
        pay(u, "ESSENTIALS", "MONTHLY");
        shiftPeriod(u, 28, 30);
        billing.runPeriodEnds(now());
        billing.runPeriodEnds(now());
        assertThat(notifications.findAll()).filteredOn(n -> n.getUserId().equals(u.getId()) && "RENEWAL_DUE".equals(n.getType()))
                .hasSize(1);
        assertThat(sub(u).getStatus()).isEqualTo(Subscription.Status.ACTIVE);
    }

    @Test
    void unpaidPeriodGetsGraceThenMovesToFree() {
        User u = account();
        pay(u, "ESSENTIALS", "MONTHLY");
        shiftPeriod(u, 31, 30); // ended a day ago
        billing.runPeriodEnds(now());
        assertThat(sub(u).getStatus()).isEqualTo(Subscription.Status.PAST_DUE);
        assertThat(users.findById(u.getId()).orElseThrow().getPlanType()).isEqualTo(PlanType.ESSENTIALS);

        // paying during grace renews from now
        BillingService.Quote q = billing.quote(u.getEmail(), "ESSENTIALS", "MONTHLY");
        assertThat(q.kind()).isEqualTo(BillingService.KIND_RENEW);

        billing.runPeriodEnds(now().plus(BillingService.GRACE));
        assertThat(sub(u).getStatus()).isEqualTo(Subscription.Status.CANCELED);
        assertThat(users.findById(u.getId()).orElseThrow().getPlanType()).isEqualTo(PlanType.FREE);
    }

    @Test
    void cancelTakesEffectAtPeriodEndAndCanBeUndone() {
        User u = account();
        pay(u, "ESSENTIALS", "MONTHLY");
        billing.cancel(u.getEmail());
        assertThat(sub(u).isCancelAtPeriodEnd()).isTrue();
        billing.resume(u.getEmail());
        assertThat(sub(u).isCancelAtPeriodEnd()).isFalse();

        billing.cancel(u.getEmail());
        shiftPeriod(u, 31, 30);
        billing.runPeriodEnds(now());
        assertThat(sub(u).getStatus()).isEqualTo(Subscription.Status.CANCELED);
        assertThat(users.findById(u.getId()).orElseThrow().getPlanType()).isEqualTo(PlanType.FREE);
    }

    @Test
    void downgradeAppliesAtPeriodEnd() {
        User u = account();
        pay(u, "PRO", "MONTHLY");
        assertThatThrownBy(() -> billing.downgrade(u.getEmail(), "AGENCY", "MONTHLY")).isInstanceOf(IllegalArgumentException.class);
        billing.downgrade(u.getEmail(), "ESSENTIALS", "MONTHLY");
        assertThat(users.findById(u.getId()).orElseThrow().getPlanType()).isEqualTo(PlanType.PRO);
        assertThat(billing.overview(u.getEmail()).nextCharge().amount()).isEqualTo(49_900);

        shiftPeriod(u, 31, 30);
        billing.runPeriodEnds(now());
        assertThat(sub(u).getPlanType()).isEqualTo(PlanType.ESSENTIALS);
        assertThat(users.findById(u.getId()).orElseThrow().getPlanType()).isEqualTo(PlanType.ESSENTIALS);
    }

    @Test
    void cancellingOnFreeIsAConflict() {
        User u = account();
        assertThatThrownBy(() -> billing.cancel(u.getEmail())).isInstanceOf(ConflictException.class);
    }

    // ------------------------------------------------------------------ profile and invoices

    @Test
    void gstinIsValidatedAndSetsThePlaceOfSupply() {
        User u = account();
        BillingProfile bad = new BillingProfile();
        bad.setGstin("12345");
        assertThatThrownBy(() -> billing.saveProfile(u.getEmail(), bad)).isInstanceOf(IllegalArgumentException.class);

        BillingProfile p = new BillingProfile();
        p.setLegalName("Aarti Studios");
        p.setGstin("29abcde1234f1z5");
        p.setStateCode("07");
        BillingProfile saved = billing.saveProfile(u.getEmail(), p);
        assertThat(saved.getGstin()).isEqualTo("29ABCDE1234F1Z5");
        assertThat(saved.getStateCode()).isEqualTo("29");

        // same state as the seller: CGST + SGST
        PaymentAudit paid = pay(u, "ESSENTIALS", "MONTHLY");
        Invoice inv = invoices.findByPaymentOrderId(paid.getTransactionId()).orElseThrow();
        assertThat(inv.getIgst()).isZero();
        assertThat(inv.getCgst()).isPositive();
        assertThat(inv.getCustomerName()).isEqualTo("Aarti Studios");
        assertThat(inv.getCustomerGstin()).isEqualTo("29ABCDE1234F1Z5");
    }

    @Test
    void invoicePdfIsOnlyForItsOwner() throws Exception {
        User u = account();
        User other = account();
        PaymentAudit paid = pay(u, "ESSENTIALS", "MONTHLY");
        Invoice inv = invoices.findByPaymentOrderId(paid.getTransactionId()).orElseThrow();

        byte[] pdf = mvc.perform(get("/api/billing/invoices/" + inv.getId() + ".pdf").with(user(u.getEmail())))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");

        mvc.perform(get("/api/billing/invoices/" + inv.getId() + ".pdf").with(user(other.getEmail())))
                .andExpect(status().isNotFound());
    }

    @Test
    void invoiceNumbersAreSequential() {
        User a = account();
        User b = account();
        Invoice first = invoices.findByPaymentOrderId(pay(a, "ESSENTIALS", "MONTHLY").getTransactionId()).orElseThrow();
        Invoice second = invoices.findByPaymentOrderId(pay(b, "ESSENTIALS", "MONTHLY").getTransactionId()).orElseThrow();
        int n1 = Integer.parseInt(first.getNumber().substring(first.getNumber().lastIndexOf('/') + 1));
        int n2 = Integer.parseInt(second.getNumber().substring(second.getNumber().lastIndexOf('/') + 1));
        assertThat(n2).isEqualTo(n1 + 1);
    }

    // ------------------------------------------------------------------ endpoints

    @Test
    void billingEndpointsWorkForTheSignedInUser() throws Exception {
        User u = account();
        mvc.perform(get("/api/billing").with(user(u.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan").value("FREE"))
                .andExpect(jsonPath("$.usage.hoursIncluded").value(10));
        mvc.perform(get("/api/billing/quote").param("plan", "PRO").with(user(u.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(149_900));
        mvc.perform(post("/api/billing/checkout").with(user(u.getEmail())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"plan\":\"PRO\",\"cycle\":\"YEARLY\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkoutUrl").value("https://pay.test/checkout"));
        mvc.perform(put("/api/billing/profile").with(user(u.getEmail())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"legalName\":\"Me\",\"stateCode\":\"27\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stateCode").value("27"));
        mvc.perform(post("/api/billing/cancel").with(user(u.getEmail())).with(csrf()))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/billing")).andExpect(r -> assertThat(r.getResponse().getStatus()).isNotEqualTo(200));
    }

    // ------------------------------------------------------------------ usage (free hours)

    @Test
    void freeHoursAreMeteredPerMonth() {
        User u = account();
        String month = BillingService.month(now());
        assertThat(streams.freeHoursUsedUp(u)).isFalse();
        usage.add(u.getId(), month, 9 * 3600L);
        usage.add(u.getId(), month, 3599L);
        assertThat(streams.freeHoursUsedUp(u)).isFalse();
        usage.add(u.getId(), month, 1L);
        assertThat(streams.freeHoursUsedUp(u)).isTrue();

        // paid plans have no monthly limit
        u.setPlanType(PlanType.ESSENTIALS);
        assertThat(streams.freeHoursUsedUp(u)).isFalse();
        assertThat(billing.overview(u.getEmail()).usage().hoursUsed()).isEqualTo(10.0);
    }
}
