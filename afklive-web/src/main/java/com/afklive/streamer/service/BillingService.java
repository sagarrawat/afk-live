package com.afklive.streamer.service;

import com.afklive.streamer.exception.ConflictException;
import com.afklive.streamer.model.*;
import com.afklive.streamer.payment.PaymentGateway;
import com.afklive.streamer.repository.*;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Plans and subscriptions (BI-1, BI-2, BI-5, BI-6):
 * <ul>
 *   <li>Upgrades apply at once: the unused part of the current period is credited against the new
 *       plan, and a new period starts now.</li>
 *   <li>Downgrades and cancellations take effect at the end of the period; access is kept until then.</li>
 *   <li>Renewals are paid from a reminder a few days before the period ends (UPI and cards via the
 *       payment provider); an unpaid period gets {@link #GRACE} of grace, then the account moves to Free.</li>
 * </ul>
 * Every change of plan comes from a verified payment or an end-of-period rule, never from the client.
 */
@Service
@Slf4j
public class BillingService {

    public static final Duration GRACE = Duration.ofDays(3);
    static final Duration REMIND_BEFORE = Duration.ofDays(3);
    /** Smallest amount the payment provider accepts (₹1). */
    static final long MIN_CHARGE = 100;
    private static final Pattern GSTIN = Pattern.compile("\\d{2}[A-Z]{5}\\d{4}[A-Z][1-9A-Z]Z[0-9A-Z]");

    public static final String KIND_NEW = "NEW";
    public static final String KIND_UPGRADE = "UPGRADE";
    public static final String KIND_RENEW = "RENEW";

    private final SubscriptionRepository subscriptions;
    private final PaymentAuditRepository audits;
    private final PaymentGateway gateway;
    private final UserRepository users;
    private final PlanService plans;
    private final InvoiceService invoices;
    private final InvoiceRepository invoiceRepository;
    private final BillingProfileRepository profiles;
    private final UsageRepository usage;
    private final StreamJobRepository jobs;
    private final NotificationService notifications;
    private final String baseUrl;
    private final String role;
    private final org.springframework.transaction.support.TransactionTemplate tx;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "billing-renewals");
        t.setDaemon(true);
        return t;
    });

    public BillingService(SubscriptionRepository subscriptions, PaymentAuditRepository audits, PaymentGateway gateway,
                          UserRepository users, PlanService plans, InvoiceService invoices, InvoiceRepository invoiceRepository,
                          BillingProfileRepository profiles, UsageRepository usage, StreamJobRepository jobs,
                          NotificationService notifications,
                          @Value("${app.base-url:http://localhost:8080}") String baseUrl,
                          @Value("${app.streaming.role:all}") String role,
                          org.springframework.transaction.PlatformTransactionManager txManager) {
        this.tx = new org.springframework.transaction.support.TransactionTemplate(txManager);
        this.subscriptions = subscriptions;
        this.audits = audits;
        this.gateway = gateway;
        this.users = users;
        this.plans = plans;
        this.invoices = invoices;
        this.invoiceRepository = invoiceRepository;
        this.profiles = profiles;
        this.usage = usage;
        this.jobs = jobs;
        this.notifications = notifications;
        this.baseUrl = baseUrl;
        this.role = role;
    }

    // ------------------------------------------------------------------ catalog (MK-2: the pricing page reads this)

    public record PlanView(String id, String name, int concurrentStreams, int maxHeight, int maxFps, long storageBytes,
                           int monthlyHours, boolean watermark, int destinationsPerStream, String currency,
                           long monthly, long yearly, String monthlyLabel, String yearlyLabel) {
    }

    /** "₹1,499" or "$9": whole amounts without ".00"; "$7.50" keeps its cents. */
    public static String priceLabel(long minor, String currency) {
        String symbol = "USD".equalsIgnoreCase(currency) ? "$" : "₹";
        String amount = minor % 100 == 0 ? String.format(java.util.Locale.ENGLISH, "%,d", minor / 100)
                : String.format(java.util.Locale.ENGLISH, "%,.2f", minor / 100.0);
        return symbol + amount;
    }

    public List<PlanView> catalog(String currency) {
        String cur = "USD".equalsIgnoreCase(currency) ? "USD" : "INR";
        List<PlanView> out = new ArrayList<>();
        for (PlanType t : PlanType.values()) {
            PlanConfig c = plans.getPlanConfig(t);
            long m = c.price(cur, false);
            long y = c.price(cur, true);
            out.add(new PlanView(t.name(), c.getDisplayName(), c.getMaxActiveStreams(), c.getMaxResolution(), c.getMaxFps(),
                    c.getMaxStorageBytes(), c.getMonthlyHours(), c.isWatermark(), c.getMaxDestinations(), cur, m, y,
                    m == 0 ? "Free" : priceLabel(m, cur), y == 0 ? "Free" : priceLabel(y, cur)));
        }
        return out;
    }

    // ------------------------------------------------------------------ overview (BI-6)

    public record Usage(double hoursUsed, int hoursIncluded, long storageUsed, long storageMax, int streamsLive, int streamsMax) {
    }

    public record NextCharge(ZonedDateTime date, long amount, String currency, String description) {
    }

    public record InvoiceView(Long id, String number, String description, long total, String currency, ZonedDateTime issuedAt) {
    }

    public record Overview(String plan, String planName, String cycle, String status, ZonedDateTime periodEnd,
                           boolean cancelAtPeriodEnd, String pendingPlan, Usage usage, NextCharge nextCharge,
                           List<InvoiceView> invoices, double unpaidBalance) {
    }

    public Overview overview(String email) {
        User u = user(email);
        PlanConfig c = plans.getPlanConfig(u.getPlanType());
        Optional<Subscription> sub = subscriptions.findByUserId(u.getId()).filter(s -> s.getStatus() != Subscription.Status.CANCELED);
        Usage use = new Usage(Math.round(usage.secondsIn(u.getId(), month(now())) / 36.0) / 100.0, c.getMonthlyHours(),
                u.getUsedStorageBytes(), c.getMaxStorageBytes(), (int) jobs.countByUserEmailAndIsLiveTrue(email), c.getMaxActiveStreams());
        NextCharge next = null;
        if (sub.isPresent() && !sub.get().isCancelAtPeriodEnd()) {
            Subscription s = sub.get();
            PlanType nextPlan = s.getPendingPlan() != null ? s.getPendingPlan() : s.getPlanType();
            Subscription.Cycle nextCycle = s.getPendingCycle() != null ? s.getPendingCycle() : s.getCycle();
            if (nextPlan != PlanType.FREE) {
                long amount = plans.getPlanConfig(nextPlan).price(s.getCurrency(), nextCycle == Subscription.Cycle.YEARLY);
                next = new NextCharge(s.getCurrentPeriodEnd(), amount, s.getCurrency(),
                        plans.getPlanConfig(nextPlan).getDisplayName() + " (" + nextCycle.name().toLowerCase() + ")");
            }
        }
        List<InvoiceView> inv = invoiceRepository.findAllByUserIdOrderByIssuedAtDesc(u.getId()).stream()
                .map(i -> new InvoiceView(i.getId(), i.getNumber(), i.getDescription(), i.getTotal(), i.getCurrency(), i.getIssuedAt()))
                .toList();
        return new Overview(u.getPlanType().name(), c.getDisplayName(), sub.map(s -> s.getCycle().name()).orElse(null),
                sub.map(s -> s.getStatus().name()).orElse(u.getPlanType() == PlanType.FREE ? "FREE" : "ACTIVE"),
                sub.map(Subscription::getCurrentPeriodEnd).orElse(null), sub.map(Subscription::isCancelAtPeriodEnd).orElse(false),
                sub.map(s -> s.getPendingPlan() == null ? null : s.getPendingPlan().name()).orElse(null), use, next, inv,
                u.getUnpaidBalance());
    }

    // ------------------------------------------------------------------ checkout

    public record Quote(String kind, PlanType plan, Subscription.Cycle cycle, String currency, long price, long credit,
                        long amount, ZonedDateTime periodStart, ZonedDateTime periodEnd) {
    }

    /** What paying for {@code plan}/{@code cycle} now would cost (new, upgrade with credit, or renewal). */
    public Quote quote(String email, String planId, String cycleId) {
        User u = user(email);
        PlanType plan = paidPlan(planId);
        Subscription.Cycle cycle = cycle(cycleId);
        Optional<Subscription> current = subscriptions.findByUserId(u.getId()).filter(s -> s.getStatus() != Subscription.Status.CANCELED);
        String currency = current.map(Subscription::getCurrency).orElse("INR");
        long price = plans.getPlanConfig(plan).price(currency, cycle == Subscription.Cycle.YEARLY);
        if (price <= 0) {
            throw new IllegalArgumentException("This plan isn't for sale yet.");
        }
        ZonedDateTime now = now();
        if (current.isEmpty()) {
            return new Quote(KIND_NEW, plan, cycle, currency, price, 0, price, now, end(now, cycle));
        }
        Subscription s = current.get();
        boolean same = s.getPlanType() == plan && s.getCycle() == cycle;
        if (same) {
            boolean renewable = s.getStatus() == Subscription.Status.PAST_DUE
                    || !s.getCurrentPeriodEnd().isAfter(now.plus(Duration.ofDays(7)));
            if (!renewable) {
                throw new ConflictException("You're on this plan until " + day(s.getCurrentPeriodEnd()) + ". Renewal opens 7 days before.");
            }
            ZonedDateTime start = s.getStatus() == Subscription.Status.PAST_DUE || s.getCurrentPeriodEnd().isBefore(now)
                    ? now : s.getCurrentPeriodEnd();
            return new Quote(KIND_RENEW, plan, cycle, currency, price, 0, price, start, end(start, cycle));
        }
        if (!isUpgrade(s, plan, cycle)) {
            throw new ConflictException("Moving to a smaller plan or a monthly cycle happens at the end of your period: use Downgrade.");
        }
        long credit = s.getStatus() == Subscription.Status.ACTIVE ? credit(s, now) : 0;
        long amount = Math.max(MIN_CHARGE, price - credit);
        return new Quote(KIND_UPGRADE, plan, cycle, currency, price, Math.min(credit, price - MIN_CHARGE), amount, now, end(now, cycle));
    }

    /** Value of the unused part of the current period (what an upgrade credits). */
    long credit(Subscription s, ZonedDateTime now) {
        long paid = plans.getPlanConfig(s.getPlanType()).price(s.getCurrency(), s.getCycle() == Subscription.Cycle.YEARLY);
        long total = Duration.between(s.getCurrentPeriodStart(), s.getCurrentPeriodEnd()).toSeconds();
        long left = Duration.between(now, s.getCurrentPeriodEnd()).toSeconds();
        if (total <= 0 || left <= 0) {
            return 0;
        }
        return paid * left / total;
    }

    static boolean isUpgrade(Subscription s, PlanType plan, Subscription.Cycle cycle) {
        if (plan.rank() > s.getPlanType().rank()) {
            return true;
        }
        return plan == s.getPlanType() && cycle == Subscription.Cycle.YEARLY && s.getCycle() == Subscription.Cycle.MONTHLY;
    }

    public record Checkout(String orderId, String checkoutUrl, Quote quote) {
    }

    /** Creates an order for the quoted amount and returns the payment page (UPI, cards). */
    @Transactional
    public Checkout checkout(String email, String planId, String cycleId) {
        User u = user(email);
        Quote q = quote(email, planId, cycleId);
        String orderId = "MT" + UUID.randomUUID().toString().replace("-", "").substring(0, 30);
        PaymentAudit audit = new PaymentAudit(orderId, email, q.amount(), "INITIATED");
        audit.setUser(u);
        audit.setPlanId(q.plan().name());
        audit.setKind(q.kind());
        audit.setBillingCycle(q.cycle().name());
        audit.setCurrency(q.currency());
        audit.setCredit(q.credit());
        audits.save(audit);
        String url = gateway.createCheckout(orderId, q.amount(), baseUrl + "/studio?view=settings&payment_order=" + orderId);
        log.info("Checkout {} for {}: {} {} {} amount={} credit={}", orderId, email, q.kind(), q.plan(), q.cycle(), q.amount(), q.credit());
        return new Checkout(orderId, url, q);
    }

    /**
     * Applies a verified payment (called once per order, inside the payment's lock). Upgrades and new
     * subscriptions start now; renewals continue from the period end; then the invoice is issued.
     */
    @Transactional
    public void applyPaid(PaymentAudit audit) {
        User u = users.findByEmail(audit.getMerchantUserId()).orElseThrow();
        PlanType plan = PlanType.valueOf(audit.getPlanId());
        Subscription.Cycle cycle = Subscription.Cycle.valueOf(audit.getBillingCycle() == null ? "MONTHLY" : audit.getBillingCycle());
        ZonedDateTime now = now();
        Subscription s = subscriptions.lockByUserId(u.getId()).orElseGet(() -> {
            Subscription fresh = new Subscription();
            fresh.setUserId(u.getId());
            return fresh;
        });
        ZonedDateTime start = now;
        if (KIND_RENEW.equals(audit.getKind()) && s.getStatus() == Subscription.Status.ACTIVE && s.getCurrentPeriodEnd() != null
                && s.getCurrentPeriodEnd().isAfter(now)) {
            start = s.getCurrentPeriodEnd(); // paid early: the new period follows the current one
        }
        s.setPlanType(plan);
        s.setCycle(cycle);
        s.setCurrency(audit.getCurrency() == null ? "INR" : audit.getCurrency());
        s.setStatus(Subscription.Status.ACTIVE);
        s.setCurrentPeriodStart(start);
        s.setCurrentPeriodEnd(end(start, cycle));
        s.setCancelAtPeriodEnd(false);
        s.setPendingPlan(null);
        s.setPendingCycle(null);
        s.setRenewalRemindedAt(null);
        subscriptions.save(s);
        setPlan(u, plan, s.getCurrentPeriodEnd());
        String what = plans.getPlanConfig(plan).getDisplayName() + " plan, " + cycle.name().toLowerCase()
                + (audit.getCredit() > 0 ? " (credit for unused time: " + InvoiceService.money(audit.getCredit(), s.getCurrency()) + ")" : "");
        Invoice inv = invoices.issue(u.getId(), audit.getTransactionId(), "AFK Live " + what, s.getCurrentPeriodStart(),
                s.getCurrentPeriodEnd(), s.getCurrency(), audit.getAmount());
        notifications.notify(u, new NotificationService.Alert("PAYMENT_RECEIVED", Notification.Level.SUCCESS, null,
                "Payment received: " + plans.getPlanConfig(plan).getDisplayName(),
                "You're on " + what + " until " + day(s.getCurrentPeriodEnd()) + ". Invoice " + inv.getNumber() + " is in Billing.",
                "/settings#billing", null, null, true));
    }

    // ------------------------------------------------------------------ end-of-period changes (BI-5)

    @Transactional
    public void downgrade(String email, String planId, String cycleId) {
        User u = user(email);
        Subscription s = activeSub(u);
        PlanType plan = PlanType.valueOf(planId.toUpperCase(Locale.ROOT));
        Subscription.Cycle cycle = cycle(cycleId == null ? s.getCycle().name() : cycleId);
        if (plan == PlanType.FREE) {
            s.setCancelAtPeriodEnd(true);
            s.setPendingPlan(null);
        } else {
            if (isUpgrade(s, plan, cycle) || (plan == s.getPlanType() && cycle == s.getCycle())) {
                throw new IllegalArgumentException("That's not a downgrade: pay for it as an upgrade.");
            }
            s.setPendingPlan(plan);
            s.setPendingCycle(cycle);
            s.setCancelAtPeriodEnd(false);
        }
        subscriptions.save(s);
    }

    @Transactional
    public void cancel(String email) {
        Subscription s = activeSub(user(email));
        s.setCancelAtPeriodEnd(true);
        s.setPendingPlan(null);
        s.setPendingCycle(null);
        subscriptions.save(s);
    }

    /** Undo a scheduled cancellation or downgrade. */
    @Transactional
    public void resume(String email) {
        Subscription s = activeSub(user(email));
        s.setCancelAtPeriodEnd(false);
        s.setPendingPlan(null);
        s.setPendingCycle(null);
        subscriptions.save(s);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startTimer() {
        if ("worker".equalsIgnoreCase(role)) {
            return;
        }
        timer.scheduleWithFixedDelay(() -> {
            try {
                runPeriodEnds(now());
            } catch (Exception e) {
                log.error("Billing period check failed", e);
            }
        }, 1, 60, TimeUnit.MINUTES);
    }

    @PreDestroy
    void stopTimer() {
        timer.shutdownNow();
    }

    /** Reminders before the end, and what happens at the end (renewal due, downgrade, cancel, grace over). */
    public void runPeriodEnds(ZonedDateTime now) {
        for (Subscription s : subscriptions.findEndingBefore(now.plus(REMIND_BEFORE))) {
            try {
                // Through the template: a self-call would skip @Transactional and the row lock would fail.
                tx.executeWithoutResult(status -> handlePeriodEnd(s.getId(), now));
            } catch (Exception e) {
                log.error("Period end for subscription {} failed", s.getId(), e);
            }
        }
    }

    @Transactional
    public void handlePeriodEnd(Long subscriptionId, ZonedDateTime now) {
        Subscription s = subscriptions.findById(subscriptionId).orElse(null);
        if (s == null || s.getStatus() == Subscription.Status.CANCELED) {
            return;
        }
        s = subscriptions.lockByUserId(s.getUserId()).orElseThrow();
        User u = users.findById(s.getUserId()).orElseThrow();
        boolean ended = !s.getCurrentPeriodEnd().isAfter(now);

        if (!ended) {
            // Before the end: one reminder to pay for the next period (unless it's ending on purpose).
            if (s.getRenewalRemindedAt() == null && !s.isCancelAtPeriodEnd()) {
                PlanType nextPlan = s.getPendingPlan() != null ? s.getPendingPlan() : s.getPlanType();
                notifications.notify(u, new NotificationService.Alert("RENEWAL_DUE", Notification.Level.INFO, null,
                        "Your " + plans.getPlanConfig(s.getPlanType()).getDisplayName() + " plan renews on " + day(s.getCurrentPeriodEnd()),
                        "Pay for the next period (" + plans.getPlanConfig(nextPlan).getDisplayName() + ") from Billing to stay on it.",
                        "/settings#billing", null, null, true));
                s.setRenewalRemindedAt(now);
                subscriptions.save(s);
            }
            return;
        }

        if (s.isCancelAtPeriodEnd() || s.getPendingPlan() == PlanType.FREE) {
            toFree(s, u, "Your plan ended as you asked; you're on the Free plan now.");
            return;
        }
        if (s.getStatus() == Subscription.Status.ACTIVE) {
            // Period over and not renewed: a few days' grace on the plan they'll continue with.
            if (s.getPendingPlan() != null) {
                s.setPlanType(s.getPendingPlan());
                s.setCycle(s.getPendingCycle() == null ? s.getCycle() : s.getPendingCycle());
                s.setPendingPlan(null);
                s.setPendingCycle(null);
                setPlan(u, s.getPlanType(), s.getCurrentPeriodEnd().plus(GRACE));
            }
            s.setStatus(Subscription.Status.PAST_DUE);
            subscriptions.save(s);
            notifications.notify(u, new NotificationService.Alert(NotificationService.Types.PAYMENT_FAILED, Notification.Level.WARNING, null,
                    "Renew your " + plans.getPlanConfig(s.getPlanType()).getDisplayName() + " plan",
                    "Your period ended. Pay within " + GRACE.toDays() + " days to keep your plan; after that your account moves to Free.",
                    "/settings#billing", null, null, true));
            return;
        }
        if (s.getStatus() == Subscription.Status.PAST_DUE && !s.getCurrentPeriodEnd().plus(GRACE).isAfter(now)) {
            toFree(s, u, "Your plan wasn't renewed, so your account moved to the Free plan. You can upgrade any time.");
        }
    }

    private void toFree(Subscription s, User u, String message) {
        s.setStatus(Subscription.Status.CANCELED);
        s.setCancelAtPeriodEnd(false);
        s.setPendingPlan(null);
        s.setPendingCycle(null);
        subscriptions.save(s);
        setPlan(u, PlanType.FREE, null);
        notifications.notify(u, new NotificationService.Alert("PLAN_ENDED", Notification.Level.INFO, null, "You're on the Free plan now",
                message, "/pricing", null, null, true));
    }

    private void setPlan(User u, PlanType plan, ZonedDateTime until) {
        u.setPlanType(plan);
        u.setPlanExpiration(until == null ? null : until.withZoneSameInstant(ZoneId.of("UTC")).toLocalDateTime());
        users.save(u);
    }

    // ------------------------------------------------------------------ billing profile (BI-3)

    @Transactional
    public BillingProfile saveProfile(String email, BillingProfile in) {
        User u = user(email);
        BillingProfile p = profiles.findById(u.getId()).orElseGet(BillingProfile::new);
        p.setUserId(u.getId());
        p.setLegalName(limit(in.getLegalName(), 200));
        p.setAddressLine(limit(in.getAddressLine(), 400));
        p.setCity(limit(in.getCity(), 100));
        p.setPostalCode(limit(in.getPostalCode(), 12));
        p.setCountry(in.getCountry() == null || in.getCountry().isBlank() ? "IN" : in.getCountry().trim().toUpperCase(Locale.ROOT));
        String gstin = in.getGstin() == null || in.getGstin().isBlank() ? null : in.getGstin().trim().toUpperCase(Locale.ROOT);
        if (gstin != null && !GSTIN.matcher(gstin).matches()) {
            throw new IllegalArgumentException("That GSTIN doesn't look right (15 characters, e.g. 29ABCDE1234F1Z5).");
        }
        p.setGstin(gstin);
        String state = in.getStateCode() == null || in.getStateCode().isBlank() ? null : in.getStateCode().trim();
        if (gstin != null) {
            state = gstin.substring(0, 2); // the GSTIN's state is the place of supply
        }
        if (state != null && !state.matches("\\d{2}")) {
            throw new IllegalArgumentException("State must be its two-digit GST code, e.g. 29 for Karnataka.");
        }
        p.setStateCode(state);
        return profiles.save(p);
    }

    public BillingProfile profile(String email) {
        User u = user(email);
        return profiles.findById(u.getId()).orElseGet(() -> {
            BillingProfile p = new BillingProfile();
            p.setUserId(u.getId());
            return p;
        });
    }

    public byte[] invoicePdf(String email, Long invoiceId) {
        User u = user(email);
        Invoice inv = invoiceRepository.findByIdAndUserId(invoiceId, u.getId())
                .orElseThrow(() -> new com.afklive.streamer.exception.NotFoundException("Invoice not found."));
        return invoices.pdf(inv, u.getEmail());
    }

    // ------------------------------------------------------------------ helpers

    private User user(String email) {
        return users.findByEmail(email).orElseThrow(() -> new com.afklive.streamer.exception.NotFoundException("Account not found."));
    }

    private Subscription activeSub(User u) {
        return subscriptions.findByUserId(u.getId()).filter(s -> s.getStatus() != Subscription.Status.CANCELED)
                .orElseThrow(() -> new ConflictException("You're on the Free plan."));
    }

    private static PlanType paidPlan(String id) {
        try {
            PlanType p = PlanType.valueOf(id.trim().toUpperCase(Locale.ROOT));
            if (p == PlanType.FREE) {
                throw new IllegalArgumentException("The Free plan needs no payment.");
            }
            return p;
        } catch (IllegalArgumentException | NullPointerException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("The Free")) {
                throw e;
            }
            throw new IllegalArgumentException("Unknown plan.");
        }
    }

    private static Subscription.Cycle cycle(String id) {
        try {
            return Subscription.Cycle.valueOf(id == null ? "MONTHLY" : id.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Billing cycle must be MONTHLY or YEARLY.");
        }
    }

    static ZonedDateTime end(ZonedDateTime start, Subscription.Cycle cycle) {
        return cycle == Subscription.Cycle.YEARLY ? start.plusYears(1) : start.plusMonths(1);
    }

    public static String month(ZonedDateTime at) {
        return at.withZoneSameInstant(ZoneId.of("UTC")).format(DateTimeFormatter.ofPattern("yyyy-MM"));
    }

    private static String day(ZonedDateTime t) {
        return t.withZoneSameInstant(ZoneId.of("Asia/Kolkata")).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH));
    }

    private static ZonedDateTime now() {
        return ZonedDateTime.now(ZoneId.of("UTC"));
    }

    private static String limit(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
