package com.afklive.streamer.service;

import com.afklive.streamer.model.TransactionLedger;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.TransactionLedgerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
@RequiredArgsConstructor
public class LedgerService {

    private final TransactionLedgerRepository ledgerRepo;

    @Transactional(propagation = Propagation.REQUIRED)
    public void recordTransaction(User user, BigDecimal amount, TransactionLedger.TransactionType type, String referenceId, String description) {
        // Assume the user's unpaidBalance has already been updated in the current transaction.
        // The balance_after is the inverse of unpaidBalance (if unpaidBalance is -10, they have +10 credit).
        // Let's store positive for credit, negative for debt.
        BigDecimal currentBalance = user.getUnpaidBalance().negate().setScale(2, RoundingMode.HALF_UP);

        TransactionLedger ledger = new TransactionLedger();
        ledger.setUser(user);
        ledger.setAmount(amount.setScale(4, RoundingMode.HALF_UP));
        ledger.setBalanceAfter(currentBalance);
        ledger.setTransactionType(type);
        ledger.setReferenceId(referenceId);
        ledger.setDescription(description);
        
        ledgerRepo.save(ledger);
    }
}
