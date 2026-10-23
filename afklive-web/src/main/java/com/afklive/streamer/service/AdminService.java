package com.afklive.streamer.service;

import com.afklive.streamer.dto.StreamDtos;
import com.afklive.streamer.exception.ConflictException;
import com.afklive.streamer.exception.NotFoundException;
import com.afklive.streamer.model.AdminAction;
import com.afklive.streamer.model.Invoice;
import com.afklive.streamer.model.LiveStream;
import com.afklive.streamer.model.PaymentAudit;
import com.afklive.streamer.model.PlanType;
import com.afklive.streamer.model.StreamJob;
import com.afklive.streamer.model.Subscription;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.AdminActionRepository;
import com.afklive.streamer.repository.InvoiceRepository;
import com.afklive.streamer.repository.LiveStreamRepository;
import com.afklive.streamer.repository.PaymentAuditRepository;
import com.afklive.streamer.repository.StreamJobRepository;
import com.afklive.streamer.repository.SubscriptionRepository;
import com.afklive.streamer.repository.UsageRepository;
import com.afklive.streamer.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The admin console's back end. AD-1: every live stream with its worker and health, stop or move it;
 * worker load. AD-3: find a user and see their plan, payments, invoices, streams and usage; change the
 * plan, extend a subscription, disable an account, record a refund. Every change needs a reason and
 * is written to {@code admin_actions}.
 */
@Service
@Slf4j
public class AdminService {

    private final StreamJobRepository jobs;
    private final LiveStreamRepository streams;
    private final UserRepository users;
    private final SubscriptionRepository subscriptions;
    private final PaymentAuditRepository payments;
    private final InvoiceRepository invoices;
    private final UsageRepository usage;
    private final PlanService plans;
    private final StreamService streamService;
    private final AdminActionRepository audit;
    private final NotificationService notifications;

    public AdminService(StreamJobRepository jobs, LiveStreamRepository streams, UserRepository users,
                        SubscriptionRepository subscriptions, PaymentAuditRepository payments, InvoiceRepository invoices,
                        UsageRepository usage, PlanService plans, StreamService streamService, AdminActionRepository audit,
                        NotificationService notifications) {
        this.jobs = jobs;
        this.streams = streams;
        this.users = users;
        this.subscriptions = subscriptions;
        this.payments = payments;
        this.invoices = invoices;
        this.usage = usage;
        this.plans = plans;
        this.streamService = streamService;
        this.audit = audit;
        this.notifications = notifications;
    }

    // ------------------------------------------------------------------ AD-1: live streams and workers

    public record LiveRow(Long jobId, Long streamId, String streamName, String userEmail, Long userId, String plan,
                          String worker, Long heartbeatAgeSeconds, String status, String health, long uptimeSeconds,
                          String nowPlaying, Boolean inputLive, int restarts, Double bitrateKbps, Double fps, Double speed,
                          String lastError, int destinations, List<StreamDtos.DestinationHealth> destinationHealth) {
    }

    public List<LiveRow> liveStreams(String q, String health, String worker) {
        ZonedDateTime now = now();
        List<LiveRow> out = new ArrayList<>();
        Map<Long, Optional<LiveStream>> streamCache = new HashMap<>();
        for (StreamJob j : jobs.findAllByIsLiveTrue()) {
            Optional<LiveStream> s = j.getStreamId() == null ? Optional.empty()
                    : streamCache.computeIfAbsent(j.getStreamId(), streams::findById);
            int height = s.map(LiveStream::getQualityHeight).orElse(1080);
            int fps = s.map(LiveStream::getFps).orElse(30);
            StreamDtos.Health h = StreamHealth.of(j, height, fps);
            Long age = j.getHeartbeatAt() == null ? null : Duration.between(j.getHeartbeatAt(), now).getSeconds();
            LiveRow row = new LiveRow(j.getId(), j.getStreamId(), s.map(LiveStream::getName).orElse(j.getTitle()),
                    j.getUser().getEmail(), j.getUser().getId(), j.getUser().getPlanType().name(), j.getWorkerId(), age,
                    j.getStatus(), h.overall(), j.getStartTime() == null ? 0 : Math.max(0, Duration.between(j.getStartTime(), now).getSeconds()),
                    j.getNowPlaying(), j.getInputLive(), j.getRestarts(), j.getBitrateKbps(), j.getFps(), j.getSpeed(), j.getLastError(),
                    h.destinations().size(), h.destinations());
            if (q != null && !q.isBlank()) {
                String needle = q.trim().toLowerCase(Locale.ROOT);
                boolean match = row.userEmail().toLowerCase(Locale.ROOT).contains(needle)
                        || (row.streamName() != null && row.streamName().toLowerCase(Locale.ROOT).contains(needle))
                        || String.valueOf(row.jobId()).equals(needle);
                if (!match) {
                    continue;
                }
            }
            if (health != null && !health.isBlank() && !health.equalsIgnoreCase(row.health())) {
                continue;
            }
            if (worker != null && !worker.isBlank() && !worker.equals(row.worker())) {
                continue;
            }
            out.add(row);
        }
        // Problems first, then the longest-running.
        out.sort(Comparator.comparingInt((LiveRow r) -> healthRank(r.health())).thenComparing(LiveRow::uptimeSeconds, Comparator.reverseOrder()));
        return out;
    }

