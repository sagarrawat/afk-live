package com.afklive.streamer.service;

import com.afklive.streamer.model.TransactionLedger;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.TransactionLedgerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class LedgerServiceTest {

    @Mock
    private TransactionLedgerRepository ledgerRepository;

    @InjectMocks
    private LedgerService ledgerService;

    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = new User();
        testUser.setId(1L);
        testUser.setEmail("test@afklive.in");
        testUser.setUnpaidBalance(new BigDecimal("50.00")); // Balance is conceptually -50
    }

    @Test
    void testRecordDeposit() {
        BigDecimal depositAmount = new BigDecimal("50.00");
        String reference = "TXN123";
        String description = "Payment Recharge";

        ledgerService.recordTransaction(testUser, depositAmount, TransactionLedger.TransactionType.DEPOSIT, reference, description);

        ArgumentCaptor<TransactionLedger> captor = ArgumentCaptor.forClass(TransactionLedger.class);
        verify(ledgerRepository, times(1)).save(captor.capture());

        TransactionLedger saved = captor.getValue();
        assertEquals(testUser, saved.getUser());
        assertEquals(0, depositAmount.compareTo(saved.getAmount()));
        assertEquals(TransactionLedger.TransactionType.DEPOSIT, saved.getTransactionType());
        assertEquals(reference, saved.getReferenceId());
        assertEquals(description, saved.getDescription());
        
        // unpaidBalance = 50.00, meaning available credit is -50.00 conceptually
        assertEquals(0, new BigDecimal("-50.00").compareTo(saved.getBalanceAfter()));
    }

    @Test
    void testRecordStreamUsage() {
        BigDecimal usageAmount = new BigDecimal("-5.50"); // usage is negative
        String reference = "JOB-42";
        String description = "Stream Usage: Live event";

        ledgerService.recordTransaction(testUser, usageAmount, TransactionLedger.TransactionType.STREAM_USAGE, reference, description);

        ArgumentCaptor<TransactionLedger> captor = ArgumentCaptor.forClass(TransactionLedger.class);
        verify(ledgerRepository, times(1)).save(captor.capture());

        TransactionLedger saved = captor.getValue();
        assertEquals(0, usageAmount.compareTo(saved.getAmount()));
        assertEquals(TransactionLedger.TransactionType.STREAM_USAGE, saved.getTransactionType());
    }
}
