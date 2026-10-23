package com.afklive.streamer.payment;

import com.afklive.streamer.model.PaymentAudit;
import com.afklive.streamer.model.PlanType;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.PaymentAuditRepository;
import com.afklive.streamer.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentServiceTest {

    private static final String EMAIL = "buyer@example.com";

    private PaymentGateway gateway;
    private PaymentAuditRepository audits;
    private UserService userService;
    private PaymentService service;
    private User user;

    @BeforeEach
    void setUp() {
        gateway = mock(PaymentGateway.class);
        audits = mock(PaymentAuditRepository.class);
        userService = mock(UserService.class);
        service = new PaymentService(gateway, audits, userService);
        user = new User(EMAIL);
        user.setPlanType(PlanType.FREE);
        when(userService.getOrCreateUser(EMAIL)).thenReturn(user);
        when(gateway.createCheckout(anyString(), anyLong(), anyString())).thenReturn("https://pay.example/checkout");
    }

    private PaymentAudit pending(String orderId, String planId, long amount) {
        PaymentAudit audit = new PaymentAudit(orderId, EMAIL, amount, PaymentService.INITIATED);
        audit.setPlanId(planId);
        when(audits.findForUpdateByTransactionId(orderId)).thenReturn(Optional.of(audit));
        when(audits.findByTransactionId(orderId)).thenReturn(Optional.of(audit));
        return audit;
    }

    @Test
    void checkoutAmountIsSetByTheServer() {
        service.startCheckout(EMAIL, "ESSENTIALS");
        verify(gateway).createCheckout(anyString(), eq(PaymentService.ESSENTIALS_PRICE_PAISE), anyString());
    }

    @Test
    void balanceClearChargesTheRealBalance() {
        user.setUnpaidBalance(12.34);
        service.startCheckout(EMAIL, PaymentService.BALANCE_CLEAR);
        verify(gateway).createCheckout(anyString(), eq(1234L), anyString());
    }

    @Test
    void balanceClearWithNothingOwedIsRejected() {
        assertThatThrownBy(() -> service.startCheckout(EMAIL, PaymentService.BALANCE_CLEAR))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void freePlanCannotBeBought() {
        assertThatThrownBy(() -> service.startCheckout(EMAIL, "FREE")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.startCheckout(EMAIL, "NOT_A_PLAN")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void redirectBringsTheOrderIdBack() {
        service.startCheckout(EMAIL, "ESSENTIALS");
        ArgumentCaptor<String> order = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> redirect = ArgumentCaptor.forClass(String.class);
        verify(gateway).createCheckout(order.capture(), anyLong(), redirect.capture());
        assertThat(redirect.getValue()).endsWith("payment_order=" + order.getValue());
    }

    @Test
    void completedOrderUpgradesExactlyOnce() {
        pending("MT1", "ESSENTIALS", 19900);
        when(gateway.fetchOrderState("MT1")).thenReturn(new PaymentGateway.OrderState("COMPLETED", 19900));

        assertThat(service.confirm("MT1")).isEqualTo("COMPLETED");
        assertThat(service.confirm("MT1")).isEqualTo("COMPLETED"); // replayed callback
        verify(userService, times(1)).updatePlan(EMAIL, PlanType.ESSENTIALS);
        verify(gateway, times(1)).fetchOrderState("MT1");
    }

    @Test
    void balanceClearIsDeductedOnce() {
        pending("MT2", PaymentService.BALANCE_CLEAR, 1234);
        when(gateway.fetchOrderState("MT2")).thenReturn(new PaymentGateway.OrderState("COMPLETED", 1234));

        service.confirm("MT2");
        service.confirm("MT2");
        verify(userService, times(1)).clearUnpaidBalance(EMAIL, 12.34);
    }

    @Test
    void pendingOrderChangesNothing() {
        pending("MT3", "ESSENTIALS", 19900);
        when(gateway.fetchOrderState("MT3")).thenReturn(new PaymentGateway.OrderState("PENDING", 19900));

        assertThat(service.confirm("MT3")).isEqualTo(PaymentService.INITIATED);
        verify(userService, never()).updatePlan(any(), any());
    }

    @Test
    void wrongAmountIsNotApplied() {
        PaymentAudit audit = pending("MT4", "ESSENTIALS", 19900);
        when(gateway.fetchOrderState("MT4")).thenReturn(new PaymentGateway.OrderState("COMPLETED", 100));

        assertThat(service.confirm("MT4")).isEqualTo("FAILED");
        assertThat(audit.getStatus()).isEqualTo("FAILED");
        verify(userService, never()).updatePlan(any(), any());
    }

    @Test
    void failedOrderIsRecorded() {
        PaymentAudit audit = pending("MT5", "ESSENTIALS", 19900);
        when(gateway.fetchOrderState("MT5")).thenReturn(new PaymentGateway.OrderState("FAILED", 19900));

        assertThat(service.confirm("MT5")).isEqualTo("FAILED");
        assertThat(audit.getStatus()).isEqualTo("FAILED");
        verify(userService, never()).clearUnpaidBalance(anyString(), anyDouble());
    }

    @Test
    void usersCanOnlyCheckTheirOwnOrders() {
        pending("MT6", "ESSENTIALS", 19900);
        assertThatThrownBy(() -> service.confirmForUser("MT6", "someone-else@example.com"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(gateway, never()).fetchOrderState(anyString());
    }
}
