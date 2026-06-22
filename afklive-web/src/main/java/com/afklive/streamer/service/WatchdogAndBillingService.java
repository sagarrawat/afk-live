package com.afklive.streamer.service;

import com.afklive.streamer.model.StreamJob;
import com.afklive.streamer.model.StreamJobQueue;
import com.afklive.streamer.model.User;
import com.afklive.streamer.model.TransactionLedger;
import com.afklive.streamer.repository.StreamJobQueueRepository;
import com.afklive.streamer.repository.StreamJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.Duration;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class WatchdogAndBillingService {

    private final StreamJobRepository streamJobRepo;
    private final StreamJobQueueRepository streamJobQueueRepo;
    private final UserService userService;
    private final StreamService streamService;
    private final AppConfigService appConfigService;
    private final LedgerService ledgerService;
    private final NotificationService notificationService;
    
    @Autowired
    private ApplicationContext applicationContext;

    /** Heartbeat timeout — if no heartbeat received in this many seconds, the worker is considered dead. */
    private static final long HEARTBEAT_TIMEOUT_SECONDS = 60;

    @Scheduled(fixedRate = 60000)
    // Removed @Transactional to avoid locking the entire database for the entire duration of processing all streams.
    public void executeWatchdogAndBilling() {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("UTC"));
        List<StreamJob> liveJobs = streamJobRepo.findAllByStatusLive();

        if (liveJobs.isEmpty()) {
            return; // Nothing to do — avoids unnecessary DB calls
        }

        log.debug("Watchdog/billing tick: processing {} live stream jobs.", liveJobs.size());

        WatchdogAndBillingService selfProxy = applicationContext.getBean(WatchdogAndBillingService.class);

        for (StreamJob job : liveJobs) {
            try {
                // Process each job in a separate transaction
                selfProxy.processSingleJobInTransaction(job.getId(), now);
            } catch (Exception e) {
                // Catch per-job so a failure on one stream doesn't break billing for all others
                log.error("Error processing watchdog/billing for job {}", job.getId(), e);
            }
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processSingleJobInTransaction(Long jobId, ZonedDateTime now) {
        StreamJob job = streamJobRepo.findByIdForUpdate(jobId).orElse(null);
        if (job == null || job.getStatus() != StreamJob.StreamStatus.LIVE) return;

        // ── WATCHDOG: Check for stale worker ──────────────────────────────────
        if (isWorkerStale(job, now)) {
            log.warn("Watchdog detected stale stream. jobId={}, workerId={}, lastHeartbeat={}",
                    job.getId(), job.getWorkerId(), job.getLastHeartbeat());
            handleStaleJob(job);
            return; // Do not bill for a crashed minute
        }

        // ── BILLING: Only for pay-as-you-go (FREE plan) users ─────────────────
        User user = userService.getOrCreateUser(job.getUser().getEmail());
        if (user.getPlanType() != com.afklive.streamer.model.PlanType.FREE) {
            return; // Paid plans have flat billing handled elsewhere
        }

        applyIncrementalBilling(job, user, now);
    }

    private boolean isWorkerStale(StreamJob job, ZonedDateTime now) {
        if (job.getLastHeartbeat() == null) {
            return false; // Job just queued, worker hasn't connected yet
        }
        return ChronoUnit.SECONDS.between(job.getLastHeartbeat(), now) > HEARTBEAT_TIMEOUT_SECONDS;
    }

    private void applyIncrementalBilling(StreamJob job, User user, ZonedDateTime now) {
        ZonedDateTime lastCalc = (job.getLastBillingTime() != null) ? job.getLastBillingTime() : job.getStartTime();
        if (lastCalc == null) {
            log.warn("Job {} has no start time or billing time set; skipping billing tick.", job.getId());
            return;
        }

        long durationSeconds = ChronoUnit.SECONDS.between(lastCalc, now);
        if (durationSeconds <= 0) {
            return;
        }

        BigDecimal hours = new BigDecimal(durationSeconds).divide(new BigDecimal("3600"), 6, RoundingMode.DOWN);
        BigDecimal incrementalCost = hours.multiply(job.getLockedHourlyRate()).setScale(2, RoundingMode.DOWN);

        // ── Platform-wide daily budget cap check ──────────────────────────────
        String currency = user.getCurrency();
        BigDecimal dailySpend = appConfigService.getDailySpend(currency);
        BigDecimal dailyCap   = appConfigService.getDailyBudgetCap(currency);

        if (dailySpend.add(incrementalCost).compareTo(dailyCap) > 0) {
            log.error("CRITICAL: Platform daily budget cap of {} {} exceeded (current spend: {}). Stopping jobId={}.",
                    dailyCap, currency, dailySpend, job.getId());
            streamService.stopStream(job.getId(), user.getEmail());
            return;
        }

        // ── Apply cost ────────────────────────────────────────────────────────
        job.setAccumulatedCost(job.getAccumulatedCost().add(incrementalCost));
        job.setLastBillingTime(now);
        userService.addUnpaidBalance(user.getEmail(), incrementalCost);
        appConfigService.addToDailySpend(currency, incrementalCost);
        streamJobRepo.save(job);

        log.debug("Billed {} {} to user {} for jobId={} (lockedRate={}/hr, {}s elapsed).",
                incrementalCost, currency, user.getEmail(), job.getId(), job.getLockedHourlyRate(), durationSeconds);

        // ── Per-user credit limit check ───────────────────────────────────────
        if (!userService.checkCreditLimit(user.getEmail())) {
            log.warn("User {} exceeded credit limit. Stopping jobId={}.", user.getEmail(), job.getId());
            streamService.stopStream(job.getId(), user.getEmail());
            notificationService.createNotification(user.getId(), "BILLING_ALERT", "Stream Stopped", "Your stream was stopped because your wallet balance has reached zero. Please add funds.");
        } else {
            // Check if available balance is low (unpaidBalance > -10.00 means available < 10.00)
            BigDecimal availableBalance = user.getCreditLimit().subtract(user.getUnpaidBalance());
            if (availableBalance.compareTo(new BigDecimal("10.00")) <= 0) {
                // Debounce to avoid spamming every minute? Let's just create it, but in reality we'd check if one was recently sent.
                // For MVP, we'll create it. To prevent spam, maybe check if they already have an unread BILLING_ALERT.
                notificationService.createNotification(user.getId(), "BILLING_WARNING", "Low Balance Warning", "Your wallet balance is running low (" + user.getCurrency() + " " + availableBalance + "). Please add funds to avoid stream interruption.");
            }
        }
    }

    private void handleStaleJob(StreamJob job) {
        if (job.getRetryCount() < job.getMaxRetries()) {
            int attempt = job.getRetryCount() + 1;
            log.info("Attempting recovery for jobId={} (attempt {}/{}).", job.getId(), attempt, job.getMaxRetries());

            job.setStatus(StreamJob.StreamStatus.RECOVERY_PENDING);
            job.setRetryCount(attempt);
            streamJobRepo.save(job);

            // Re-enqueue for a new worker to pick up via SKIP LOCKED
            StreamJobQueue recoveryEntry = new StreamJobQueue();
            recoveryEntry.setStreamJob(job);
            recoveryEntry.setStreamSpec(job.getStreamSpecJson());
            recoveryEntry.setStatus(StreamJobQueue.QueueStatus.PENDING);
            streamJobQueueRepo.save(recoveryEntry);

        } else {
            log.error("JobId={} exhausted all {} retries. Marking as FAILED.", job.getId(), job.getMaxRetries());
            job.setStatus(StreamJob.StreamStatus.FAILED);
            job.setFailureReason("Worker crashed and max retries (" + job.getMaxRetries() + ") exceeded.");
            streamJobRepo.save(job);
            
            EmailService emailService = applicationContext.getBean(EmailService.class);
            emailService.sendStreamStatusEmail(job.getUser().getEmail(), job.getTitle(), "FAILED", job.getFailureReason());
        }
    }

    @Scheduled(fixedRate = 60000)
    public void recordCompletedStreamsToLedger() {
        List<StreamJob> unrecorded = streamJobRepo.findUnrecordedFinishedJobs();
        if (unrecorded.isEmpty()) return;

        WatchdogAndBillingService selfProxy = applicationContext.getBean(WatchdogAndBillingService.class);
        for (StreamJob job : unrecorded) {
            try {
                selfProxy.recordSingleStreamToLedger(job.getId());
            } catch (Exception e) {
                log.error("Failed to record ledger for job {}", job.getId(), e);
            }
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSingleStreamToLedger(Long jobId) {
        StreamJob job = streamJobRepo.findByIdForUpdate(jobId).orElse(null);
        if (job == null || job.isLedgerRecorded() || job.getAccumulatedCost().compareTo(BigDecimal.ZERO) <= 0) return;

        String description = "Stream Usage";
        if (job.getTitle() != null && !job.getTitle().isEmpty()) {
            description += ": " + job.getTitle();
        }
        
        // Negative amount for usage deduction
        BigDecimal cost = job.getAccumulatedCost().negate();
        ledgerService.recordTransaction(job.getUser(), cost, TransactionLedger.TransactionType.STREAM_USAGE, "JOB-" + job.getId(), description);
        
        job.setLedgerRecorded(true);
        streamJobRepo.save(job);
        log.info("Recorded ledger for job {}", jobId);
    }
}
