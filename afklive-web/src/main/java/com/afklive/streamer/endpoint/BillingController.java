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
    private final com.afklive.streamer.service.OfferService offers;
    private final com.afklive.streamer.repository.UserRepository users;

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

    /**
     * Public (BI-9): the launch offer open now with each plan's real list and offer price, for the pricing page and
     * the app; 204 when there is none, and the page then shows only the list prices. {@code eligible} says whether
     * the signed-in account can still take it (null when signed out).
     */
    @GetMapping("/offer")
    public ResponseEntity<com.afklive.streamer.service.OfferService.PublicOffer> offer(@RequestParam(defaultValue = "INR") String currency,
                                                                                    Principal principal) {
        com.afklive.streamer.model.User viewer = principal == null ? null : users.findByEmail(SecurityUtils.getEmail(principal)).orElse(null);
        return offers.publicOffer(currency, viewer, java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC))
                .map(o -> ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(o))
                .orElseGet(() -> ResponseEntity.noContent().cacheControl(org.springframework.http.CacheControl.noStore()).build());
    }

    /** What a plan change would cost now (launch offer, upgrade credit), before paying. */
    @GetMapping("/quote")
    public BillingService.Quote quote(@RequestParam String plan, @RequestParam(defaultValue = "MONTHLY") String cycle,
                                      @RequestParam(required = false) String code, Principal principal) {
        return billing.quote(email(principal), plan, cycle, code);
    }

    @PostMapping("/checkout")
    public Map<String, Object> checkout(@RequestBody Map<String, String> body, Principal principal) {
        BillingService.Checkout c = billing.checkout(email(principal), body.get("plan"), body.getOrDefault("cycle", "MONTHLY"),
                body.get("code"));
        return Map.of("orderId", c.orderId(), "checkoutUrl", c.checkoutUrl(), "amount", c.quote().amount(),
                "credit", c.quote().credit(), "currency", c.quote().currency(), "discount", c.quote().discount());
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