    private static int healthRank(String h) {
        return StreamHealth.RED.equals(h) ? 0 : StreamHealth.AMBER.equals(h) ? 1 : 2;
    }

    public record WorkerRow(String worker, int streams, long lastHeartbeatAgeSeconds, boolean healthy) {
    }

    /** Workers seen through the jobs they run (a worker with no streams isn't listed). */
    public List<WorkerRow> workers() {
        ZonedDateTime now = now();
        Map<String, List<StreamJob>> byWorker = new HashMap<>();
        for (StreamJob j : jobs.findAllByIsLiveTrue()) {
            byWorker.computeIfAbsent(j.getWorkerId() == null ? "(unassigned)" : j.getWorkerId(), k -> new ArrayList<>()).add(j);
        }
        List<WorkerRow> out = new ArrayList<>();
        byWorker.forEach((w, list) -> {
            long newest = list.stream().filter(j -> j.getHeartbeatAt() != null)
                    .mapToLong(j -> Duration.between(j.getHeartbeatAt(), now).getSeconds()).min().orElse(-1);
            out.add(new WorkerRow(w, list.size(), newest, newest >= 0 && newest < StreamService.HEARTBEAT_TIMEOUT.getSeconds()));
        });
        out.sort(Comparator.comparing(WorkerRow::worker));
        return out;
    }

    @Transactional
    public void stopStream(String admin, Long jobId, String reason) {
        StreamJob j = liveJob(jobId);
        String why = requireReason(reason);
        streamService.requestStop(j, "Stopped by AFK Live support: " + why);
        record(admin, "STREAM_STOP", "job", jobId, why);
        notifications.notify(j.getUser(), new NotificationService.Alert("ADMIN_STOP", com.afklive.streamer.model.Notification.Level.WARNING,
                j.getStreamId(), "Your stream was stopped by support", why, "/support", null, null, true));
    }

    /** Releases the stream from its worker so another picks it up; a reconnect of a few seconds. */
    @Transactional
    public void moveStream(String admin, Long jobId, String reason) {
        StreamJob j = liveJob(jobId);
        String why = requireReason(reason);
        if (j.getWorkerId() == null) {
            throw new ConflictException("This stream isn't on a worker yet.");
        }
        if (jobs.release(jobId, j.getWorkerId(), now()) != 1) {
            throw new ConflictException("The stream changed meanwhile (stopped or moved). Refresh and try again.");
        }
        record(admin, "STREAM_MOVE", "job", jobId, "from " + j.getWorkerId() + ": " + why);
    }

    private StreamJob liveJob(Long jobId) {
        StreamJob j = jobs.findById(jobId).orElseThrow(() -> new NotFoundException("Stream run not found."));
        if (!j.isLive()) {
            throw new ConflictException("This stream has already ended.");
        }
        return j;
    }

    // ------------------------------------------------------------------ AD-3: users

    public record UserRow(Long id, String email, String name, String plan, boolean enabled, String role, int liveStreams,
                          long storageBytes) {
    }

    public Page<UserRow> searchUsers(String q, int page) {
        PageRequest p = PageRequest.of(Math.max(0, page), 25, Sort.by(Sort.Direction.DESC, "id"));
        Page<User> found = q == null || q.isBlank() ? users.findAll(p) : users.search(q.trim().toLowerCase(Locale.ROOT), p);
        return found.map(u -> new UserRow(u.getId(), u.getEmail(), u.getFullName(), u.getPlanType().name(), u.isEnabled(),
                u.getRole(), (int) jobs.countByUserEmailAndIsLiveTrue(u.getEmail()), u.getUsedStorageBytes()));
    }

    public record PaymentRow(String orderId, long amount, String currency, String status, String kind, String plan,
                             String cycle, java.time.LocalDateTime createdAt) {
    }

    public record InvoiceRow(Long id, String number, long total, String currency, ZonedDateTime issuedAt) {
    }

    public record StreamRow(Long id, String name, boolean live, Long jobId, String worker) {
    }

    public record SubscriptionView(String plan, String cycle, String status, String currency, ZonedDateTime periodStart,
                                   ZonedDateTime periodEnd, boolean cancelAtPeriodEnd, String pendingPlan) {
    }

