package com.afklive.streamer.payment;

import com.phonepe.sdk.pg.Env;
import com.phonepe.sdk.pg.common.models.response.CallbackResponse;
import com.phonepe.sdk.pg.common.models.response.OrderStatusResponse;
import com.phonepe.sdk.pg.payments.v2.StandardCheckoutClient;
import com.phonepe.sdk.pg.payments.v2.models.request.StandardCheckoutPayRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * PhonePe Standard Checkout (SDK v2). v2 authenticates with OAuth client credentials
 * (client id / secret / version from the PhonePe dashboard), not the v1 merchant id + salt key.
 * Callbacks are verified with the username/password configured for the webhook in the dashboard.
 */
@Component
@Slf4j
public class PhonePeGateway implements PaymentGateway {

    private final String clientId;
    private final String clientSecret;
    private final int clientVersion;
    private final Env env;
    private final String callbackUsername;
    private final String callbackPassword;
    private volatile StandardCheckoutClient client;

    public PhonePeGateway(@Value("${app.phonepe.client-id:}") String clientId,
                          @Value("${app.phonepe.client-secret:}") String clientSecret,
                          @Value("${app.phonepe.client-version:1}") int clientVersion,
                          @Value("${app.phonepe.env:SANDBOX}") String env,
                          @Value("${app.phonepe.callback-username:}") String callbackUsername,
                          @Value("${app.phonepe.callback-password:}") String callbackPassword) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.clientVersion = clientVersion;
        this.env = Env.valueOf(env.trim().toUpperCase(Locale.ROOT));
        this.callbackUsername = callbackUsername;
        this.callbackPassword = callbackPassword;
    }

    /** Created on first use so the app starts without payment credentials (e.g. in development). */
    private StandardCheckoutClient client() {
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    if (clientId.isBlank() || clientSecret.isBlank()) {
                        throw new IllegalStateException("Payments aren't configured on this server.");
                    }
                    client = StandardCheckoutClient.getInstance(clientId, clientSecret, clientVersion, env);
                    log.info("Initialized PhonePe client (env={})", env);
                }
            }
        }
        return client;
    }

    @Override
    public String createCheckout(String merchantOrderId, long amountPaise, String redirectUrl) {
        StandardCheckoutPayRequest request = StandardCheckoutPayRequest.builder()
                .merchantOrderId(merchantOrderId)
                .amount(amountPaise)
                .redirectUrl(redirectUrl)
                .build();
        return client().pay(request).getRedirectUrl();
    }

    @Override
    public OrderState fetchOrderState(String merchantOrderId) {
        OrderStatusResponse status = client().getOrderStatus(merchantOrderId);
        return new OrderState(status.getState(), status.getAmount());
    }

    @Override
    public String verifyCallback(String authorizationHeader, String body) {
        if (callbackUsername.isBlank() || callbackPassword.isBlank()) {
            throw new SecurityException("Payment callback credentials aren't configured.");
        }
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            throw new SecurityException("Missing callback authorization.");
        }
        try {
            CallbackResponse callback = client().validateCallback(callbackUsername, callbackPassword, authorizationHeader, body);
            if (callback.getPayload() == null || callback.getPayload().getMerchantOrderId() == null) {
                throw new SecurityException("Callback has no order id.");
            }
            return callback.getPayload().getMerchantOrderId();
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            throw new SecurityException("Callback verification failed.", e);
        }
    }
}
