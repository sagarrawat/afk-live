package com.afklive.streamer.service;

import com.afklive.streamer.exception.ConflictException;
import com.afklive.streamer.exception.NotFoundException;
import com.afklive.streamer.model.*;
import com.afklive.streamer.repository.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.regex.Pattern;

/**
 * BI-9 launch offers. Rules (CCPA Guidelines for Prevention and Regulation of Dark Patterns, 2023):
 * <ul>
 *   <li>The discount is taken off the real list price in {@code plan_configs}; there is no made-up "MRP".</li>
 *   <li>Every offer has a real start and end date set by an admin; nothing counts down or resets per visitor.</li>
 *   <li>The quote shows the list price, the discount, the price to pay (GST included), how many payments it
 *       covers and the price it renews at, before any payment.</li>
 * </ul>
 * Who gets it: accounts that have never had a plan (no subscription, no plan payment, no earlier offer), once per
 * account. A monthly purchase gets the discount on its first {@code monthlyPeriods} payments (the first one and
 * renewals of the same plan and cycle); a yearly purchase gets it once, on the first year. An upgrade is charged
 * at the list price and ends the offer's remaining renewals. Renewals already earned are honoured after the offer
 * closes or is ended early.
 * <p>
 * Price math is in paise (cents): the discounted price is the list price less the percentage, rounded down to
 * the whole rupee (never up, so the discount is never less than advertised); USD keeps cents. GST is worked out
 * on the discounted price ({@link InvoiceService#split}).
 */
@Service
@Slf4j
public class OfferService {

    public static final int DEFAULT_PERCENT = 10;
    public static final int DEFAULT_MONTHS = 3;
    public static final int MAX_PERCENT = 50;
    /** Longest an offer may run: a "limited time" offer that never ends would just be the price. */
    public static final Duration MAX_LENGTH = Duration.ofDays(92);
    private static final Pattern CODE = Pattern.compile("[A-Z0-9]{3,32}");

    private final LaunchOfferRepository offers;
    private final OfferRedemptionRepository redemptions;
    private final SubscriptionRepository subscriptions;
    private final PaymentAuditRepository payments;
    private final PlanService plans;
    private final AdminActionRepository audit;

