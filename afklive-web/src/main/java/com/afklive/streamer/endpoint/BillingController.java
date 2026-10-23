package com.afklive.streamer.endpoint;

import com.afklive.streamer.model.BillingProfile;
import com.afklive.streamer.service.BillingService;
import com.afklive.streamer.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;

/** BI-2, BI-3, BI-5, BI-6: plans, checkout, changes at period end, billing details and invoices. */
@RestController
@RequestMapping("/api/billing")
@RequiredArgsConstructor
public class BillingController {

    private final BillingService billing;

    private static String email(Principal principal) {
        if (principal == null) {
            throw new IllegalStateException("Sign in first.");
        }
        return SecurityUtils.getEmail(principal);
    }

    /** Public: the pricing page shows exactly these limits and prices (MK-2). */
    @GetMapping("/plans")
    public List<BillingService.PlanView> plans(@RequestParam(defaultValue = "INR") String currency) {
        return billing.catalog(currency);
    }

    @GetMapping
    public BillingService.Overview overview(Principal principal) {
        return billing.overview(email(principal));
    }

    /** What a plan change would cost now (with the upgrade credit), before paying. */
    @GetMapping("/quote")
    public BillingService.Quote quote(@RequestParam String plan, @RequestParam(defaultValue = "MONTHLY") String cycle,
                                      Principal principal) {
        return billing.quote(email(principal), plan, cycle);
    }

    @PostMapping("/checkout")
    public Map<String, Object> checkout(@RequestBody Map<String, String> body, Principal principal) {
        BillingService.Checkout c = billing.checkout(email(principal), body.get("plan"), body.getOrDefault("cycle", "MONTHLY"));
        return Map.of("orderId", c.orderId(), "checkoutUrl", c.checkoutUrl(), "amount", c.quote().amount(),
                "credit", c.quote().credit(), "currency", c.quote().currency());
    }

    @PostMapping("/downgrade")
    public BillingService.Overview downgrade(@RequestBody Map<String, String> body, Principal principal) {
        billing.downgrade(email(principal), body.get("plan"), body.get("cycle"));
        return billing.overview(email(principal));
    }

    @PostMapping("/cancel")
    public BillingService.Overview cancel(Principal principal) {
        billing.cancel(email(principal));
        return billing.overview(email(principal));
    }

    @PostMapping("/resume")
    public BillingService.Overview resume(Principal principal) {
        billing.resume(email(principal));
        return billing.overview(email(principal));
    }

    @GetMapping("/profile")
    public BillingProfile profile(Principal principal) {
        return billing.profile(email(principal));
    }

    @PutMapping("/profile")
    public BillingProfile saveProfile(@RequestBody BillingProfile body, Principal principal) {
        return billing.saveProfile(email(principal), body);
    }

    @GetMapping("/invoices/{id}.pdf")
    public ResponseEntity<byte[]> invoice(@PathVariable Long id, Principal principal) {
        byte[] pdf = billing.invoicePdf(email(principal), id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("afklive-invoice-" + id + ".pdf").build().toString())
                .body(pdf);
    }
}
