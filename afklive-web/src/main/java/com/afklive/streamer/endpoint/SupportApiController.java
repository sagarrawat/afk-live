package com.afklive.streamer.endpoint;

import com.afklive.streamer.model.DestinationTest;
import com.afklive.streamer.service.EmailService;
import com.afklive.streamer.service.SupportService;
import com.afklive.streamer.util.SecurityUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

/**
 * Support tools in the admin console (OPS-1 email, AD-1 / AD-3 run detail). {@code /api/admin/**} is
 * ADMIN-only in SecurityConfig. Nothing here returns a full stream key.
 */
@RestController
@RequestMapping("/api/admin")
public class SupportApiController {

    private final SupportService support;

    public SupportApiController(SupportService support) {
        this.support = support;
    }

    public record Reason(String reason) {
    }

    @GetMapping("/email")
    public SupportService.EmailStatus email() {
        return support.emailStatus();
    }

    /** Sends a test email to the signed-in admin's own address. */
    @PostMapping("/email/test")
    public EmailService.Result testEmail(@RequestBody Reason body, Principal p) {
        return support.sendTestEmail(SecurityUtils.getEmail(p), body.reason());
    }

    @GetMapping("/jobs/{id}")
    public SupportService.RunDetail run(@PathVariable Long id) {
        return support.run(id);
    }

    /** {@code position} is 0-based, as in the run's destination list. */
    @PostMapping("/jobs/{id}/destinations/{position}/test")
    public DestinationTest testDestination(@PathVariable Long id, @PathVariable int position, @RequestBody Reason body, Principal p) {
        return support.testDestination(SecurityUtils.getEmail(p), id, position, body.reason());
    }

    @GetMapping("/destination-tests/{id}")
    public DestinationTest destinationTest(@PathVariable Long id) {
        return support.destinationTest(id);
    }
}