    public OfferService(LaunchOfferRepository offers, OfferRedemptionRepository redemptions, SubscriptionRepository subscriptions,
                        PaymentAuditRepository payments, PlanService plans, AdminActionRepository audit) {
        this.offers = offers;
        this.redemptions = redemptions;
        this.subscriptions = subscriptions;
        this.payments = payments;
        this.plans = plans;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ price math

    /** The list price less {@code percent}, rounded down: to the whole rupee for INR, to the cent for USD. */
    public static long discountedPrice(long list, int percent, String currency) {
        if (percent <= 0 || list <= 0) {
            return list;
        }
        long p = list * (100 - percent) / 100; // integer division: rounds down
        if (!"USD".equalsIgnoreCase(currency)) {
            p -= p % 100;
        }
        return Math.max(Math.min(list, BillingService.MIN_CHARGE), p);
    }

    /**
     * What one period costs: {@code list} less {@code discount} is {@code price}. With an offer, {@code payments}
     * is how many payments get it in all and {@code paymentsLeft} how many still will after this one.
     */
    public record Pricing(long list, long discount, long price, Long offerId, String offerName, int percentOff,
                          int payments, int paymentsLeft) {
        static Pricing listOnly(long list) {
            return new Pricing(list, 0, list, null, null, 0, 0, 0);
        }

        public boolean discounted() {
            return discount > 0;
        }

        /** "Launch offer (10% off)", as invoices and receipts say it. */
        public String label() {
            return offerName == null ? null : offerName + " (" + percentOff + "% off)";
        }
    }

    static Pricing priced(long list, String currency, LaunchOffer o, int percent, int payments, int left) {
        long price = discountedPrice(list, percent, currency);
        if (price >= list) {
            return Pricing.listOnly(list);
        }
        return new Pricing(list, list - price, price, o == null ? null : o.getId(), o == null ? "Launch offer" : o.getName(),
                percent, payments, left);
    }

    // ------------------------------------------------------------------ eligibility

    /** Never had a plan: no subscription (paid or set by an admin), no plan payment, no earlier offer. */
    public boolean isNewSubscriber(User u) {
        return u.getId() != null && subscriptions.findByUserId(u.getId()).isEmpty() && !redemptions.existsById(u.getId())
                && !payments.hasPaidForPlan(u.getEmail());
    }

    public Optional<LaunchOffer> activeAutomatic(ZonedDateTime now) {
        return offers.findActiveAutomatic(now).stream().findFirst();
    }

    /** Price of a first plan purchase, with the automatic offer or the code entered (blank: none). */
    public Pricing forNew(User u, PlanType plan, Subscription.Cycle cycle, String currency, long list, String code, ZonedDateTime now) {
        LaunchOffer o;
        if (code != null && !code.isBlank()) {
            o = offers.findByCodeIgnoreCase(code.trim()).filter(x -> x.isActive(now))
                    .orElseThrow(() -> new IllegalArgumentException("That offer code isn't valid, or the offer has ended."));
            if (!o.covers(plan, cycle)) {
                throw new IllegalArgumentException("That offer code doesn't apply to " + plans.getPlanConfig(plan).getDisplayName()
                        + (cycle == Subscription.Cycle.YEARLY ? " yearly." : "."));
            }
            if (!isNewSubscriber(u)) {
                throw new ConflictException("Offer codes are for accounts that have never had a paid plan.");
            }
        } else {
            o = activeAutomatic(now).filter(x -> x.covers(plan, cycle)).orElse(null);
            if (o == null || !isNewSubscriber(u)) {
                return Pricing.listOnly(list);
            }
        }
        int n = o.periodsFor(cycle);
        return priced(list, currency, o, o.getPercentOff(), n, n - 1);
    }

    /** Price of a renewal of the same plan and cycle: discounted while the account's offer has payments left. */
    public Pricing forRenewal(Long userId, PlanType plan, Subscription.Cycle cycle, String currency, long list) {
        Optional<OfferRedemption> r = redemptions.findById(userId)
                .filter(x -> x.getPlanType() == plan && x.getCycle() == cycle && x.periodsLeft() > 0);
        if (r.isEmpty()) {
            return Pricing.listOnly(list);
        }
        OfferRedemption x = r.get();
        LaunchOffer o = offers.findById(x.getOfferId()).orElse(null);
        return priced(list, currency, o, x.getPercentOff(), x.getPeriodsTotal(), x.periodsLeft() - 1);
    }

    /**
     * After a discounted payment: claims the account's offer (first payment) or counts one more discounted payment.
     * Two payments racing for the same account both count against the same offer, never two offers.
     */
    public void recordPaid(User u, PaymentAudit a, PlanType plan, Subscription.Cycle cycle) {
        if (a.getOfferId() == null || a.getDiscountAmount() <= 0) {
            return;
        }
        LaunchOffer o = offers.findById(a.getOfferId()).orElse(null);
        int percent = o == null ? 0 : o.getPercentOff();
        int total = o == null ? 1 : o.periodsFor(cycle);
        if (redemptions.claim(u.getId(), a.getOfferId(), plan.name(), cycle.name(), percent, total, a.getTransactionId()) == 0) {
            redemptions.use(u.getId());
        }
    }

    /** Where the account's offer stands, for the Billing page (null without one). */
    public record Standing(String name, int percentOff, String plan, String cycle, int payments, int used, int left) {
    }

    public Standing standing(Long userId) {
        return redemptions.findById(userId).map(r -> new Standing(offers.findById(r.getOfferId()).map(LaunchOffer::getName).orElse("Launch offer"),
                r.getPercentOff(), r.getPlanType().name(), r.getCycle().name(), r.getPeriodsTotal(), r.getPeriodsUsed(), r.periodsLeft()))
                .orElse(null);
    }

    // ------------------------------------------------------------------ public view (pricing page, app)

    public record PlanPrice(String id, String name, long listMonthly, long offerMonthly, String listMonthlyLabel,
                            String offerMonthlyLabel, long listYearly, Long offerYearly, String listYearlyLabel,
                            String offerYearlyLabel) {
    }

    /** {@code eligible} is null for someone not signed in (the offer is for new subscribers). */
    public record PublicOffer(String name, int percentOff, int monthlyPeriods, boolean includeYearly, ZonedDateTime startsAt,
                              ZonedDateTime endsAt, String currency, List<PlanPrice> plans, Boolean eligible) {
    }

    /** The automatic offer open now, priced in {@code currency}; empty when there is none. Code-only offers aren't listed. */
    public Optional<PublicOffer> publicOffer(String currency, User viewer, ZonedDateTime now) {
        String cur = "USD".equalsIgnoreCase(currency) ? "USD" : "INR";
        Optional<LaunchOffer> active = activeAutomatic(now);
        if (active.isEmpty()) {
            return Optional.empty();
        }
        LaunchOffer o = active.get();
        List<PlanPrice> out = new ArrayList<>();
        for (PlanType t : PlanType.values()) {
            if (t == PlanType.FREE || !o.planSet().contains(t)) {
                continue;
            }
            PlanConfig c = plans.getPlanConfig(t);
            long m = c.price(cur, false);
            long y = c.price(cur, true);
            if (m <= 0) {
                continue;
            }
            long om = discountedPrice(m, o.getPercentOff(), cur);
            Long oy = o.isIncludeYearly() && y > 0 ? discountedPrice(y, o.getPercentOff(), cur) : null;
            out.add(new PlanPrice(t.name(), c.getDisplayName(), m, om, BillingService.priceLabel(m, cur), BillingService.priceLabel(om, cur),
                    y, oy, BillingService.priceLabel(y, cur), oy == null ? null : BillingService.priceLabel(oy, cur)));
        }
        if (out.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new PublicOffer(o.getName(), o.getPercentOff(), o.getMonthlyPeriods(), o.isIncludeYearly(), o.getStartsAt(),
                o.closesAt(), cur, out, viewer == null ? null : isNewSubscriber(viewer)));
    }

    // ------------------------------------------------------------------ admin (audited)

    public record OfferInput(String name, String code, Boolean automatic, Integer percentOff, Integer monthlyPeriods,
                             Boolean includeYearly, List<String> plans, ZonedDateTime startsAt, ZonedDateTime endsAt) {
    }

    public record OfferRow(Long id, String name, String code, boolean automatic, int percentOff, int monthlyPeriods,
                           boolean includeYearly, List<String> plans, ZonedDateTime startsAt, ZonedDateTime endsAt,
                           ZonedDateTime endedAt, String status, long redemptions, String createdBy, String createdReason,
                           String endedBy, String endedReason, String termsLocked) {
    }

    static String status(LaunchOffer o, ZonedDateTime now) {
        return o.getEndedAt() != null && !o.getEndedAt().isAfter(now) ? "ENDED"
                : now.isBefore(o.getStartsAt()) ? "SCHEDULED" : o.isActive(now) ? "ACTIVE" : "ENDED";
    }

    OfferRow row(LaunchOffer o, ZonedDateTime now) {
        long taken = redemptions.countByOfferId(o.getId());
        return new OfferRow(o.getId(), o.getName(), o.getCode(), o.isAutomatic(), o.getPercentOff(), o.getMonthlyPeriods(),
                o.isIncludeYearly(), o.planSet().stream().sorted().map(Enum::name).toList(), o.getStartsAt(), o.getEndsAt(),
                o.getEndedAt(), status(o, now), taken, o.getCreatedBy(), o.getCreatedReason(),
                o.getEndedBy(), o.getEndedReason(), lockReason(o.getId(), taken));
    }

    /**
     * Why the discount, plans, months and automatic setting can't change any more (null: they still can). Once anyone
     * has paid with the offer, or started a checkout with it, those are what they were promised: changing them would
     * make one offer mean two different things.
     */
    String lockReason(Long offerId, long taken) {
        if (taken > 0) {
            return taken + (taken == 1 ? " account has" : " accounts have") + " paid with this offer, so the discount, plans and months"
                    + " they were promised are fixed. End it and create a new offer to change them.";
        }
        if (payments.countByOfferId(offerId) > 0) {
            return "A checkout with this offer has been started, so the discount, plans and months are fixed."
                    + " End it and create a new offer to change them.";
        }
        return null;
    }

    public List<OfferRow> list(ZonedDateTime now) {
        return offers.findTop50ByOrderByCreatedAtDesc().stream().map(o -> row(o, now)).toList();
    }

    @Transactional
    public OfferRow create(String admin, OfferInput in, String reason, ZonedDateTime now) {
        String why = requireReason(reason);
        if (in == null) {
            throw new IllegalArgumentException("Send the offer.");
        }
        String name = in.name() == null ? "" : in.name().trim();
        if (name.isEmpty() || name.length() > 80) {
            throw new IllegalArgumentException("Give the offer a name of up to 80 characters, e.g. Launch offer.");
        }
        int percent = in.percentOff() == null ? DEFAULT_PERCENT : in.percentOff();
        if (percent < 1 || percent > MAX_PERCENT) {
            throw new IllegalArgumentException("The discount is between 1% and " + MAX_PERCENT + "%.");
        }
        int months = in.monthlyPeriods() == null ? DEFAULT_MONTHS : in.monthlyPeriods();
        if (months < 1 || months > 12) {
            throw new IllegalArgumentException("A monthly plan gets the discount for 1 to 12 months.");
        }
        boolean automatic = in.automatic() == null || in.automatic();
        String code = in.code() == null || in.code().isBlank() ? null : in.code().trim().toUpperCase(Locale.ROOT);
        if (code != null && !CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("A code is 3 to 32 letters or digits, e.g. LAUNCH10.");
        }
        if (!automatic && code == null) {
            throw new IllegalArgumentException("An offer that isn't automatic needs a code.");
        }
        if (code != null && offers.findByCodeIgnoreCase(code).isPresent()) {
            throw new ConflictException("That code was used for another offer. Codes are never reused.");
        }
        Set<PlanType> planSet = planSet(in.plans() == null || in.plans().isEmpty() ? List.of("ESSENTIALS", "PRO", "AGENCY") : in.plans());
        ZonedDateTime start = in.startsAt() == null ? now : in.startsAt().withZoneSameInstant(ZoneId.of("UTC"));
        if (start.isBefore(now.minusMinutes(5))) {
            throw new IllegalArgumentException("An offer can't start in the past.");
        }
        if (in.endsAt() == null) {
            throw new IllegalArgumentException("Every offer needs a real end date.");
        }
        ZonedDateTime end = in.endsAt().withZoneSameInstant(ZoneId.of("UTC"));
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("The end date must be after the start.");
        }
        if (Duration.between(start, end).compareTo(MAX_LENGTH) > 0) {
            throw new IllegalArgumentException("An offer runs at most " + MAX_LENGTH.toDays() + " days. A discount that never ends is a new price: change the plan's price instead.");
        }
        if (automatic && !offers.findAutomaticOverlapping(start, end).isEmpty()) {
            throw new ConflictException("Another automatic offer runs in those dates. End it first.");
        }
        LaunchOffer o = new LaunchOffer();
        o.setName(name);
        o.setCode(code);
        o.setAutomatic(automatic);
        o.setPercentOff(percent);
        o.setMonthlyPeriods(months);
        o.setIncludeYearly(in.includeYearly() == null || in.includeYearly());
        o.setPlans(String.join(",", planSet.stream().map(Enum::name).toList()));
        o.setStartsAt(start);
        o.setEndsAt(end);
        o.setCreatedBy(admin);
        o.setCreatedReason(why);
        o.setCreatedAt(now);
        o = offers.save(o);
        record(admin, "OFFER_CREATE", o.getId(), name + ": " + percent + "% off " + months + " month(s)"
                + (o.isIncludeYearly() ? " or the first year" : "") + " on " + o.getPlans() + (code == null ? "" : ", code " + code)
                + (automatic ? ", automatic" : "") + ", " + start + " to " + end + ": " + why, now);
        return row(o, now);
    }