    public record UserDetail(Long id, String email, String name, String plan, boolean enabled, String role,
                             java.time.LocalDateTime planExpiration, SubscriptionView subscription, long storageBytes,
                             long storageLimit, double hoursThisMonth, int hoursIncluded, List<StreamRow> streams,
                             List<PaymentRow> payments, List<InvoiceRow> invoices, List<AdminAction> history) {
    }

    public UserDetail user(Long id) {
        User u = users.findById(id).orElseThrow(() -> new NotFoundException("User not found."));
        var cfg = plans.getPlanConfig(u.getPlanType());
        SubscriptionView sub = subscriptions.findByUserId(u.getId()).map(s -> new SubscriptionView(s.getPlanType().name(),
                s.getCycle().name(), s.getStatus().name(), s.getCurrency(), s.getCurrentPeriodStart(), s.getCurrentPeriodEnd(),
                s.isCancelAtPeriodEnd(), s.getPendingPlan() == null ? null : s.getPendingPlan().name())).orElse(null);
        List<StreamRow> rows = new ArrayList<>();
        for (LiveStream s : streams.findAllByUserEmailOrderByCreatedAtAsc(u.getEmail())) {
            Optional<StreamJob> live = jobs.findAllByStreamIdAndIsLiveTrue(s.getId()).stream().findFirst();
            rows.add(new StreamRow(s.getId(), s.getName(), live.isPresent(), live.map(StreamJob::getId).orElse(null),
                    live.map(StreamJob::getWorkerId).orElse(null)));
        }
        List<PaymentRow> pay = payments.findTop50ByMerchantUserIdOrderByCreatedAtDesc(u.getEmail()).stream()
                .map(p -> new PaymentRow(p.getTransactionId(), p.getAmount() == null ? 0 : p.getAmount(), p.getCurrency(), p.getStatus(),
                        p.getKind(), p.getPlanId(), p.getBillingCycle(), p.getCreatedAt())).toList();
        List<InvoiceRow> inv = invoices.findAllByUserIdOrderByIssuedAtDesc(u.getId()).stream()
                .map(i -> new InvoiceRow(i.getId(), i.getNumber(), i.getTotal(), i.getCurrency(), i.getIssuedAt())).toList();
        double hours = Math.round(usage.secondsIn(u.getId(), BillingService.month(now())) / 36.0) / 100.0;
        return new UserDetail(u.getId(), u.getEmail(), u.getFullName(), u.getPlanType().name(), u.isEnabled(), u.getRole(),
                u.getPlanExpiration(), sub, users.usedStorage(u.getId()), cfg.getMaxStorageBytes(), hours, cfg.getMonthlyHours(),
                rows, pay, inv, audit.findTop50ByTargetTypeAndTargetIdOrderByCreatedAtDesc("user", String.valueOf(u.getId())));
    }

