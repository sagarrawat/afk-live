package com.afklive.streamer.endpoint;

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
 * Support tools in the admin console (OPS-1 email, AD-6 run detail). {@code /api/admin/**} is
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

    @GetMapping("/users/{id}/support")
    public SupportService.UserSupport userSupport(@PathVariable Long id) {
        return support.userSupport(id);
    }

    /** OPS-1: a one-time password reset link to give someone who wrote in (email off). */
    @PostMapping("/users/{id}/reset-link")
    public java.util.Map<String, String> resetLink(@PathVariable Long id, @RequestBody Reason body, Principal p) {
        return java.util.Map.of("link", support.passwordResetLink(SecurityUtils.getEmail(p), id, body.reason()));
    }

    @GetMapping("/jobs/{id}")
    public SupportService.RunDetail run(@PathVariable Long id) {
        return support.run(id);
    }

    /** {@code position} is 0-based, as in the run's destination list. */
    @PostMapping("/jobs/{id}/destinations/{position}/test")
    public SupportService.TestView testDestination(@PathVariable Long id, @PathVariable int position, @RequestBody Reason body, Principal p) {
        return support.testDestination(SecurityUtils.getEmail(p), id, position, body.reason());
    }

    @GetMapping("/destination-tests/{id}")
    public SupportService.TestView destinationTest(@PathVariable Long id) {
        return support.destinationTest(id);
    }
}