    /**
     * BI-9: changes an offer that hasn't ended. The name and code can always change. The discount, plans, months, yearly
     * and automatic settings only while nobody has paid with it ({@link #lockReason}). The start date only before it
     * starts; the end date can move either way but never into the past (to stop it now, use {@link #end}), and the
     * offer still runs at most {@link #MAX_LENGTH}. Null fields stay as they are; a blank code removes it.
     */
    @Transactional
    public OfferRow edit(String admin, Long id, OfferInput in, String reason, ZonedDateTime now) {
        String why = requireReason(reason);
        if (in == null) {
            throw new IllegalArgumentException("Send the changes.");
        }
        LaunchOffer o = offers.lockById(id).orElseThrow(() -> new NotFoundException("Offer not found."));
        if ("ENDED".equals(status(o, now))) {
            throw new ConflictException("This offer has ended. Ended offers stay ended: create a new one.");
        }
        boolean started = !now.isBefore(o.getStartsAt());
        List<String> changes = new ArrayList<>();

        if (in.name() != null) {
            String name = in.name().trim();
            if (name.isEmpty() || name.length() > 80) {
                throw new IllegalArgumentException("Give the offer a name of up to 80 characters, e.g. Launch offer.");
            }
            if (!name.equals(o.getName())) {
                changes.add("name " + o.getName() + " → " + name);
                o.setName(name);
            }
        }
        if (in.code() != null) {
            String code = in.code().isBlank() ? null : in.code().trim().toUpperCase(Locale.ROOT);
            if (code != null && !CODE.matcher(code).matches()) {
                throw new IllegalArgumentException("A code is 3 to 32 letters or digits, e.g. LAUNCH10.");
            }
            if (!Objects.equals(code, o.getCode())) {
                if (code != null && offers.findByCodeIgnoreCase(code).filter(x -> !x.getId().equals(o.getId())).isPresent()) {
                    throw new ConflictException("That code was used for another offer. Codes are never reused.");
                }
                changes.add("code " + (o.getCode() == null ? "none" : o.getCode()) + " → " + (code == null ? "none" : code));
                o.setCode(code);
            }
        }

        List<String> locked = new ArrayList<>();
        if (in.percentOff() != null && in.percentOff() != o.getPercentOff()) {
            if (in.percentOff() < 1 || in.percentOff() > MAX_PERCENT) {
                throw new IllegalArgumentException("The discount is between 1% and " + MAX_PERCENT + "%.");
            }
            locked.add("discount " + o.getPercentOff() + "% → " + in.percentOff() + "%");
            o.setPercentOff(in.percentOff());
        }
        if (in.monthlyPeriods() != null && in.monthlyPeriods() != o.getMonthlyPeriods()) {
            if (in.monthlyPeriods() < 1 || in.monthlyPeriods() > 12) {
                throw new IllegalArgumentException("A monthly plan gets the discount for 1 to 12 months.");
            }
            locked.add("months " + o.getMonthlyPeriods() + " → " + in.monthlyPeriods());
            o.setMonthlyPeriods(in.monthlyPeriods());
        }
        if (in.includeYearly() != null && in.includeYearly() != o.isIncludeYearly()) {
            locked.add("first year " + (o.isIncludeYearly() ? "included" : "not included") + " → " + (in.includeYearly() ? "included" : "not included"));
            o.setIncludeYearly(in.includeYearly());
        }
        if (in.automatic() != null && in.automatic() != o.isAutomatic()) {
            locked.add(in.automatic() ? "now applies by itself" : "now with the code only");
            o.setAutomatic(in.automatic());
        }
        if (in.plans() != null) {
            String before = String.join(",", new TreeSet<>(o.planSet()).stream().map(Enum::name).toList());
            String after = String.join(",", planSet(in.plans()).stream().map(Enum::name).toList());
            if (!after.equals(before)) {
                locked.add("plans " + before + " → " + after);
                o.setPlans(after);
            }
        }
        if (!locked.isEmpty()) {
            String lock = lockReason(o.getId(), redemptions.countByOfferId(o.getId()));
            if (lock != null) {
                throw new ConflictException(lock);
            }
            changes.addAll(locked);
        }
        if (!o.isAutomatic() && o.getCode() == null) {
            throw new IllegalArgumentException("An offer that isn't automatic needs a code.");
        }

        if (in.startsAt() != null) {
            ZonedDateTime start = in.startsAt().withZoneSameInstant(ZoneId.of("UTC"));
            if (!start.isEqual(o.getStartsAt())) {
                if (started) {
                    throw new ConflictException("This offer has started: its start date can't change.");
                }
                if (start.isBefore(now.minusMinutes(5))) {
                    throw new IllegalArgumentException("An offer can't start in the past.");
                }
                changes.add("starts " + o.getStartsAt() + " → " + start);
                o.setStartsAt(start);
            }
        }
        if (in.endsAt() != null) {
            ZonedDateTime end = in.endsAt().withZoneSameInstant(ZoneId.of("UTC"));
            if (!end.isEqual(o.getEndsAt())) {
                if (!end.isAfter(now)) {
                    throw new IllegalArgumentException("The end date can't be in the past. To stop the offer now, use End now.");
                }
                changes.add("ends " + o.getEndsAt() + " → " + end);
                o.setEndsAt(end);
            }
        }
        if (!o.getEndsAt().isAfter(o.getStartsAt())) {
            throw new IllegalArgumentException("The end date must be after the start.");
        }
        if (Duration.between(o.getStartsAt(), o.getEndsAt()).compareTo(MAX_LENGTH) > 0) {
            throw new IllegalArgumentException("An offer runs at most " + MAX_LENGTH.toDays()
                    + " days from its start. A discount that never ends is a new price: change the plan's price instead.");
        }
        if (o.isAutomatic() && offers.findAutomaticOverlapping(o.getStartsAt(), o.getEndsAt()).stream()
                .anyMatch(x -> !x.getId().equals(o.getId()))) {
            throw new ConflictException("Another automatic offer runs in those dates. End it first.");
        }
        if (changes.isEmpty()) {
            throw new IllegalArgumentException("Nothing changed.");
        }
        offers.save(o);
        record(admin, "OFFER_EDIT", id, o.getName() + ": " + String.join("; ", changes) + ": " + why, now);
        return row(o, now);
    }

