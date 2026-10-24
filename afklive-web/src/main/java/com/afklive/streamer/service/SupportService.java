package com.afklive.streamer.service;

import com.afklive.streamer.exception.ConflictException;
import com.afklive.streamer.exception.NotFoundException;
import com.afklive.streamer.model.AdminAction;
import com.afklive.streamer.model.DestinationTest;
import com.afklive.streamer.model.EmailLog;
import com.afklive.streamer.model.LiveStream;
import com.afklive.streamer.model.MediaAsset;
import com.afklive.streamer.model.StreamJob;
import com.afklive.streamer.model.StreamJobDestination;
import com.afklive.streamer.repository.AdminActionRepository;
import com.afklive.streamer.repository.DestinationTestRepository;
import com.afklive.streamer.repository.EmailLogRepository;
import com.afklive.streamer.repository.LiveStreamRepository;
import com.afklive.streamer.repository.MediaAssetRepository;
import com.afklive.streamer.repository.StreamJobLogStore;
import com.afklive.streamer.repository.StreamJobRepository;
import com.afklive.streamer.repository.UserRepository;
import com.afklive.streamer.streaming.StreamSpec;
import com.afklive.streamer.streaming.StudioSpec;
import com.afklive.streamer.streaming.studio.Canvas;
import com.afklive.streamer.util.NetworkSafety;
import com.afklive.streamer.util.Redact;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Support tools for admins.
 * <ul>
 *   <li>OPS-1: is email working? A startup warning when it isn't set up, its state for the admin console,
 *   a test email (audited), and, while it's off, password sign-ups that would wait forever for a
 *   verification email are verified.</li>
 *   <li>AD-6: one run of a stream as support needs it: what goes in (source files), what comes out
 *   (output profile), where it goes (server, masked key, a check of the key's shape, the platform's
 *   last error), the last FFmpeg lines, and a test of a destination (audited). Keys never leave the
 *   server.</li>
 * </ul>
 */
@Service
@Slf4j
public class SupportService {

    /** Old FFmpeg logs and email records are removed after these. */
    static final Duration KEEP_JOB_LOGS = Duration.ofDays(14);
    static final Duration KEEP_EMAIL_LOG = Duration.ofDays(180);
    static final int MAX_MEDIA = 50;
    /** As DestinationTestService.EXPIRE_AFTER: a test nobody picked up fails after this long. */
    static final Duration TEST_EXPIRES_AFTER = Duration.ofMinutes(2);

