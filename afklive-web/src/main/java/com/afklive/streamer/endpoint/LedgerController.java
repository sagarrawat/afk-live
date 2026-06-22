package com.afklive.streamer.endpoint;

import com.afklive.streamer.model.TransactionLedger;
import com.afklive.streamer.repository.TransactionLedgerRepository;
import com.afklive.streamer.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

@RestController
@RequestMapping("/api/ledger")
@RequiredArgsConstructor
public class LedgerController {

    private final TransactionLedgerRepository ledgerRepository;

    @GetMapping
    public ResponseEntity<Page<TransactionLedger>> getLedger(
            Principal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (principal == null) {
            return ResponseEntity.status(401).build();
        }
        
        String username = SecurityUtils.getEmail(principal);
        Pageable pageable = PageRequest.of(page, size);
        Page<TransactionLedger> transactions = ledgerRepository.findByUserEmailOrderByCreatedAtDesc(username, pageable);
        
        return ResponseEntity.ok(transactions);
    }
}
