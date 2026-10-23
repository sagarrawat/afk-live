package com.afklive.streamer.service;

import com.afklive.streamer.dto.StreamDtos;
import com.afklive.streamer.exception.ConflictException;
import com.afklive.streamer.exception.NotFoundException;
import com.afklive.streamer.model.AdminAction;
import com.afklive.streamer.model.Invoice;
import com.afklive.streamer.model.LiveStream;
import com.afklive.streamer.model.PaymentAudit;
import com.afklive.streamer.model.PlanConfig;
import com.afklive.streamer.model.SupportTicket;
import com.afklive.streamer.model.PlanType;
import com.afklive.streamer.model.StreamJob;
import com.afklive.streamer.model.Subscription;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.AdminActionRepository;
import com.afklive.streamer.repository.InvoiceRepository;
import com.afklive.streamer.repository.LiveStreamRepository;
import com.afklive.streamer.repository.PaymentAuditRepository;
import com.afklive.streamer.repository.PlanConfigRepository;
import com.afklive.streamer.repository.SupportTicketRepository;
import com.afklive.streamer.repository.QuotaAuditRepository;
import com.afklive.streamer.repository.WorkerStatusRepository;
import com.afklive.streamer.model.WorkerStatus;
import org.springframework.beans.factory.annotation.Value;
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
    private final WorkerStatusRepository workerStatus;
    private final QuotaAuditRepository quota;
    private final int dailyQuota;
    private final Double monthlyServerCost;
    private final String serverCostCurrency;
    private final SupportTicketRepository tickets;
    private final FileStorageService storage;
    private final AppConfigService appConfig;
    private final PlanConfigRepository planConfigs;
    private final com.afklive.streamer.repository.EmailLogRepository emailLog;

    public AdminService(StreamJobRepository jobs, LiveStreamRepository streams, UserRepository users,
                        SubscriptionRepository subscriptions, PaymentAuditRepository payments, InvoiceRepository invoices,
                        UsageRepository usage, PlanService plans, StreamService streamService, AdminActionRepository audit,
                        NotificationService notifications, WorkerStatusRepository workerStatus, QuotaAuditRepository quota,
                        SupportTicketRepository tickets, FileStorageService storage, AppConfigService appConfig,
                        PlanConfigRepository planConfigs, com.afklive.streamer.repository.EmailLogRepository emailLog,
                        @Value("${app.youtube.daily-quota:10000}") int dailyQuota,
                        @Value("${app.admin.monthly-server-cost:}") String monthlyServerCost,
                        @Value("${app.admin.server-cost-currency:INR}") String serverCostCurrency) {
        this.tickets = tickets;
        this.storage = storage;
        this.appConfig = appConfig;
        this.planConfigs = planConfigs;
        this.emailLog = emailLog;
        this.workerStatus = workerStatus;
        this.quota = quota;
        this.dailyQuota = dailyQuota;
        this.monthlyServerCost = parseCost(monthlyServerCost);
        this.serverCostCurrency = serverCostCurrency == null || serverCostCurrency.isBlank() ? "INR" : serverCostCurrency.trim().toUpperCase(Locale.ROOT);
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

    /**
     * A worker as admins see it (AD-1, AD-2): streams it runs, when it last reported, and the machine's
     * CPU and memory from its own report (null for a worker that hasn't sent one).
     */
    public record WorkerRow(String worker, int streams, long lastHeartbeatAgeSeconds, boolean healthy, Integer capacity,
                            Double cpuPercent, Long memoryUsedBytes, Long memoryTotalBytes) {
    }

    /** Workers that reported in the last hour, plus any worker named on a live stream. */
    public List<WorkerRow> workers() {
        ZonedDateTime now = now();
        Map<String, List<StreamJob>> byWorker = new HashMap<>();
        for (StreamJob j : jobs.findAllByIsLiveTrue()) {
            byWorker.computeIfAbsent(j.getWorkerId() == null ? "(unassigned)" : j.getWorkerId(), k -> new ArrayList<>()).add(j);
        }
        Map<String, WorkerStatus> reports = new HashMap<>();
        for (WorkerStatus w : workerStatus.findAllByHeartbeatAtAfterOrderByWorkerIdAsc(now.minusHours(1))) {
            reports.put(w.getWorkerId(), w);
            byWorker.computeIfAbsent(w.getWorkerId(), k -> new ArrayList<>());
        }
        long timeout = StreamService.HEARTBEAT_TIMEOUT.getSeconds();
        List<WorkerRow> out = new ArrayList<>();
        byWorker.forEach((w, list) -> {
            long jobAge = list.stream().filter(j -> j.getHeartbeatAt() != null)
                    .mapToLong(j -> Duration.between(j.getHeartbeatAt(), now).getSeconds()).min().orElse(-1);
            WorkerStatus r = reports.get(w);
            long reportAge = r == null ? -1 : Math.max(0, Duration.between(r.getHeartbeatAt(), now).getSeconds());
            long newest = jobAge < 0 ? reportAge : reportAge < 0 ? jobAge : Math.min(jobAge, reportAge);
            // Streams' heartbeats are the truth while it runs any; an idle worker counts by its own report.
            boolean healthy = list.isEmpty() ? reportAge >= 0 && reportAge < timeout : jobAge >= 0 && jobAge < timeout;
            out.add(new WorkerRow(w, list.size(), newest, healthy, r == null ? null : r.getCapacity(),
                    r == null ? null : r.getCpuPercent(), r == null ? null : r.getMemoryUsedBytes(), r == null ? null : r.getMemoryTotalBytes()));
        });
        out.sort(Comparator.comparing(WorkerRow::worker));
        return out;
    }

    // ------------------------------------------------------------------ AD-2: cost per stream hour

    /**
     * Server cost per stream hour: the monthly server bill (APP_MONTHLY_SERVER_COST; unset means unknown)
     * over the stream hours of the last 30 days, all accounts together. Null when either is missing.
     */
    public record CostView(Double monthlyServerCost, String currency, double streamHours30d, Double costPerStreamHour) {
    }

    public CostView cost() {
        ZonedDateTime now = now();
        double hours = Math.round(jobs.streamSecondsBetween(now.minusDays(30), now) / 36.0) / 100.0;
        Double perHour = monthlyServerCost == null || hours <= 0 ? null : Math.round(monthlyServerCost / hours * 100.0) / 100.0;
        return new CostView(monthlyServerCost, serverCostCurrency, hours, perHour);
    }

    static Double parseCost(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            double v = Double.parseDouble(raw.trim());
            return v > 0 && Double.isFinite(v) ? v : null;
        } catch (NumberFormatException e) {
            log.warn("APP_MONTHLY_SERVER_COST isn't a number; cost per stream hour is off");
            return null;
        }
    }

    // ------------------------------------------------------------------ YT-4: YouTube API quota

    public record QuotaApi(String api, long units) {
    }

    /** Units spent today against the project's daily quota; YouTube resets it at midnight Pacific time. */
    public record QuotaView(int usedToday, int dailyLimit, int percent, ZonedDateTime resetsAt, List<QuotaApi> breakdown) {
    }

    public QuotaView quota() {
        ZonedDateTime dayStart = ZonedDateTime.now(ZoneId.of("America/Los_Angeles")).truncatedTo(java.time.temporal.ChronoUnit.DAYS);
        // quota_audit stores the server's local time (LocalDateTime.now()).
        java.time.LocalDateTime since = dayStart.withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
        Integer used = quota.sumCostSince(since);
        int u = used == null ? 0 : used;
        List<QuotaApi> breakdown = new ArrayList<>();
        for (Object[] row : quota.getUsageBreakdown(since)) {
            breakdown.add(new QuotaApi(String.valueOf(row[0]), row[1] == null ? 0 : ((Number) row[1]).longValue()));
        }
        breakdown.sort(Comparator.comparingLong(QuotaApi::units).reversed());
        int pct = dailyQuota <= 0 ? 0 : (int) Math.min(999, Math.round(u * 100.0 / dailyQuota));
        return new QuotaView(u, dailyQuota, pct, dayStart.plusDays(1).withZoneSameInstant(ZoneId.of("UTC")), breakdown);
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

    /** {@code lastJobId}: the live run, or else the most recent one (AD-1 / AD-3 run page); null if never run. */
    public record StreamRow(Long id, String name, boolean live, Long jobId, String worker, Long lastJobId) {
    }

    /** OPS-1: an email we tried to send this user. */
    public record EmailRow(ZonedDateTime at, String kind, boolean ok, String error) {
    }

    public record SubscriptionView(String plan, String cycle, String status, String currency, ZonedDateTime periodStart,
                                   ZonedDateTime periodEnd, boolean cancelAtPeriodEnd, String pendingPlan) {
    }

    public record UserDetail(Long id, String email, String name, String plan, boolean enabled, String role,
                             java.time.LocalDateTime planExpiration, SubscriptionView subscription, long storageBytes,
                             long storageLimit, double hoursThisMonth, int hoursIncluded, List<StreamRow> streams,
                             List<PaymentRow> payments, List<InvoiceRow> invoices, List<AdminAction> history,
                             boolean awaitingVerification, List<EmailRow> emails) {
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
            Long last = live.map(StreamJob::getId).orElseGet(() -> jobs.findFirstByStreamIdOrderByIdDesc(s.getId()).map(StreamJob::getId).orElse(null));
            rows.add(new StreamRow(s.getId(), s.getName(), live.isPresent(), live.map(StreamJob::getId).orElse(null),
                    live.map(StreamJob::getWorkerId).orElse(null), last));
        }
        List<PaymentRow> pay = payments.findTop50ByMerchantUserIdOrderByCreatedAtDesc(u.getEmail()).stream()
                .map(p -> new PaymentRow(p.getTransactionId(), p.getAmount() == null ? 0 : p.getAmount(), p.getCurrency(), p.getStatus(),
                        p.getKind(), p.getPlanId(), p.getBillingCycle(), p.getCreatedAt())).toList();
        List<InvoiceRow> inv = invoices.findAllByUserIdOrderByIssuedAtDesc(u.getId()).stream()
                .map(i -> new InvoiceRow(i.getId(), i.getNumber(), i.getTotal(), i.getCurrency(), i.getIssuedAt())).toList();
        double hours = Math.round(usage.secondsIn(u.getId(), BillingService.month(now())) / 36.0) / 100.0;
        return new UserDetail(u.getId(), u.getEmail(), u.getFullName(), u.getPlanType().name(), u.isEnabled(), u.getRole(),
                u.getPlanExpiration(), sub, users.usedStorage(u.getId()), cfg.getMaxStorageBytes(), hours, cfg.getMonthlyHours(),
                rows, pay, inv, audit.findTop50ByTargetTypeAndTargetIdOrderByCreatedAtDesc("user", String.valueOf(u.getId())),
                !u.isEnabled() && u.getVerificationToken() != null,
                emailLog.findTop50ByUserIdOrderByCreatedAtDesc(u.getId()).stream()
                        .map(e -> new EmailRow(e.getCreatedAt(), e.getKind(), e.isOk(), e.getError())).toList());
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

    // ------------------------------------------------------------------ overview and platform settings

    public record Revenue(String currency, long allTime, long last30Days) {
    }

    /**
     * The platform at a glance. Revenue counts completed payments only (refunded ones drop out), in the
     * currency's minor unit. {@code freeStreamLimit}: free accounts can't go live once this many streams
     * (all plans together) are live.
     */
    public record Overview(long users, long liveStreams, long storageBytes, long openTickets, int freeStreamLimit,
                           List<Revenue> revenue) {
    }

    public Overview overview() {
        Map<String, long[]> byCurrency = new java.util.TreeMap<>();
        // payment_audit keeps the server's local time (LocalDateTime.now()).
        java.time.LocalDateTime monthAgo = java.time.LocalDateTime.now().minusDays(30);
        for (Object[] row : payments.sumCompletedByCurrencySince(java.time.LocalDateTime.of(1970, 1, 1, 0, 0))) {
            byCurrency.computeIfAbsent(currency(row[0]), k -> new long[2])[0] += ((Number) row[1]).longValue();
        }
        for (Object[] row : payments.sumCompletedByCurrencySince(monthAgo)) {
            byCurrency.computeIfAbsent(currency(row[0]), k -> new long[2])[1] += ((Number) row[1]).longValue();
        }
        List<Revenue> revenue = new ArrayList<>();
        byCurrency.forEach((c, v) -> revenue.add(new Revenue(c, v[0], v[1])));
        Long storageBytes = users.sumUsedStorageBytes();
        return new Overview(users.count(), jobs.countByIsLiveTrue(), storageBytes == null ? 0 : storageBytes,
                tickets.countByStatus(TICKET_OPEN), appConfig.getGlobalStreamLimit(), revenue);
    }

    private static String currency(Object raw) {
        return raw == null || String.valueOf(raw).isBlank() ? "INR" : String.valueOf(raw).toUpperCase(Locale.ROOT);
    }

    public static final int MAX_FREE_STREAM_LIMIT = 10_000;

    /** Sets how many live streams (all plans) the platform runs before free accounts can't go live. */
    @Transactional
    public void setFreeStreamLimit(String admin, Integer limit, String reason) {
        String why = requireReason(reason);
        if (limit == null || limit < 0 || limit > MAX_FREE_STREAM_LIMIT) {
            throw new IllegalArgumentException("Set a limit between 0 and " + MAX_FREE_STREAM_LIMIT + ".");
        }
        int before = appConfig.getGlobalStreamLimit();
        appConfig.setGlobalStreamLimit(limit);
        record(admin, "SETTING_FREE_LIMIT", "setting", "global_max_streams", before + " → " + limit + ": " + why);
    }

    // ------------------------------------------------------------------ support tickets

    public static final String TICKET_OPEN = "OPEN";
    public static final List<String> TICKET_STATUSES = List.of(TICKET_OPEN, "RESOLVED", "CLOSED");

    public record TicketRow(Long id, String userEmail, Long userId, String category, String message, String status,
                            java.time.LocalDateTime createdAt, String attachmentName, boolean hasAttachment) {
        static TicketRow of(SupportTicket t) {
            boolean has = t.getAttachmentKey() != null;
            String name = !has ? null : t.getAttachmentName() == null || t.getAttachmentName().isBlank() ? "attachment" : t.getAttachmentName();
            return new TicketRow(t.getId(), t.getUserEmail(), t.getUser() == null ? null : t.getUser().getId(), t.getCategory(),
                    t.getMessage(), t.getStatus(), t.getCreatedAt(), name, has);
        }
    }

    /** Newest first; {@code status} filters (blank: all). */
    public Page<TicketRow> tickets(String status, int page) {
        PageRequest p = PageRequest.of(Math.max(0, page), 25);
        Page<SupportTicket> found = status == null || status.isBlank() ? tickets.findAllByOrderByCreatedAtDesc(p)
                : tickets.findAllByStatusOrderByCreatedAtDesc(ticketStatus(status), p);
        return found.map(TicketRow::of);
    }

    public TicketRow ticket(Long id) {
        return TicketRow.of(tickets.findById(id).orElseThrow(() -> new NotFoundException("Ticket not found.")));
    }

    @Transactional
    public TicketRow setTicketStatus(String admin, Long id, String status, String reason) {
        String why = requireReason(reason);
        String next = ticketStatus(status);
        SupportTicket t = tickets.findById(id).orElseThrow(() -> new NotFoundException("Ticket not found."));
        String before = t.getStatus();
        if (next.equals(before)) {
            throw new ConflictException("The ticket is already " + next.toLowerCase(Locale.ROOT) + ".");
        }
        t.setStatus(next);
        tickets.save(t);
        record(admin, "TICKET_STATUS", "ticket", id, before + " → " + next + ": " + why);
        return TicketRow.of(t);
    }

    private static String ticketStatus(String raw) {
        String s = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (!TICKET_STATUSES.contains(s)) {
            throw new IllegalArgumentException("Status is one of " + String.join(", ", TICKET_STATUSES) + ".");
        }
        return s;
    }

    public record Attachment(String name, String contentType, org.springframework.core.io.Resource file) {
    }

    /** A ticket's attachment. A content type is set only for a few safe kinds; anything else is plain bytes. */
    public Attachment attachment(Long id) {
        SupportTicket t = tickets.findById(id).orElseThrow(() -> new NotFoundException("Ticket not found."));
        if (t.getAttachmentKey() == null) {
            throw new NotFoundException("This ticket has no attachment.");
        }
        org.springframework.core.io.Resource file;
        try {
            file = storage.loadFileAsResource(t.getAttachmentKey());
        } catch (RuntimeException e) {
            log.warn("Attachment of ticket {} can't be read: {}", id, e.getMessage());
            throw new NotFoundException("The attachment file is missing.");
        }
        String name = t.getAttachmentName() == null || t.getAttachmentName().isBlank() ? "attachment" : t.getAttachmentName();
        return new Attachment(name, attachmentType(name), file);
    }

    static String attachmentType(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".mp4")) return "video/mp4";
        if (n.endsWith(".pdf")) return "application/pdf";
        return "application/octet-stream";
    }

    // ------------------------------------------------------------------ plan configuration (plan_configs)

    /** A plan's live settings; prices are GST-inclusive, in paise (INR) and cents (USD). */
    public record PlanView(String plan, String displayName, long maxStorageBytes, int maxActiveStreams, int maxDestinations,
                           int maxChannels, int maxResolution, int maxFps, int monthlyHours, boolean watermark,
                           int maxScheduledPosts, long priceInrMonth, long priceInrYear, long priceUsdMonth, long priceUsdYear) {
        static PlanView of(PlanConfig c) {
            return new PlanView(c.getPlanType().name(), c.getDisplayName(), c.getMaxStorageBytes(), c.getMaxActiveStreams(),
                    c.getMaxDestinations(), c.getMaxChannels(), c.getMaxResolution(), c.getMaxFps(), c.getMonthlyHours(),
                    c.isWatermark(), c.getMaxScheduledPosts(), c.getPriceInrMonth(), c.getPriceInrYear(), c.getPriceUsdMonth(),
                    c.getPriceUsdYear());
        }
    }

    public List<PlanView> planConfigs() {
        List<PlanView> out = new ArrayList<>();
        for (PlanType t : PlanType.values()) {
            planConfigs.findById(t).ifPresent(c -> out.add(PlanView.of(c)));
        }
        return out;
    }

    /** Everything an admin can change on a plan. {@code maxChannels} / {@code maxScheduledPosts} null: keep as is. */
    public record PlanEdit(String displayName, Long maxStorageBytes, Integer maxActiveStreams, Integer maxDestinations,
                           Integer maxChannels, Integer maxResolution, Integer maxFps, Integer monthlyHours, Boolean watermark,
                           Integer maxScheduledPosts, Long priceInrMonth, Long priceInrYear, Long priceUsdMonth,
                           Long priceUsdYear) {
    }

    static final long MAX_STORAGE_BYTES = 10L * 1024 * 1024 * 1024 * 1024; // 10 TB
    static final long MAX_PRICE_MINOR = 100_000_000L; // ₹10 lakh or $1M a period: anything above is a typo
    static final java.util.Set<Integer> RESOLUTIONS = java.util.Set.of(720, 1080, 1440, 2160);
    static final java.util.Set<Integer> FPS = java.util.Set.of(30, 60);

    /**
     * Changes a plan's limits and prices in {@code plan_configs}. They apply at once: limits to everyone
     * on the plan (streams already live keep running), prices to every checkout and renewal from now on.
     */
    @Transactional
    public PlanView updatePlanConfig(String admin, String planId, PlanEdit e, String reason) {
        String why = requireReason(reason);
        PlanType type;
        try {
            type = PlanType.valueOf(planId.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException ex) {
            throw new NotFoundException("Unknown plan.");
        }
        if (e == null) {
            throw new IllegalArgumentException("Send the plan's settings.");
        }
        String name = e.displayName() == null ? "" : e.displayName().trim();
        if (name.isEmpty() || name.length() > 40) {
            throw new IllegalArgumentException("Give the plan a name of up to 40 characters.");
        }
        long storageBytes = required(e.maxStorageBytes(), "storage");
        if (storageBytes <= 0 || storageBytes > MAX_STORAGE_BYTES) {
            throw new IllegalArgumentException("Storage is more than 0 and at most 10 TB.");
        }
        int streamsMax = range(e.maxActiveStreams(), "streams at a time", 1, 1000);
        int destinations = range(e.maxDestinations(), "destinations per stream", 1, 20);
        int resolution = required(e.maxResolution(), "top quality");
        if (!RESOLUTIONS.contains(resolution)) {
            throw new IllegalArgumentException("Top quality is 720, 1080, 1440 or 2160.");
        }
        int fps = required(e.maxFps(), "frame rate");
        if (!FPS.contains(fps)) {
            throw new IllegalArgumentException("Frame rate is 30 or 60.");
        }
        int hours = range(e.monthlyHours(), "monthly hours", 0, 744);
        boolean watermark = required(e.watermark(), "watermark");
        if (e.maxChannels() != null && (e.maxChannels() < 0 || e.maxChannels() > 1000)) {
            throw new IllegalArgumentException("Channels is between 0 and 1000.");
        }
        if (e.maxScheduledPosts() != null && e.maxScheduledPosts() < 0) {
            throw new IllegalArgumentException("Scheduled posts can't be negative.");
        }
        long inrMonth = price(e.priceInrMonth(), "INR monthly price");
        long inrYear = price(e.priceInrYear(), "INR yearly price");
        long usdMonth = price(e.priceUsdMonth(), "USD monthly price");
        long usdYear = price(e.priceUsdYear(), "USD yearly price");
        if (type == PlanType.FREE) {
            if (inrMonth != 0 || inrYear != 0 || usdMonth != 0 || usdYear != 0) {
                throw new IllegalArgumentException("The Free plan's prices stay 0.");
            }
        } else {
            if (inrMonth == 0 || inrYear == 0 || usdMonth == 0 || usdYear == 0) {
                throw new IllegalArgumentException("A paid plan needs every price above 0.");
            }
            if (inrYear > inrMonth * 12 || usdYear > usdMonth * 12) {
                throw new IllegalArgumentException("A yearly price can't be more than 12 monthly ones.");
            }
        }

        PlanConfig c = planConfigs.findById(type).orElseThrow(() -> new NotFoundException("Unknown plan."));
        List<String> changes = new ArrayList<>();
        diff(changes, "name", c.getDisplayName(), name);
        diff(changes, "storage", c.getMaxStorageBytes(), storageBytes);
        diff(changes, "streams", c.getMaxActiveStreams(), streamsMax);
        diff(changes, "destinations", c.getMaxDestinations(), destinations);
        diff(changes, "quality", c.getMaxResolution(), resolution);
        diff(changes, "fps", c.getMaxFps(), fps);
        diff(changes, "monthlyHours", c.getMonthlyHours(), hours);
        diff(changes, "watermark", c.isWatermark(), watermark);
        if (e.maxChannels() != null) {
            diff(changes, "channels", c.getMaxChannels(), e.maxChannels());
        }
        if (e.maxScheduledPosts() != null) {
            diff(changes, "scheduledPosts", c.getMaxScheduledPosts(), e.maxScheduledPosts());
        }
        diff(changes, "INR/month", c.getPriceInrMonth(), inrMonth);
        diff(changes, "INR/year", c.getPriceInrYear(), inrYear);
        diff(changes, "USD/month", c.getPriceUsdMonth(), usdMonth);
        diff(changes, "USD/year", c.getPriceUsdYear(), usdYear);
        if (changes.isEmpty()) {
            throw new IllegalArgumentException("Nothing changed.");
        }
        c.setDisplayName(name);
        c.setMaxStorageBytes(storageBytes);
        c.setMaxActiveStreams(streamsMax);
        c.setMaxDestinations(destinations);
        c.setMaxResolution(resolution);
        c.setMaxFps(fps);
        c.setMonthlyHours(hours);
        c.setWatermark(watermark);
        if (e.maxChannels() != null) {
            c.setMaxChannels(e.maxChannels());
        }
        if (e.maxScheduledPosts() != null) {
            c.setMaxScheduledPosts(e.maxScheduledPosts());
        }
        c.setPriceInrMonth(inrMonth);
        c.setPriceInrYear(inrYear);
        c.setPriceUsdMonth(usdMonth);
        c.setPriceUsdYear(usdYear);
        // The old display strings, kept in step for anything that still reads them.
        c.setPrice(inrMonth == 0 ? "Free" : BillingService.priceLabel(inrMonth, "INR"));
        c.setBillingCycle(inrMonth == 0 ? "Free" : "Monthly");
        PlanConfig saved = plans.updatePlan(c); // evicts the plan caches: checkout, renewals and limits see it at once
        record(admin, "PLAN_CONFIG", "plan", type.name(), String.join(", ", changes) + ": " + why);
        return PlanView.of(saved);
    }

    private static <T> T required(T v, String what) {
        if (v == null) {
            throw new IllegalArgumentException("Fill in " + what + ".");
        }
        return v;
    }

    private static int range(Integer v, String what, int min, int max) {
        int x = required(v, what);
        if (x < min || x > max) {
            throw new IllegalArgumentException(capitalize(what) + " is between " + min + " and " + max + ".");
        }
        return x;
    }

    private static long price(Long v, String what) {
        long x = required(v, what);
        if (x < 0 || x > MAX_PRICE_MINOR) {
            throw new IllegalArgumentException(capitalize(what) + " can't be negative or that large.");
        }
        return x;
    }

    private static String capitalize(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static void diff(List<String> out, String field, Object before, Object after) {
        if (!java.util.Objects.equals(before, after)) {
            out.add(field + " " + before + " → " + after);
        }
    }

    // ------------------------------------------------------------------ helpers

    void record(String admin, String action, String type, Object id, String detail) {
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

    static String requireReason(String reason) {
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
