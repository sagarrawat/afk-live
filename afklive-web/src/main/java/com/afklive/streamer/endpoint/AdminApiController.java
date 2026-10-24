package com.afklive.streamer.endpoint;

import com.afklive.streamer.model.AdminAction;
import com.afklive.streamer.service.AdminConsoleService;
import com.afklive.streamer.service.AdminService;
import com.afklive.streamer.service.StatusService;
import com.afklive.streamer.util.SecurityUtils;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/** Admin console API (AD-1, AD-2, AD-3, YT-4, RE-7 incidents). {@code /api/admin/**} is ADMIN-only in SecurityConfig. */
@RestController
@RequestMapping("/api/admin")
public class AdminApiController {

    private final AdminService admin;
    private final StatusService status;
    private final AdminConsoleService console;

    public AdminApiController(AdminService admin, StatusService status, AdminConsoleService console) {
        this.admin = admin;
        this.status = status;
        this.console = console;
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
    /** {@code filter}: paying, live or never (went live); blank lists everyone. Newest accounts first. */
    public Page<AdminConsoleService.UserRow> users(@RequestParam(required = false) String q, @RequestParam(required = false) String filter,
                                                   @RequestParam(defaultValue = "0") int page) {
        return console.users(q, filter, page);
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

    /** YT-4: YouTube API units spent today against the daily quota. */
    @GetMapping("/quota")
    public AdminService.QuotaView quota() {
        return admin.quota();
    }

    /** AD-2: server cost per stream hour over the last 30 days. */
    @GetMapping("/cost")
    public AdminService.CostView cost() {
        return admin.cost();
    }

    // ------------------------------------------------------------------ overview and platform settings

    @GetMapping("/overview")
    public AdminService.Overview overview() {
        return admin.overview();
    }

    public record FreeStreamLimit(Integer limit, String reason) {
    }

    /** Free accounts can't go live once this many streams (all plans together) are live. */
    @PostMapping("/settings/free-stream-limit")
    public AdminService.Overview freeStreamLimit(@RequestBody FreeStreamLimit body, Principal p) {
        admin.setFreeStreamLimit(SecurityUtils.getEmail(p), body.limit(), body.reason());
        return admin.overview();
    }

    // ------------------------------------------------------------------ support tickets

    @GetMapping("/tickets")
    public Page<AdminService.TicketRow> tickets(@RequestParam(required = false) String status, @RequestParam(defaultValue = "0") int page) {
        return admin.tickets(status, page);
    }

    @GetMapping("/tickets/{id}")
    public AdminService.TicketRow ticket(@PathVariable Long id) {
        return admin.ticket(id);
    }

    public record TicketStatus(String status, String reason) {
    }

    @PostMapping("/tickets/{id}/status")
    public AdminService.TicketRow ticketStatus(@PathVariable Long id, @RequestBody TicketStatus body, Principal p) {
        return admin.setTicketStatus(SecurityUtils.getEmail(p), id, body.status(), body.reason());
    }

    /** Always a download (never rendered inline on our origin); images still preview through an img tag. */
    @GetMapping("/tickets/{id}/attachment")
    public ResponseEntity<Resource> attachment(@PathVariable Long id) {
        AdminService.Attachment a = admin.attachment(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(a.name(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(a.contentType()))
                .body(a.file());
    }

    // ------------------------------------------------------------------ plan configuration

    @GetMapping("/plans")
    public List<AdminService.PlanView> plans() {
        return admin.planConfigs();
    }

    public record PlanUpdate(AdminService.PlanEdit plan, String reason) {
    }

    @PostMapping("/plans/{plan}")
    public AdminService.PlanView updatePlan(@PathVariable String plan, @RequestBody PlanUpdate body, Principal p) {
        return admin.updatePlanConfig(SecurityUtils.getEmail(p), plan, body.plan(), body.reason());
    }

    // ------------------------------------------------------------------ RE-7: status page incidents

    @GetMapping("/status/incidents")
    public List<StatusService.IncidentWithAuthor> incidents() {
        return status.incidentList();
    }

    public record NewIncident(String title, String status, String message) {
    }

    @PostMapping("/status/incidents")
    public StatusService.Incident openIncident(@RequestBody NewIncident body, Principal p) {
        return status.open(SecurityUtils.getEmail(p), body.title(), body.status(), body.message());
    }

    public record IncidentUpdate(String status, String message) {
    }

    @PostMapping("/status/incidents/{id}/updates")
    public StatusService.Incident updateIncident(@PathVariable Long id, @RequestBody IncidentUpdate body, Principal p) {
        return status.update(SecurityUtils.getEmail(p), id, body.status(), body.message());
    }
}
