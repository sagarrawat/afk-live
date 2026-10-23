package com.afklive.streamer.payment;

import com.afklive.streamer.model.PaymentAudit;
import com.afklive.streamer.model.PlanType;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.PaymentAuditRepository;
import com.afklive.streamer.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Starts checkouts and applies payments. A payment's effect (plan upgrade or balance clear) is
 * applied only after the provider itself reports the order COMPLETED for the expected amount,
 * and exactly once per order, however many callbacks or status checks arrive.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    public static final String BALANCE_CLEAR = "BALANCE_CLEAR";
    /** Essentials price in paise. Keep in sync with the plan's displayed price. */
    static final long ESSENTIALS_PRICE_PAISE = 19900;

    static final String INITIATED = "INITIATED";
    static final String COMPLETED = "COMPLETED";
    static final String FAILED = "FAILED";

    private final PaymentGateway gateway;
    private final PaymentAuditRepository audits;
    private final UserService userService;

    @Value("${app.base-url:http://localhost:8080}")
    private String baseUrl;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.afklive.streamer.service.NotificationService notifications;

    /** Creates an order for the user and returns the provider checkout URL. */
    public String startCheckout(String email, String planId) {
        User user = userService.getOrCreateUser(email);
        long amount = amountFor(user, planId);

        String orderId = "MT" + UUID.randomUUID().toString().replace("-", "").substring(0, 30);
        PaymentAudit audit = new PaymentAudit(orderId, email, amount, INITIATED);
        audit.setPlanId(planId);
        audit.setUser(user);
        audits.save(audit);

        log.info("Starting checkout order={} user={} plan={} amount={}", orderId, email, planId, amount);
        String redirect = baseUrl + "/studio?view=settings&payment_order=" + orderId;
        return gateway.createCheckout(orderId, amount, redirect);
    }

    private long amountFor(User user, String planId) {
        if (BALANCE_CLEAR.equals(planId)) {
            long amount = Math.round(user.getUnpaidBalance() * 100);
            if (amount <= 0) {
                throw new IllegalArgumentException("You have no outstanding balance.");
            }
            return amount;
        }
        PlanType plan;
        try {
            plan = PlanType.valueOf(planId);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("Invalid plan.");
        }
        if (plan != PlanType.ESSENTIALS) {
            throw new IllegalArgumentException("This plan can't be purchased.");
        }
        return ESSENTIALS_PRICE_PAISE;
    }

    /**
     * Checks the order with the provider and applies it if it's paid. Safe to call any number of
     * times (callback, user returning from checkout, retries): the effect is applied once.
     *
     * @return the order's status after the check: COMPLETED, FAILED or INITIATED (still pending)
     */
    @Transactional
    public String confirm(String orderId) {
        PaymentAudit audit = audits.findForUpdateByTransactionId(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown payment."));
        if (COMPLETED.equals(audit.getStatus()) || FAILED.equals(audit.getStatus())) {
            return audit.getStatus();
        }

        PaymentGateway.OrderState state = gateway.fetchOrderState(orderId);
        if (state.completed()) {
            if (state.amountPaise() != audit.getAmount()) {
                log.error("Order {} paid {} but expected {}; not applying", orderId, state.amountPaise(), audit.getAmount());
                audit.setStatus(FAILED);
                audits.save(audit);
                return FAILED;
            }
            apply(audit);
            audit.setStatus(COMPLETED);
            audits.save(audit);
            log.info("Payment {} completed and applied for {}", orderId, audit.getMerchantUserId());
            return COMPLETED;
        }
        if (state.failed()) {
            audit.setStatus(FAILED);
            audits.save(audit);
            if (notifications != null) {
                notifications.notify(audit.getMerchantUserId(), new com.afklive.streamer.service.NotificationService.Alert(
                        com.afklive.streamer.service.NotificationService.Types.PAYMENT_FAILED,
                        com.afklive.streamer.model.Notification.Level.ERROR, null, "Your payment didn't go through",
                        "No money was taken, or it will be refunded by your bank. Try again from Billing.", "/settings#billing",
                        "payment:" + orderId, java.time.Duration.ofDays(1), true));
            }
            return FAILED;
        }
        return INITIATED;
    }

    /** Confirms an order the given user owns (used when the user returns from checkout). */
    public String confirmForUser(String orderId, String email) {
        PaymentAudit audit = audits.findByTransactionId(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown payment."));
        if (!email.equals(audit.getMerchantUserId())) {
            throw new IllegalArgumentException("Unknown payment.");
        }
        return confirm(orderId);
    }

    private void apply(PaymentAudit audit) {
        String email = audit.getMerchantUserId();
        if (BALANCE_CLEAR.equals(audit.getPlanId())) {
            userService.clearUnpaidBalance(email, audit.getAmount() / 100.0);
        } else {
            userService.updatePlan(email, PlanType.valueOf(audit.getPlanId()));
        }
    }
}
