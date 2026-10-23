package com.afklive.streamer.endpoint;

import com.afklive.streamer.model.AdminAction;
import com.afklive.streamer.service.AdminService;
import com.afklive.streamer.util.SecurityUtils;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/** Admin console API (AD-1, AD-3). {@code /api/admin/**} is ADMIN-only in SecurityConfig. */
@RestController
@RequestMapping("/api/admin")
public class AdminApiController {

    private final AdminService admin;

    public AdminApiController(AdminService admin) {
        this.admin = admin;
    }

    public record Reason(String reason) {
    }

    @GetMapping("/live")
    public List<AdminService.LiveRow> live(@RequestParam(required = false) String q, @RequestParam(required = false) String health,
                                           @RequestParam(required = false) String worker) {
        return admin.liveStreams(q, health, worker);
    }

    @GetMapping("/workers")
    public List<AdminService.WorkerRow> workers() {
        return admin.workers();
    }

    @PostMapping("/jobs/{id}/stop")
    public Map<String, Object> stop(@PathVariable Long id, @RequestBody Reason body, Principal p) {
        admin.stopStream(SecurityUtils.getEmail(p), id, body.reason());
        return Map.of("ok", true);
    }

    @PostMapping("/jobs/{id}/move")
    public Map<String, Object> move(@PathVariable Long id, @RequestBody Reason body, Principal p) {
        admin.moveStream(SecurityUtils.getEmail(p), id, body.reason());
        return Map.of("ok", true, "note", "Another worker picks it up within a few seconds; destinations reconnect.");
    }

    @GetMapping("/users")
    public Page<AdminService.UserRow> users(@RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page) {
        return admin.searchUsers(q, page);
    }

    @GetMapping("/users/{id}")
    public AdminService.UserDetail user(@PathVariable Long id) {
        return admin.user(id);
    }

    public record SetPlan(String plan, ZonedDateTime until, String reason) {
    }

    @PostMapping("/users/{id}/plan")
    public AdminService.UserDetail plan(@PathVariable Long id, @RequestBody SetPlan body, Principal p) {
        admin.setPlan(SecurityUtils.getEmail(p), id, body.plan(), body.until(), body.reason());
        return admin.user(id);
    }

    public record Extend(int days, String reason) {
    }

    @PostMapping("/users/{id}/extend")
    public AdminService.UserDetail extend(@PathVariable Long id, @RequestBody Extend body, Principal p) {
        admin.extend(SecurityUtils.getEmail(p), id, body.days(), body.reason());
        return admin.user(id);
    }

    public record Enabled(Boolean enabled, String reason) {
    }

    @PostMapping("/users/{id}/enabled")
    public AdminService.UserDetail enabled(@PathVariable Long id, @RequestBody Enabled body, Principal p) {
        if (body.enabled() == null) {
            throw new IllegalArgumentException("Say whether the account is enabled.");
        }
        admin.setEnabled(SecurityUtils.getEmail(p), id, body.enabled(), body.reason());
        return admin.user(id);
    }

    public record Refund(long amount, Boolean endPlan, String reason) {
    }

    @PostMapping("/payments/{orderId}/refund")
    public Map<String, Object> refund(@PathVariable String orderId, @RequestBody Refund body, Principal p) {
        admin.recordRefund(SecurityUtils.getEmail(p), orderId, body.amount(), Boolean.TRUE.equals(body.endPlan()), body.reason());
        return Map.of("ok", true);
    }

    @GetMapping("/audit")
    public Page<AdminAction> audit(@RequestParam(defaultValue = "0") int page) {
        return admin.auditLog(page);
    }
}