    @Transactional
    public OfferRow end(String admin, Long id, String reason, ZonedDateTime now) {
        String why = requireReason(reason);
        LaunchOffer o = offers.findById(id).orElseThrow(() -> new NotFoundException("Offer not found."));
        if (o.getEndedAt() != null || !o.getEndsAt().isAfter(now)) {
            throw new ConflictException("This offer has already ended.");
        }
        o.setEndedAt(now);
        o.setEndedBy(admin);
        o.setEndedReason(why);
        offers.save(o);
        record(admin, "OFFER_END", id, o.getName() + " ended early (accounts that already have it keep their discounted renewals): " + why, now);
        return row(o, now);
    }

    /** Paid plan ids as a sorted set of at least one. */
    static Set<PlanType> planSet(List<String> ids) {
        Set<PlanType> out = new TreeSet<>();
        for (String id : ids) {
            PlanType p;
            try {
                p = PlanType.valueOf(id.trim().toUpperCase(Locale.ROOT));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("Unknown plan: " + id);
            }
            if (p == PlanType.FREE) {
                throw new IllegalArgumentException("The Free plan has nothing to discount.");
            }
            out.add(p);
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("Pick at least one plan.");
        }
        return out;
    }

    private void record(String admin, String action, Object id, String detail, ZonedDateTime now) {
        AdminAction a = new AdminAction();
        a.setAdminEmail(admin);
        a.setAction(action);
        a.setTargetType("offer");
        a.setTargetId(String.valueOf(id));
        a.setDetail(detail);
        a.setCreatedAt(now);
        audit.save(a);
        log.info("Admin {} {} offer {}: {}", admin, action, id, detail);
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.trim().length() < 3) {
            throw new IllegalArgumentException("Say why (it goes in the audit log).");
        }
        String r = reason.trim();
        return r.length() > 500 ? r.substring(0, 500) : r;
    }
}
