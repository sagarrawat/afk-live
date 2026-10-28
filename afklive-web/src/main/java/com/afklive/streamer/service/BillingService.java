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
    private final OfferService offers;
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
                          NotificationService notifications, OfferService offers,
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
        this.offers = offers;
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

    /** {@code offer}: the account's launch offer and how many discounted payments it has left (BI-9), or null. */
    public record Overview(String plan, String planName, String cycle, String status, ZonedDateTime periodEnd,
                           boolean cancelAtPeriodEnd, String pendingPlan, Usage usage, NextCharge nextCharge,
                           List<InvoiceView> invoices, double unpaidBalance, OfferService.Standing offer) {
    }

    public Overview overview(String email) {
        User u = user(email);
        PlanConfig c = plans.limitsFor(u);
        Optional<Subscription> sub = subscriptions.findByUserId(u.getId()).filter(s -> s.getStatus() != Subscription.Status.CANCELED);
        Usage use = new Usage(Math.round(usage.secondsIn(u.getId(), month(now())) / 36.0) / 100.0, c.getMonthlyHours(),
                u.getUsedStorageBytes(), c.getMaxStorageBytes(), (int) jobs.countByUserEmailAndIsLiveTrue(email), c.getMaxActiveStreams());
        NextCharge next = null;
        if (sub.isPresent() && !sub.get().isCancelAtPeriodEnd()) {
            Subscription s = sub.get();
            PlanType nextPlan = s.getPendingPlan() != null ? s.getPendingPlan() : s.getPlanType();
            Subscription.Cycle nextCycle = s.getPendingCycle() != null ? s.getPendingCycle() : s.getCycle();
            if (nextPlan != PlanType.FREE) {
                OfferService.Pricing p = renewalPricing(s, nextPlan, nextCycle);
                next = new NextCharge(s.getCurrentPeriodEnd(), p.price(), s.getCurrency(),
                        plans.getPlanConfig(nextPlan).getDisplayName() + " (" + nextCycle.name().toLowerCase() + ")"
                                + (p.discounted() ? ", " + p.label() : ""));
            }
        }
        List<InvoiceView> inv = invoiceRepository.findAllByUserIdOrderByIssuedAtDesc(u.getId()).stream()
                .map(i -> new InvoiceView(i.getId(), i.getNumber(), i.getDescription(), i.getTotal(), i.getCurrency(), i.getIssuedAt()))
                .toList();
        return new Overview(u.getPlanType().name(), c.getDisplayName(), sub.map(s -> s.getCycle().name()).orElse(null),
                sub.map(s -> s.getStatus().name()).orElse(u.getPlanType() == PlanType.FREE ? "FREE" : "ACTIVE"),
                sub.map(Subscription::getCurrentPeriodEnd).orElse(null), sub.map(Subscription::isCancelAtPeriodEnd).orElse(false),
                sub.map(s -> s.getPendingPlan() == null ? null : s.getPendingPlan().name()).orElse(null), use, next, inv,
                u.getUnpaidBalance(), offers.standing(u.getId()));
    }

    /** What the next period of {@code plan}/{@code cycle} costs this subscriber (a launch offer's renewals, or the list price). */
    OfferService.Pricing renewalPricing(Subscription s, PlanType plan, Subscription.Cycle cycle) {
        long list = plans.getPlanConfig(plan).price(s.getCurrency(), cycle == Subscription.Cycle.YEARLY);
        return offers.forRenewal(s.getUserId(), plan, cycle, s.getCurrency(), list);
    }

    // ------------------------------------------------------------------ checkout

    /**
     * A launch offer on a quote (BI-9): {@code payments} discounted payments in all, {@code paymentsLeft} more after
     * this one, each at the quote's {@code price}; then the plan renews at the quote's {@code renewalPrice}.
     */
    public record OfferLine(Long id, String name, int percentOff, int payments, int paymentsLeft) {
    }

    /**
     * {@code listPrice} is the plan's price for the period and {@code discount} a launch offer's (both GST-inclusive);
     * {@code price} is what the period costs after it; {@code amount} what is paid now after any upgrade credit, of
     * which {@code gst} is GST (INR). {@code renewalPrice}: the price once any offer is used up (the list price).
     */
    public record Quote(String kind, PlanType plan, Subscription.Cycle cycle, String currency, long price, long credit,
                        long amount, ZonedDateTime periodStart, ZonedDateTime periodEnd, long listPrice, long discount,
                        OfferLine offer, long renewalPrice, long gst) {
    }

    static Quote quoteOf(String kind, PlanType plan, Subscription.Cycle cycle, String currency, OfferService.Pricing p, long credit,
                         long amount, ZonedDateTime start, ZonedDateTime end) {
        OfferLine offer = p.discounted() ? new OfferLine(p.offerId(), p.offerName(), p.percentOff(), p.payments(), p.paymentsLeft()) : null;
        long gst = amount - InvoiceService.split(amount, currency, null, null).taxable();
        return new Quote(kind, plan, cycle, currency, p.price(), credit, amount, start, end, p.list(), p.discount(), offer, p.list(), gst);
    }

    /** What paying for {@code plan}/{@code cycle} now would cost (new, upgrade with credit, or renewal). */
    public Quote quote(String email, String planId, String cycleId) {
        return quote(email, planId, cycleId, null);
    }

    /** As {@link #quote(String, String, String)}, with a launch offer code (blank: the automatic offer, if any). */
    public Quote quote(String email, String planId, String cycleId, String offerCode) {
        User u = user(email);
        PlanType plan = paidPlan(planId);
        Subscription.Cycle cycle = cycle(cycleId);
        Optional<Subscription> current = subscriptions.findByUserId(u.getId()).filter(s -> s.getStatus() != Subscription.Status.CANCELED);
        String currency = current.map(Subscription::getCurrency).orElse("INR");
        long list = plans.getPlanConfig(plan).price(currency, cycle == Subscription.Cycle.YEARLY);
        if (list <= 0) {
            throw new IllegalArgumentException("This plan isn't for sale yet.");
        }
        ZonedDateTime now = now();
        if (current.isEmpty()) {
            OfferService.Pricing p = offers.forNew(u, plan, cycle, currency, list, offerCode, now);
            return quoteOf(KIND_NEW, plan, cycle, currency, p, 0, p.price(), now, end(now, cycle));
        }
        if (offerCode != null && !offerCode.isBlank()) {
            throw new ConflictException("Offer codes are for accounts that have never had a paid plan.");
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
            OfferService.Pricing p = offers.forRenewal(u.getId(), plan, cycle, currency, list);
            return quoteOf(KIND_RENEW, plan, cycle, currency, p, 0, p.price(), start, end(start, cycle));
        }
        if (!isUpgrade(s, plan, cycle)) {
            throw new ConflictException("Moving to a smaller plan or a monthly cycle happens at the end of your period: use Downgrade.");
        }
        long credit = s.getStatus() == Subscription.Status.ACTIVE ? credit(s, now) : 0;
        long amount = Math.max(MIN_CHARGE, list - credit);
        // An upgrade is at the list price: a launch offer covers only the plan and cycle it was taken on.
        return quoteOf(KIND_UPGRADE, plan, cycle, currency, OfferService.Pricing.listOnly(list), Math.min(credit, list - MIN_CHARGE),
                amount, now, end(now, cycle));
    }

    /** Value of the unused part of the current period (what an upgrade credits), from what the period cost. */
    long credit(Subscription s, ZonedDateTime now) {
        long paid = s.getPeriodPrice() != null ? s.getPeriodPrice()
                : plans.getPlanConfig(s.getPlanType()).price(s.getCurrency(), s.getCycle() == Subscription.Cycle.YEARLY);
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
        return checkout(email, planId, cycleId, null);
    }

    /** As {@link #checkout(String, String, String)}, with a launch offer code; the order is for the discounted amount. */
    @Transactional
    public Checkout checkout(String email, String planId, String cycleId, String offerCode) {
        User u = user(email);
        Quote q = quote(email, planId, cycleId, offerCode);
        String orderId = "MT" + UUID.randomUUID().toString().replace("-", "").substring(0, 30);
        PaymentAudit audit = new PaymentAudit(orderId, email, q.amount(), "INITIATED");
        audit.setUser(u);
        audit.setPlanId(q.plan().name());
        audit.setKind(q.kind());
        audit.setBillingCycle(q.cycle().name());
        audit.setCurrency(q.currency());
        audit.setCredit(q.credit());
        audit.setListAmount(q.listPrice());
        audit.setDiscountAmount(q.discount());
        audit.setOfferId(q.offer() == null ? null : q.offer().id());
        audits.save(audit);
        String url = gateway.createCheckout(orderId, q.amount(), baseUrl + "/studio?view=settings&payment_order=" + orderId);
        log.info("Checkout {} for {}: {} {} {} amount={} credit={} discount={}", orderId, email, q.kind(), q.plan(), q.cycle(), q.amount(),
                q.credit(), q.discount());
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
        boolean wasSubscribed = s.getId() != null && s.getStatus() != null && s.getStatus() != Subscription.Status.CANCELED;
        PlanType previousPlan = wasSubscribed ? s.getPlanType() : null;
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
        // GR-18: free days earned through invites while on no paid plan join this period, once.
        int bonus = users.lockBonusDays(u.getId());
        if (bonus > 0) {
            users.clearBonusDays(u.getId());
        }
        s.setCurrentPeriodEnd(end(start, cycle).plusDays(Math.max(0, bonus)));
        s.setCancelAtPeriodEnd(false);
        s.setPendingPlan(null);
        s.setPendingCycle(null);
        s.setRenewalRemindedAt(null);
        long paid = audit.getAmount() == null ? 0 : audit.getAmount();
        s.setPeriodPrice(paid + audit.getCredit());
        s.setTerms(termsAfterPayment(s.getTerms(), previousPlan, plan, now));
        subscriptions.save(s);
        setPlan(u, plan, s.getCurrentPeriodEnd());
        offers.recordPaid(u, audit, plan, cycle);
        String discountLabel = null;
        if (audit.getDiscountAmount() > 0) {
            OfferService.Standing st = offers.standing(u.getId());
            discountLabel = st == null ? "Launch offer" : st.name() + " (" + st.percentOff() + "% off)";
        }
        String what = plans.getPlanConfig(plan).getDisplayName() + " plan, " + cycle.name().toLowerCase()
                + (audit.getCredit() > 0 ? " (credit for unused time: " + InvoiceService.money(audit.getCredit(), s.getCurrency()) + ")" : "")
                + (bonus > 0 ? ", plus " + bonus + " free days from invites" : "");
        Invoice inv = invoices.issue(u.getId(), audit.getTransactionId(), "AFK Live " + what, s.getCurrentPeriodStart(),
                s.getCurrentPeriodEnd(), s.getCurrency(), paid,
                audit.getDiscountAmount() > 0 ? audit.getListAmount() : null, audit.getDiscountAmount(), discountLabel);
        notifications.notify(u, new NotificationService.Alert("PAYMENT_RECEIVED", Notification.Level.SUCCESS, null,
                "Payment received: " + plans.getPlanConfig(plan).getDisplayName(),
                "You're on " + what + " until " + day(s.getCurrentPeriodEnd()) + ". Invoice " + inv.getNumber() + " is in Billing.",
                "/settings#billing", null, null, true));
    }

    /**
     * BI-10: the limits recorded with a payment. A first plan (or one after a lapse) gets the plan's limits as sold
     * today; renewing or switching cycle on the same plan keeps what was bought; an upgrade gets the new plan's
     * limits, keeping anything the old terms gave that is more.
     */
    PlanTerms termsAfterPayment(PlanTerms before, PlanType previousPlan, PlanType plan, ZonedDateTime now) {
        PlanConfig current = plans.getPlanConfig(plan);
        boolean hadTerms = before != null && before.getPlan() != null && before.getPlan() == previousPlan;
        if (previousPlan == null || !hadTerms) {
            return PlanTerms.of(current, now);
        }
        if (previousPlan == plan) {
            return before;
        }
        return plan.rank() > previousPlan.rank() ? PlanTerms.carryOver(before, current, now) : PlanTerms.of(current, now);
    }

    // ------------------------------------------------------------------ free days (GR-18)

    /**
     * What {@link #addFreeDays} did: {@code until} is the new end of the paid period, or null when the
     * days were saved for the next paid period ({@code saved} days are now waiting).
     */
    public record FreeDays(int days, ZonedDateTime until, int saved) {
        public boolean extended() {
            return until != null;
        }
    }

    /**
     * Gives an account free days without a payment, joining the caller's transaction: on a paid plan
     * (active, or past due within grace) the current period is extended, the same way an admin
     * extension works; on no paid plan the days are saved on the account and added to the next period
     * it pays for ({@link #applyPaid}). Never changes a price.
     */
    @Transactional
    public FreeDays addFreeDays(Long userId, int days) {
        if (days < 1 || days > 366) {
            throw new IllegalArgumentException("Free days must be between 1 and 366.");
        }
        Optional<Subscription> paid = subscriptions.lockByUserId(userId).filter(x -> x.getStatus() != Subscription.Status.CANCELED);
        if (paid.isEmpty()) {
            users.addBonusDays(userId, days);
            return new FreeDays(days, null, users.bonusDays(userId));
        }
        Subscription s = paid.get();
        User u = users.lockById(userId).orElseThrow();
        ZonedDateTime base = s.getCurrentPeriodEnd() == null || s.getCurrentPeriodEnd().isBefore(now()) ? now() : s.getCurrentPeriodEnd();
        s.setCurrentPeriodEnd(base.plusDays(days));
        s.setStatus(Subscription.Status.ACTIVE);
        s.setRenewalRemindedAt(null);
        subscriptions.save(s);
        setPlan(u, s.getPlanType(), s.getCurrentPeriodEnd());
        return new FreeDays(days, s.getCurrentPeriodEnd(), 0);
    }

    /** "24 Dec 2026", as billing messages write dates. */
    public static String dayOf(ZonedDateTime at) {
        return day(at);
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
                Subscription.Cycle nextCycle = s.getPendingCycle() != null ? s.getPendingCycle() : s.getCycle();
                notifications.notify(u, new NotificationService.Alert("RENEWAL_DUE", Notification.Level.INFO, null,
                        "Your " + plans.getPlanConfig(s.getPlanType()).getDisplayName() + " plan renews on " + day(s.getCurrentPeriodEnd()),
                        renewalMessage(s, nextPlan, nextCycle), "/settings#billing", null, null, true));
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
                // BI-10: a downgrade takes the smaller plan's limits as sold today.
                s.setTerms(PlanTerms.of(plans.getPlanConfig(s.getPendingPlan()), now));
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

    /**
     * The renewal reminder (BI-7, BI-9): what the next period costs, GST included, and when a launch offer's last
     * discounted period is next, what it renews at after that.
     */
    String renewalMessage(Subscription s, PlanType nextPlan, Subscription.Cycle nextCycle) {
        OfferService.Pricing p = renewalPricing(s, nextPlan, nextCycle);
        String name = plans.getPlanConfig(nextPlan).getDisplayName();
        String per = nextCycle == Subscription.Cycle.YEARLY ? "year" : "month";
        String gst = "INR".equalsIgnoreCase(s.getCurrency()) ? " incl. GST" : "";
        String price = priceLabel(p.price(), s.getCurrency()) + gst;
        if (!p.discounted()) {
            return "Pay " + price + " for the next " + per + " of " + name + " from Billing to stay on it.";
        }
        String after = p.paymentsLeft() == 0
                ? " It's the last discounted " + per + ": after it, " + name + " renews at " + priceLabel(p.list(), s.getCurrency()) + gst
                        + " a " + per + "."
                : "";
        return "Pay " + price + " for the next " + per + " of " + name + " (" + p.label() + " on the list price of "
                + priceLabel(p.list(), s.getCurrency()) + ") from Billing to stay on it." + after;
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