    private final EmailService email;
    private final EmailLogRepository emailLog;
    private final StreamJobRepository jobs;
    private final LiveStreamRepository streams;
    private final MediaAssetRepository media;
    private final StreamJobLogStore jobLogs;
    private final DestinationTestRepository destinationTests;
    private final AdminService admin;
    private final AdminActionRepository audit;
    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final String role;
    private final String baseUrl;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "support-retention");
        t.setDaemon(true);
        return t;
    });

    public SupportService(EmailService email, EmailLogRepository emailLog, StreamJobRepository jobs, LiveStreamRepository streams,
                          MediaAssetRepository media, StreamJobLogStore jobLogs, DestinationTestRepository destinationTests,
                          AdminService admin, AdminActionRepository audit, JdbcTemplate jdbc, UserRepository users,
                          @Value("${app.streaming.role:all}") String role,
                          @Value("${app.base-url:http://localhost:8080}") String baseUrl) {
        this.email = email;
        this.emailLog = emailLog;
        this.jobs = jobs;
        this.streams = streams;
        this.media = media;
        this.jobLogs = jobLogs;
        this.destinationTests = destinationTests;
        this.admin = admin;
        this.audit = audit;
        this.jdbc = jdbc;
        this.users = users;
        this.role = role;
        this.baseUrl = com.afklive.streamer.endpoint.AuthController.publicBaseUrl(baseUrl);
    }

    // ------------------------------------------------------------------ startup and retention (web side)

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if ("worker".equalsIgnoreCase(role)) {
            return;
        }
        if (!email.isConfigured()) {
            log.warn("EMAIL IS OFF: ZEPTOMAIL_URL / ZEPTOMAIL_TOKEN are empty. Verification, password reset, alert and "
                    + "payment emails are not sent; password sign-ups are verified automatically and \"Forgot password\" "
                    + "points to support. Set both variables to turn email on.");
            int verified = verifyWaitingSignUps();
            if (verified > 0) {
                log.warn("Email is off: verified {} password sign-up(s) that were waiting for a verification email", verified);
            }
        }
        if (baseUrl == null || baseUrl.contains("localhost") || baseUrl.contains("127.0.0.1")) {
            log.warn("APP_BASE_URL is {}: links in emails (verification, password reset) point there. Set it to the public "
                    + "address, e.g. https://afklive.in", baseUrl);
        }
        timer.scheduleWithFixedDelay(() -> {
            try {
                purgeOld(now());
            } catch (Exception e) {
                log.warn("Support log cleanup failed: {}", e.getMessage());
            }
        }, 5, 60, TimeUnit.MINUTES);
    }

    @PreDestroy
    void stop() {
        timer.shutdownNow();
    }

    /**
     * OPS-1: while email is off, password sign-ups still waiting for a verification email get verified.
     * Accounts an admin disabled are left alone.
     */
    public int verifyWaitingSignUps() {
        return jdbc.update("UPDATE users SET enabled = true, verification_token = NULL WHERE enabled = false "
                + "AND verification_token IS NOT NULL AND password IS NOT NULL AND NOT EXISTS (SELECT 1 FROM admin_actions a "
                + "WHERE a.target_type = 'user' AND a.target_id = CAST(users.id AS VARCHAR) AND a.action = 'USER_DISABLE')");
    }

    public void purgeOld(ZonedDateTime now) {
        int logs = jobLogs.deleteOlderThan(now.minus(KEEP_JOB_LOGS));
        int mails = emailLog.deleteOlderThan(now.minus(KEEP_EMAIL_LOG));
        if (logs + mails > 0) {
            log.info("Removed {} old stream logs and {} old email records", logs, mails);
        }
    }

    // ------------------------------------------------------------------ OPS-1: email

    public record EmailRow(ZonedDateTime at, String kind, boolean ok, String error) {
        static EmailRow of(EmailLog e) {
            return new EmailRow(e.getCreatedAt(), e.getKind(), e.isOk(), e.getError());
        }
    }

    /** Whether email is set up, and how sending went in the last day. */
    public record EmailStatus(boolean configured, long sent24h, long failed24h, EmailRow lastSent, EmailRow lastFailure) {
    }

    public EmailStatus emailStatus() {
        ZonedDateTime since = now().minusHours(24);
        return new EmailStatus(email.isConfigured(), emailLog.countByOkAndCreatedAtAfter(true, since),
                emailLog.countByOkAndCreatedAtAfter(false, since),
                emailLog.findFirstByOkTrueOrderByCreatedAtDesc().map(EmailRow::of).orElse(null),
                emailLog.findFirstByOkFalseOrderByCreatedAtDesc().map(EmailRow::of).orElse(null));
    }

    /** Sends a test email to the admin's own address; audited with the reason. */
    @Transactional
    public EmailService.Result sendTestEmail(String adminEmail, String reason) {
        String why = requireReason(reason);
        EmailService.Result r = email.sendTestEmail(adminEmail);
        admin.record(adminEmail, "EMAIL_TEST", "setting", "email", (r.ok() ? "sent" : "failed: " + r.error()) + ": " + why);
        return r;
    }

    /** A stream of the user and its newest run, for the AD-6 run page. */
    public record StreamRun(Long streamId, String name, boolean live, Long lastJobId) {
    }

    /** What support needs about a user beyond Admin > Users: email state and sends, and each stream's last run. */
    public record UserSupport(boolean awaitingVerification, boolean hasPassword, List<StreamRun> streams, List<EmailRow> emails) {
    }

    @Transactional(readOnly = true)
    public UserSupport userSupport(Long userId) {
        com.afklive.streamer.model.User u = users.findById(userId).orElseThrow(() -> new NotFoundException("User not found."));
        List<StreamRun> runs = new ArrayList<>();
        for (LiveStream s : streams.findAllByUserEmailOrderByCreatedAtAsc(u.getEmail())) {
            Long last = jdbc.queryForObject("SELECT max(id) FROM stream_jobs WHERE stream_id = ?", Long.class, s.getId());
            boolean live = Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM stream_jobs WHERE stream_id = ? AND is_live)", Boolean.class, s.getId()));
            runs.add(new StreamRun(s.getId(), s.getName(), live, last));
        }
        return new UserSupport(!u.isEnabled() && u.getVerificationToken() != null, u.getPassword() != null && !u.getPassword().isBlank(),
                runs, emailLog.findTop50ByUserIdOrderByCreatedAtDesc(u.getId()).stream().map(EmailRow::of).toList());
    }

    /**
     * OPS-1: while email is off, someone locked out writes to support; after checking who they are, an
     * admin gives them this link (the same one "Forgot password" would email). Audited; the link works once.
     */
    @Transactional
    public String passwordResetLink(String adminEmail, Long userId, String reason) {
        String why = requireReason(reason);
        com.afklive.streamer.model.User u = users.findById(userId).orElseThrow(() -> new NotFoundException("User not found."));
        if (u.getPassword() == null || u.getPassword().isBlank()) {
            throw new IllegalStateException("This account signs in with Google, so it has no password to reset.");
        }
        u.setResetToken(java.util.UUID.randomUUID().toString());
        users.save(u);
        admin.record(adminEmail, "PASSWORD_RESET_LINK", "user", userId, why);
        return baseUrl + "/reset-password?token=" + u.getResetToken();
    }

    // ------------------------------------------------------------------ AD-6: one run

    public record SourceMedia(Long id, String title, Integer width, Integer height, Double fps, String shape, String codec,
                              Boolean hasAudio, String status, String problem) {
    }

    /** {@code kind}: PLAYLIST, PHONE or FILE. */
    public record Source(String kind, int count, List<SourceMedia> media, int musicCount) {
    }

    public record Output(int width, int height, int fps, String shape, Integer videoKbps, String audio, String keyframes) {
    }

    public record Destination(int position, String name, boolean off, String server, String keyMasked, int keyLength,
                              String checkLevel, String check, String status, int restarts, String lastError,
                              ZonedDateTime updatedAt, String reach, String reachReason) {
    }

    /**
     * RE-8: whether the platform is receiving, when the worker reports it ({@code reach}, {@code reach_reason}
     * on {@code stream_job_destinations}). Read by column name so this works before and after those columns
     * exist: without them every destination simply has no reach.
     */
    public record Reach(String reach, String reason) {
    }

    public record RunDetail(Long jobId, Long streamId, String streamName, Long userId, String userEmail, String plan,
                            boolean live, String status, String desiredState, String health, String worker,
                            Long heartbeatAgeSeconds, ZonedDateTime startTime, ZonedDateTime endTime, String endReason,
                            int restarts, Double bitrateKbps, Double fps, Double speed, Long droppedFrames, String lastError,
                            String nowPlaying, Boolean inputLive, String scene, Source source, Output output,
                            List<Destination> destinations, String log, ZonedDateTime logUpdatedAt,
                            List<RunRow> otherRuns, List<AdminAction> history) {
    }

    public record RunRow(Long jobId, ZonedDateTime startTime, ZonedDateTime endTime, String status, String endReason) {
    }

    @Transactional(readOnly = true)
    public RunDetail run(Long jobId) {
        StreamJob j = jobs.findById(jobId).orElseThrow(() -> new NotFoundException("Stream run not found."));
        StreamSpec spec = specOf(j);
        LiveStream stream = j.getStreamId() == null ? null : streams.findById(j.getStreamId()).orElse(null);
        int height = stream != null ? stream.getQualityHeight() : spec != null && spec.studio() != null ? spec.studio().height() : 1080;
        int fpsTarget = stream != null ? stream.getFps() : spec != null && spec.studio() != null ? spec.studio().fps() : 30;
        ZonedDateTime now = now();
        Long age = j.getHeartbeatAt() == null || !j.isLive() ? null : Duration.between(j.getHeartbeatAt(), now).getSeconds();
        String health = j.isLive() ? StreamHealth.of(j, height, fpsTarget).overall() : null;
        var log = jobLogs.find(jobId);
        List<RunRow> others = j.getStreamId() == null ? List.of() : jobs.findTop20ByStreamIdOrderByStartTimeDesc(j.getStreamId()).stream()
                .filter(o -> !o.getId().equals(jobId))
                .map(o -> new RunRow(o.getId(), o.getStartTime(), o.getEndTime(), o.getStatus(), o.getEndReason())).toList();
        return new RunDetail(j.getId(), j.getStreamId(), stream != null ? stream.getName() : j.getTitle(), j.getUser().getId(),
                j.getUser().getEmail(), j.getUser().getPlanType().name(), j.isLive(), j.getStatus(), j.getDesiredState(), health,
                j.getWorkerId(), age, j.getStartTime(), j.getEndTime(), j.getEndReason(), j.getRestarts(), j.getBitrateKbps(),
                j.getFps(), j.getSpeed(), j.getDroppedFrames(), Redact.streamKeys(j.getLastError()), j.getNowPlaying(), j.getInputLive(),
                j.getScene(), source(j, spec), output(spec), destinations(j, spec, reachOf(jobId)),
                log.map(StreamJobLogStore.Entry::lines).orElse(""), log.map(StreamJobLogStore.Entry::updatedAt).orElse(null),
                others, audit.findTop50ByTargetTypeAndTargetIdOrderByCreatedAtDesc("job", String.valueOf(jobId)));
    }

    private static StreamSpec specOf(StreamJob j) {
        if (j.getSpec() == null) {
            return null;
        }
        try {
            return StreamSpec.fromJson(j.getSpec());
        } catch (Exception e) {
            return null;
        }
    }

    Source source(StreamJob j, StreamSpec spec) {
        if (spec == null) {
            return new Source("UNKNOWN", 0, List.of(), 0);
        }
        StudioSpec studio = spec.studio();
        if (studio == null) {
            List<SourceMedia> files = new ArrayList<>();
            if (spec.videoKey() != null) {
                files.add(media.findFirstByUserEmailAndS3Key(j.getUser().getEmail(), spec.videoKey())
                        .map(SupportService::sourceMedia)
                        .orElse(new SourceMedia(null, spec.videoKey(), null, null, null, null, null, null, null, null)));
            }
            return new Source("FILE", files.size(), files, spec.musicKey() == null ? 0 : 1);
        }
        if ("PHONE".equalsIgnoreCase(studio.source())) {
            return new Source("PHONE", 0, List.of(), studio.music() == null ? 0 : studio.music().size());
        }
        List<StudioSpec.Media> videos = studio.videos() == null ? List.of() : studio.videos();
        Set<Long> ids = new LinkedHashSet<>();
        for (StudioSpec.Media m : videos) {
            try {
                ids.add(Long.parseLong(m.id()));
            } catch (RuntimeException ignored) {
                // not a library file id
            }
            if (ids.size() >= MAX_MEDIA) {
                break;
            }
        }
        Map<Long, MediaAsset> found = new HashMap<>();
        for (MediaAsset a : media.findAllByIdIn(ids)) {
            found.put(a.getId(), a);
        }
        List<SourceMedia> rows = new ArrayList<>();
        for (StudioSpec.Media m : videos) {
            if (rows.size() >= MAX_MEDIA) {
                break;
            }
            MediaAsset a = null;
            try {
                a = found.get(Long.parseLong(m.id()));
            } catch (RuntimeException ignored) {
                // not a library file id
            }
            rows.add(a != null ? sourceMedia(a) : new SourceMedia(null, m.title(), null, null, null, null, null, m.hasAudio(), "MISSING",
                    "This file is no longer in the library"));
        }
        return new Source("PLAYLIST", videos.size(), rows, studio.music() == null ? 0 : studio.music().size());
    }

    private static SourceMedia sourceMedia(MediaAsset a) {
        String problem = null;
        if (a.getWidth() == null || a.getHeight() == null) {
            problem = "Not probed yet: size unknown";
        } else if (a.getFps() != null && a.getFps() > 0 && a.getFps() < 20) {
            problem = "Low frame rate";
        } else if (Boolean.FALSE.equals(a.getHasAudio())) {
            problem = "No sound track";
        }
        return new SourceMedia(a.getId(), a.getTitle(), a.getWidth(), a.getHeight(), a.getFps(), shape(a.getWidth(), a.getHeight()),
                a.getVideoCodec(), a.getHasAudio(), a.getStatus() == null ? null : a.getStatus().name(), problem);
    }

    static String shape(Integer w, Integer h) {
        if (w == null || h == null || w <= 0 || h <= 0) {
            return null;
        }
        double r = (double) w / h;
        if (r > 1.1) {
            return "Landscape";
        }
        if (r < 0.9) {
            return "Vertical";
        }
        return "Square";
    }

    static Output output(StreamSpec spec) {
        if (spec == null) {
            return null;
        }
        int heightClass;
        boolean vertical = false;
        int fps = 30;
        if (spec.studio() != null) {
            heightClass = spec.studio().height();
            vertical = spec.studio().vertical();
            fps = spec.studio().fps();
        } else {
            heightClass = spec.maxHeight() > 0 ? spec.maxHeight() : 720;
        }
        Canvas c = Canvas.of(heightClass, vertical, fps);
        int kbps = FFmpegCommandBuilder.liveVideoKbps(Math.min(c.width(), c.height()), fps);
        return new Output(c.width(), c.height(), fps, vertical ? "Vertical" : "Landscape", spec.studio() != null ? kbps : null,
                "AAC 128 kbps, 44.1 kHz stereo", "Every 2 s");
    }

    /** Where the worker writes RE-8 reach. */
    private volatile String reachTable = "stream_job_destinations";

    /** For tests: read reach from a table shaped like feature/truth-of-live's; returns the previous one. */
    public String reachTable(String table) {
        String before = reachTable;
        reachTable = table;
        return before;
    }

    public Map<Integer, Reach> reachOf(long jobId) {
        Map<Integer, Reach> out = new HashMap<>();
        try {
            // Explicit columns, never SELECT *: a cached plan must not change shape when a migration adds columns.
            Integer columns = jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema = current_schema() "
                    + "AND table_name = ? AND column_name IN ('reach', 'reach_reason')", Integer.class, reachTable);
            if (columns == null || columns < 2) {
                return out;
            }
            jdbc.query("SELECT position, reach, reach_reason FROM " + reachTable + " WHERE job_id = ?",
                    rs -> {
                        if (rs.getString(2) != null) { // not reported (yet, or by a worker of the previous release)
                            out.put(rs.getInt(1), new Reach(rs.getString(2), rs.getString(3)));
                        }
                    }, jobId);
        } catch (Exception e) {
            log.debug("Reach of run {} unavailable: {}", jobId, e.getMessage());
        }
        return out;
    }

    private static List<Destination> destinations(StreamJob j, StreamSpec spec, Map<Integer, Reach> reach) {
        Map<Integer, StreamJobDestination> state = new HashMap<>();
        for (StreamJobDestination d : j.getDestinations()) {
            state.put(d.getPosition(), d);
        }
        List<Destination> out = new ArrayList<>();
        List<StreamSpec.Destination> list = spec == null || spec.destinations() == null ? List.of() : spec.destinations();
        for (int i = 0; i < list.size(); i++) {
            StreamSpec.Destination d = list.get(i);
            DestinationInspector.Inspection in = DestinationInspector.inspect(d.url());
            StreamJobDestination s = state.get(i);
            List<String> secrets = Redact.secretsOf(d.url());
            Reach r = reach.get(i);
            out.add(new Destination(i, d.name(), d.off(), in.server(), in.keyMasked(), in.keyLength(), in.level().name(), in.check(),
                    s == null ? null : s.getStatus(), s == null ? 0 : s.getRestarts(),
                    s == null ? null : Redact.line(s.getLastError(), secrets), s == null ? null : s.getUpdatedAt(),
                    r == null ? null : r.reach(), r == null ? null : Redact.line(r.reason(), secrets)));
        }
        return out;
    }

    /** Sends a 10-second test to one of the run's destinations from a worker; audited with the reason. */
    @Transactional
    public TestView testDestination(String adminEmail, Long jobId, int position, String reason) {
        String why = requireReason(reason);
        StreamJob j = jobs.findById(jobId).orElseThrow(() -> new NotFoundException("Stream run not found."));
        StreamSpec spec = specOf(j);
        if (spec == null || spec.destinations() == null || position < 0 || position >= spec.destinations().size()) {
            throw new NotFoundException("Destination not found.");
        }
        StreamSpec.Destination d = spec.destinations().get(position);
        if (j.isLive() && !d.off()) {
            throw new ConflictException("This destination is live right now. Test it when the stream has stopped.");
        }
        DestinationTest t = requestTest(j.getUser(), d.url());
        admin.record(adminEmail, "DESTINATION_TEST", "job", jobId, "destination " + (position + 1) + " (" + d.name() + "), test "
                + t.getId() + ": " + why);
        return TestView.of(t);
    }

    /**
     * Same rules as the user's own test (never while that address is live, one test at a time), but it
     * doesn't use up their hourly tests. A worker runs it like any other test.
     */
    private DestinationTest requestTest(com.afklive.streamer.model.User user, String address) {
        if (address == null || address.isBlank()) {
            throw new IllegalArgumentException("This destination has no address to test.");
        }
        NetworkSafety.requireSafeStreamDestination(address);
        for (StreamJob live : jobs.findAllByUserEmailAndIsLiveTrue(user.getEmail())) {
            StreamSpec ls = specOf(live);
            if (ls != null && ls.destinations() != null && ls.destinations().stream().anyMatch(x -> !x.off() && address.equals(x.url()))) {
                throw new ConflictException("This destination is live right now. Test it when the stream has stopped.");
            }
        }
        if (destinationTests.existsByUserIdAndStatusIn(user.getId(), List.of(DestinationTest.Status.PENDING, DestinationTest.Status.RUNNING))) {
            throw new ConflictException("A test is already running for this account. Wait for it to finish.");
        }
        DestinationTest t = new DestinationTest();
        t.setUserId(user.getId());
        t.setUrl(address);
        t.setCreatedAt(now());
        return destinationTests.save(t);
    }

    /** A destination test as support sees it: never the address. */
    public record TestView(Long id, String status, String message, ZonedDateTime createdAt, ZonedDateTime finishedAt) {
        static TestView of(DestinationTest t) {
            return new TestView(t.getId(), t.getStatus() == null ? null : t.getStatus().name(), Redact.streamKeys(t.getMessage()),
                    t.getCreatedAt(), t.getFinishedAt());
        }
    }

    public TestView destinationTest(Long testId) {
        ZonedDateTime cutoff = now().minus(TEST_EXPIRES_AFTER);
        destinationTests.expire("No stream server could run the test right now. Try again in a minute.", cutoff, cutoff, now());
        return TestView.of(destinationTests.findById(testId).orElseThrow(() -> new NotFoundException("Test not found.")));
    }

    /** As AdminService: every support action says why, for the audit log. */
    static String requireReason(String reason) {
        if (reason == null || reason.trim().length() < 3) {
            throw new IllegalArgumentException("Say why (it goes in the audit log).");
        }
        return reason.trim();
    }

    private static ZonedDateTime now() {
        return ZonedDateTime.now(ZoneId.of("UTC"));
    }
}
