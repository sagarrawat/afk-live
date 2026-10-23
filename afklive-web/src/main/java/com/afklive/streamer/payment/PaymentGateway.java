package com.afklive.streamer.payment;

/**
 * The payment provider, behind an interface so payment confirmation logic can be tested
 * without calling the provider.
 */
public interface PaymentGateway {

    /** Creates a checkout for {@code amountPaise} and returns the URL to send the user to. */
    String createCheckout(String merchantOrderId, long amountPaise, String redirectUrl);

    /** Asks the provider for the order's current state. Never trust a client or callback for this. */
    OrderState fetchOrderState(String merchantOrderId);

    /**
     * Verifies a server-to-server callback's authenticity and returns the merchant order id it refers to.
     *
     * @throws SecurityException when the callback can't be verified
     */
    String verifyCallback(String authorizationHeader, String body);

    /** Provider order state; {@code state} is one of COMPLETED, FAILED, PENDING. */
    record OrderState(String state, long amountPaise) {
        public boolean completed() {
            return "COMPLETED".equals(state);
        }

        public boolean failed() {
            return "FAILED".equals(state);
        }
    }
}