    /**
     * Puts the user on a plan until a date (a comp, or fixing a payment that didn't apply). Their
     * subscription, if any, follows so the period-end job doesn't undo it.
     */
    @Transactional
    public void setPlan(String admin, Long userId, String planId, ZonedDateTime until, String reason) {
        String why = requireReason(reason);
        User u = users.lockById(userId).orElseThrow(() -> new NotFoundException("User not found."));
        PlanType plan;
        try {
            plan = PlanType.valueOf(planId.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Unknown plan.");
        }
        if (plan != PlanType.FREE && (until == null || !until.isAfter(now()))) {
            throw new IllegalArgumentException("A paid plan needs an end date in the future.");
        }
        PlanType before = u.getPlanType();
        u.setPlanType(plan);
        u.setPlanExpiration(plan == PlanType.FREE ? null : until.withZoneSameInstant(ZoneId.of("UTC")).toLocalDateTime());
        users.save(u);
        Optional<Subscription> sub = subscriptions.lockByUserId(userId);
        if (plan == PlanType.FREE) {
            sub.ifPresent(s -> {
                s.setStatus(Subscription.Status.CANCELED);
                s.setPendingPlan(null);
                s.setPendingCycle(null);
                subscriptions.save(s);
            });
        } else {
            Subscription s = sub.orElseGet(() -> {
                Subscription fresh = new Subscription();
                fresh.setUserId(userId);
                fresh.setCycle(Subscription.Cycle.MONTHLY);
                fresh.setCurrency("INR");
                fresh.setCurrentPeriodStart(now());
                return fresh;
            });
            s.setPlanType(plan);
            s.setStatus(Subscription.Status.ACTIVE);
            s.setCurrentPeriodEnd(until);
            s.setCancelAtPeriodEnd(false);
            s.setPendingPlan(null);
            s.setPendingCycle(null);
            s.setRenewalRemindedAt(null);
            subscriptions.save(s);
        }
        record(admin, "PLAN_SET", "user", userId, before + " → " + plan + (until == null ? "" : " until " + until) + ": " + why);
    }

    /** Adds days to the current period (goodwill credit after an outage, say). */
    @Transactional
    public void extend(String admin, Long userId, int days, String reason) {
        String why = requireReason(reason);
        if (days < 1 || days > 366) {
            throw new IllegalArgumentException("Extend by 1 to 366 days.");
        }
        Subscription s = subscriptions.lockByUserId(userId).filter(x -> x.getStatus() != Subscription.Status.CANCELED)
                .orElseThrow(() -> new ConflictException("This user has no paid subscription to extend."));
        User u = users.lockById(userId).orElseThrow();
        ZonedDateTime base = s.getCurrentPeriodEnd().isBefore(now()) ? now() : s.getCurrentPeriodEnd();
        s.setCurrentPeriodEnd(base.plusDays(days));
        s.setStatus(Subscription.Status.ACTIVE);
        s.setRenewalRemindedAt(null);
        subscriptions.save(s);
        u.setPlanType(s.getPlanType());
        u.setPlanExpiration(s.getCurrentPeriodEnd().withZoneSameInstant(ZoneId.of("UTC")).toLocalDateTime());
        users.save(u);
        record(admin, "PLAN_EXTEND", "user", userId, "+" + days + " days to " + s.getCurrentPeriodEnd() + ": " + why);
    }

    /** Disables or re-enables sign-in; disabling also stops the user's live streams. */
    @Transactional
    public void setEnabled(String admin, Long userId, boolean enabled, String reason) {
        String why = requireReason(reason);
        User u = users.lockById(userId).orElseThrow(() -> new NotFoundException("User not found."));
        if ("ROLE_ADMIN".equals(u.getRole()) && !enabled) {
            throw new ConflictException("Admins can't be disabled here.");
        }
        u.setEnabled(enabled);
        users.save(u);
        if (!enabled) {
            for (StreamJob j : jobs.findAllByUserEmailAndIsLiveTrue(u.getEmail())) {
                streamService.requestStop(j, "Account disabled");
            }
        }
        record(admin, enabled ? "USER_ENABLE" : "USER_DISABLE", "user", userId, why);
    }

    /**
     * Records a refund made in the payment provider's dashboard (the provider API isn't called from
     * here). Optionally ends the plan now.
     */
    @Transactional
    public void recordRefund(String admin, String orderId, long amount, boolean endPlan, String reason) {
        String why = requireReason(reason);
        PaymentAudit p = payments.findForUpdateByTransactionId(orderId).orElseThrow(() -> new NotFoundException("Payment not found."));
        if (!"COMPLETED".equals(p.getStatus()) && !"SUCCESS".equals(p.getStatus())) {
            throw new ConflictException("Only completed payments can be refunded (this one is " + p.getStatus() + ").");
        }
        long paid = p.getAmount() == null ? 0 : p.getAmount();
        if (amount <= 0 || amount > paid) {
            throw new IllegalArgumentException("Refund between 1 and " + paid + " (paise/cents).");
        }
        p.setStatus(amount == paid ? "REFUNDED" : "PARTIALLY_REFUNDED");
        payments.save(p);
        User u = p.getUser() != null ? p.getUser() : users.findByEmail(p.getMerchantUserId()).orElse(null);
        record(admin, "PAYMENT_REFUND", "user", u == null ? orderId : u.getId(), orderId + " refunded " + amount
                + (endPlan ? ", plan ended" : "") + ": " + why);
        if (endPlan && u != null) {
            setPlanNoAudit(u.getId());
        }
    }

    private void setPlanNoAudit(Long userId) {
        User u = users.lockById(userId).orElseThrow();
        u.setPlanType(PlanType.FREE);
        u.setPlanExpiration(null);
        users.save(u);
        subscriptions.lockByUserId(userId).ifPresent(s -> {
            s.setStatus(Subscription.Status.CANCELED);
            subscriptions.save(s);
        });
    }

    public Page<AdminAction> auditLog(int page) {
        return audit.findAllByOrderByCreatedAtDesc(PageRequest.of(Math.max(0, page), 50));
    }

    // ------------------------------------------------------------------ helpers

    private void record(String admin, String action, String type, Object id, String detail) {
        AdminAction a = new AdminAction();
        a.setAdminEmail(admin);
        a.setAction(action);
        a.setTargetType(type);
        a.setTargetId(String.valueOf(id));
        a.setDetail(detail);
        a.setCreatedAt(now());
        audit.save(a);
        log.info("Admin {} {} {} {}: {}", admin, action, type, id, detail);
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.trim().length() < 3) {
            throw new IllegalArgumentException("Say why (it goes in the audit log).");
        }
        String r = reason.trim();
        return r.length() > 500 ? r.substring(0, 500) : r;
    }

    private static ZonedDateTime now() {
        return ZonedDateTime.now(ZoneId.of("UTC"));
    }
}
