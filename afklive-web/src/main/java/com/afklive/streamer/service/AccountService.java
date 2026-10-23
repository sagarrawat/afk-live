package com.afklive.streamer.service;

import com.afklive.streamer.dto.StreamDtos.ScheduleView;
import com.afklive.streamer.exception.ConflictException;
import com.afklive.streamer.exception.NotFoundException;
import com.afklive.streamer.model.Invoice;
import com.afklive.streamer.model.LiveStream;
import com.afklive.streamer.model.MediaAsset;
import com.afklive.streamer.model.SocialChannel;
import com.afklive.streamer.model.StreamDestination;
import com.afklive.streamer.model.StreamItem;
import com.afklive.streamer.model.StreamJob;
import com.afklive.streamer.model.StreamTarget;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.BillingProfileRepository;
import com.afklive.streamer.repository.InvoiceRepository;
import com.afklive.streamer.repository.LiveStreamRepository;
import com.afklive.streamer.repository.MediaAssetRepository;
import com.afklive.streamer.repository.StreamDestinationRepository;
import com.afklive.streamer.repository.StreamItemRepository;
import com.afklive.streamer.repository.StreamJobRepository;
import com.afklive.streamer.repository.StreamTargetRepository;
import com.afklive.streamer.repository.SubscriptionRepository;
import com.afklive.streamer.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * ON-7: the creator's data, to take away (a zip with one JSON file; never stream keys or other
 * secrets) and to delete. Deleting closes the account at once (live streams stop, schedules and
 * sign-in stop working) and removes the data after {@link #GRACE}, unless the creator signs in and
 * keeps the account before then. Removal runs on the web side about hourly; it is safe on several
 * instances (the user row is locked and the delete is idempotent).
 */
@Service
@Slf4j
public class AccountService {

    public static final Duration GRACE = Duration.ofDays(7);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final UserRepository users;
    private final LiveStreamRepository streams;
    private final StreamItemRepository items;
    private final StreamTargetRepository targets;
    private final StreamScheduleService schedules;
    private final StreamDestinationRepository destinations;
    private final MediaAssetRepository media;
    private final InvoiceRepository invoices;
    private final BillingProfileRepository billingProfiles;
    private final SubscriptionRepository subscriptions;
    private final StreamJobRepository jobs;
    private final StreamService streamService;
    private final FileStorageService storage;
    private final EmailService email;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final String role;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "account-deletion");
        t.setDaemon(true);
        return t;
    });

    public AccountService(UserRepository users, LiveStreamRepository streams, StreamItemRepository items,
                          StreamTargetRepository targets, StreamScheduleService schedules, StreamDestinationRepository destinations,
                          MediaAssetRepository media, InvoiceRepository invoices, BillingProfileRepository billingProfiles,
                          SubscriptionRepository subscriptions, StreamJobRepository jobs, StreamService streamService,
                          FileStorageService storage, EmailService email, JdbcTemplate jdbc, PlatformTransactionManager txManager,
                          @Value("${app.streaming.role:all}") String role) {
        this.users = users;
        this.streams = streams;
        this.items = items;
        this.targets = targets;
        this.schedules = schedules;
        this.destinations = destinations;
        this.media = media;
        this.invoices = invoices;
        this.billingProfiles = billingProfiles;
        this.subscriptions = subscriptions;
        this.jobs = jobs;
        this.streamService = streamService;
        this.storage = storage;
        this.email = email;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.role = role;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startTimer() {
        if ("worker".equalsIgnoreCase(role)) {
            return;
        }
        timer.scheduleWithFixedDelay(() -> {
            try {
                purgeDue(ZonedDateTime.now(ZoneId.of("UTC")));
            } catch (Exception e) {
                log.error("Account deletion run failed", e);
            }
        }, 10, 60, TimeUnit.MINUTES);
    }

    @PreDestroy
    public void stopTimer() {
        timer.shutdownNow();
    }

    // ------------------------------------------------------------------ export

    /** The account's data as a zip: {@code afk-live-data.json} and a short read-me. */
    public byte[] export(String emailAddress) {
        Map<String, Object> data = exportData(emailAddress);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(out)) {
                zip.putNextEntry(new ZipEntry("afk-live-data.json"));
                zip.write(JSON.writeValueAsBytes(data));
                zip.closeEntry();
                zip.putNextEntry(new ZipEntry("README.txt"));
                zip.write(("Your AFK Live data, exported " + data.get("exportedAt") + ".\n\n"
                        + "afk-live-data.json has your profile, streams with their settings and schedules, destinations,\n"
                        + "library (file details), plan and invoices. Stream keys and other secrets are left out on purpose.\n"
                        + "Download your videos from the Library, and invoices as PDFs from Settings > Plan & billing.\n")
                        .getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Couldn't build the export", e);
        }
    }

    /** Everything in the export, as plain maps (no secrets). */
    public Map<String, Object> exportData(String emailAddress) {
        return tx.execute(s -> collect(emailAddress));
    }

    private Map<String, Object> collect(String emailAddress) {
        User u = users.findByEmail(emailAddress).orElseThrow(() -> new NotFoundException("Account not found."));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("format", "afk-live-export/1");
        out.put("exportedAt", iso(ZonedDateTime.now(ZoneId.of("UTC"))));

        out.put("profile", map("email", u.getEmail(), "name", u.getFullName(), "timeZone", u.getTimeZone(),
                "currency", u.getCurrency(), "plan", u.getPlanType() == null ? null : u.getPlanType().name(),
                "planExpires", u.getPlanExpiration() == null ? null : u.getPlanExpiration().toString(),
                "emailAlerts", u.isEmailAlerts(), "twoStepSignIn", u.isTwoFactorEnabled(),
                "onboardedAt", iso(u.getOnboardedAt()), "usedStorageBytes", users.usedStorage(u.getId())));

        billingProfiles.findById(u.getId()).ifPresent(b -> out.put("billingProfile", map("legalName", b.getLegalName(),
                "address", b.getAddressLine(), "city", b.getCity(), "state", b.getStateCode(), "postalCode", b.getPostalCode(),
                "country", b.getCountry(), "gstin", b.getGstin())));
        subscriptions.findByUserId(u.getId()).ifPresent(s -> out.put("subscription", map("plan", s.getPlanType().name(),
                "cycle", s.getCycle() == null ? null : s.getCycle().name(), "status", s.getStatus() == null ? null : s.getStatus().name(),
                "currency", s.getCurrency(), "periodStart", iso(s.getCurrentPeriodStart()), "periodEnd", iso(s.getCurrentPeriodEnd()),
                "cancelAtPeriodEnd", s.isCancelAtPeriodEnd())));

        List<StreamDestination> dests = destinations.findByUser(u);
        Map<Long, String> destNames = new LinkedHashMap<>();
        List<Map<String, Object>> destList = new ArrayList<>();
        for (StreamDestination d : dests) {
            destNames.put(d.getId(), d.getName());
            // Never the stream key: it lets anyone broadcast to the channel.
            destList.add(map("id", d.getId(), "name", d.getName(), "type", d.getType(), "selected", d.isSelected()));
        }
        out.put("destinations", destList);

        List<Map<String, Object>> streamList = new ArrayList<>();
        for (LiveStream s : streams.findAllByUserEmailOrderByCreatedAtAsc(u.getEmail())) {
            List<Map<String, Object>> videos = new ArrayList<>();
            List<Map<String, Object>> music = new ArrayList<>();
            for (StreamItem i : items.findAllByStreamIdOrderByPositionAsc(s.getId())) {
                Map<String, Object> item = map("position", i.getPosition(), "mediaId", i.getMedia() == null ? null : i.getMedia().getId(),
                        "title", i.getMedia() == null ? null : i.getMedia().getTitle(), "fit", i.getFit());
                (i.getKind() == StreamItem.Kind.AUDIO ? music : videos).add(item);
            }
            List<Map<String, Object>> sendsTo = new ArrayList<>();
            for (StreamTarget t : targets.findAllByStreamId(s.getId())) {
                sendsTo.add(map("destinationId", t.getDestinationId(), "name", destNames.get(t.getDestinationId()), "enabled", t.isEnabled()));
            }
            List<Map<String, Object>> sched = new ArrayList<>();
            for (ScheduleView v : schedules.list(u.getEmail(), s.getId())) {
                sched.add(map("kind", v.kind(), "startAt", iso(v.startAt()), "endAt", iso(v.endAt()), "daysOfWeek", v.daysOfWeek(),
                        "startTime", v.startTime(), "endTime", v.endTime(), "timeZone", v.timeZone(), "skipDates", v.skipDates(),
                        "enabled", v.enabled(), "playlist", v.playlist() == null ? List.of()
                                : v.playlist().stream().map(p -> map("mediaId", p.id(), "title", p.title())).toList()));
            }
            streamList.add(map("id", s.getId(), "name", s.getName(), "source", s.getSource().name(),
                    "orientation", s.getOrientation().name(), "qualityHeight", s.getQualityHeight(), "fps", s.getFps(),
                    "playbackOrder", s.getPlaybackOrder().name(), "loopCount", s.getLoopCount(), "musicOrder", s.getMusicOrder().name(),
                    "audioMode", s.getAudioMode().name(), "videoVolume", s.getVideoVolume(), "musicVolume", s.getMusicVolume(),
                    "fit", s.getFit(), "crossfadeSeconds", s.getCrossfadeSeconds(), "timeZone", s.getTimeZone(),
                    "endOnInputLoss", s.isEndOnInputLoss(), "inputGraceSeconds", s.getInputGraceSeconds(), "srtLatencyMs", s.getSrtLatencyMs(),
                    "overlays", parse(s.getOverlays()), "scenes", parse(s.getScenes()),
                    "videos", videos, "music", music, "destinations", sendsTo, "schedules", sched,
                    "createdAt", iso(s.getCreatedAt()), "updatedAt", iso(s.getUpdatedAt())));
        }
        out.put("streams", streamList);

        List<Map<String, Object>> library = new ArrayList<>();
        for (MediaAsset a : media.findByUserEmail(u.getEmail())) {
            library.add(map("id", a.getId(), "title", a.getTitle(), "type", a.getContentType() == null ? null : a.getContentType().name(),
                    "status", a.getStatus() == null ? null : a.getStatus().name(), "sizeBytes", a.getFileSize(),
                    "durationSeconds", a.getDurationSeconds(), "width", a.getWidth(), "height", a.getHeight(), "fps", a.getFps(),
                    "uploadedAt", a.getCreatedAt() == null ? null : a.getCreatedAt().toString(),
                    "download", "/api/media/" + a.getId() + "/file"));
        }
        out.put("library", library);

        List<Map<String, Object>> invoiceList = new ArrayList<>();
        for (Invoice i : invoices.findAllByUserIdOrderByIssuedAtDesc(u.getId())) {
            invoiceList.add(map("number", i.getNumber(), "issuedAt", iso(i.getIssuedAt()), "description", i.getDescription(),
                    "periodStart", iso(i.getPeriodStart()), "periodEnd", iso(i.getPeriodEnd()), "currency", i.getCurrency(),
                    "taxableAmount", i.getTaxableAmount(), "cgst", i.getCgst(), "sgst", i.getSgst(), "igst", i.getIgst(),
                    "total", i.getTotal(), "amountsIn", "minor units (paise or cents)",
                    "pdf", "/api/billing/invoices/" + i.getId() + ".pdf"));
        }
        out.put("invoices", invoiceList);

        List<Map<String, Object>> channels = new ArrayList<>();
        for (SocialChannel c : u.getChannels()) {
            channels.add(map("name", c.getName(), "platform", c.getPlatform(), "profileUrl", c.getProfileUrl()));
        }
        out.put("connectedChannels", channels);
        return out;
    }

    // ------------------------------------------------------------------ deletion

    public record DeletionState(boolean scheduled, ZonedDateTime scheduledFor) {
    }

    public DeletionState deletionState(String emailAddress) {
        User u = users.findByEmail(emailAddress).orElseThrow(() -> new NotFoundException("Account not found."));
        return new DeletionState(u.getDeletionScheduledFor() != null, u.getDeletionScheduledFor());
    }

    /**
     * Closes the account now and schedules its data for deletion after {@link #GRACE}. The creator
     * types their email to confirm. Live streams are asked to stop; schedules won't start them again.
     */
    public ZonedDateTime requestDeletion(String emailAddress, String typedEmail) {
        if (typedEmail == null || !typedEmail.trim().equalsIgnoreCase(emailAddress)) {
            throw new IllegalArgumentException("Type your email address exactly as shown to confirm.");
        }
        ZonedDateTime when = tx.execute(s -> {
            User u = users.lockByEmail(emailAddress).orElseThrow(() -> new NotFoundException("Account not found."));
            if ("ROLE_ADMIN".equals(u.getRole())) {
                throw new ConflictException("Admin accounts can't be deleted here. Ask another admin to remove the role first.");
            }
            if (u.getDeletionScheduledFor() != null) {
                return u.getDeletionScheduledFor();
            }
            ZonedDateTime now = ZonedDateTime.now(ZoneId.of("UTC"));
            u.setDeletionRequestedAt(now);
            u.setDeletionScheduledFor(now.plus(GRACE));
            users.save(u);
            for (StreamJob j : jobs.findAllByUserEmailAndIsLiveTrue(emailAddress)) {
                streamService.requestStop(j, "Account deletion requested");
            }
            return u.getDeletionScheduledFor();
        });
        log.info("Account {} scheduled for deletion on {}", emailAddress, when);
        try {
            email.sendDeletionScheduledEmail(emailAddress, when.format(DAY));
        } catch (Exception e) {
            log.warn("Couldn't send the deletion email to {}: {}", emailAddress, e.getMessage());
        }
        return when;
    }

    /** Keeps the account: undoes a scheduled deletion (streams stay stopped; schedules resume). */
    public void cancelDeletion(String emailAddress) {
        tx.executeWithoutResult(s -> {
            User u = users.lockByEmail(emailAddress).orElseThrow(() -> new NotFoundException("Account not found."));
            if (u.getDeletionScheduledFor() == null) {
                return;
            }
            u.setDeletionRequestedAt(null);
            u.setDeletionScheduledFor(null);
            users.save(u);
        });
        log.info("Account {} kept: deletion cancelled", emailAddress);
    }

    /** Deletes every account whose grace period has ended; returns how many went. */
    public int purgeDue(ZonedDateTime now) {
        List<Long> due = jdbc.queryForList(
                "SELECT id FROM users WHERE deletion_scheduled_for IS NOT NULL AND deletion_scheduled_for <= ? ORDER BY id",
                Long.class, java.sql.Timestamp.from(now.toInstant()));
        int deleted = 0;
        for (Long id : due) {
            try {
                if (purge(id, now)) {
                    deleted++;
                }
            } catch (Exception e) {
                log.error("Couldn't delete account {}; will retry", id, e);
            }
        }
        return deleted;
    }

    private record Files(List<String> keys, List<String[]> openUploads) {
    }

    /**
     * Removes one account: its rows (the database cascades from {@code users}), its files, and its
     * connected-channel tokens. Invoices stay (tax law), detached from the account; B2C invoices lose
     * the customer's name and address.
     */
    boolean purge(Long userId, ZonedDateTime now) {
        Files files = tx.execute(s -> {
            User u = users.lockById(userId).orElse(null);
            if (u == null || u.getDeletionScheduledFor() == null || u.getDeletionScheduledFor().isAfter(now)) {
                return null; // kept, or already gone
            }
            List<StreamJob> live = jobs.findAllByUserEmailAndIsLiveTrue(u.getEmail());
            if (!live.isEmpty()) {
                live.forEach(j -> streamService.requestStop(j, "Account deleted"));
                return null; // the worker ends them; next run deletes
            }
            List<String> keys = new ArrayList<>();
            long bytes = 0;
            for (Map<String, Object> row : jdbc.queryForList(
                    "SELECT s3_key, thumbnail_key, COALESCE(file_size, 0) AS size FROM media_assets WHERE user_id = ?", userId)) {
                keys.add((String) row.get("s3_key"));
                if (row.get("thumbnail_key") != null) {
                    keys.add((String) row.get("thumbnail_key"));
                }
                bytes += ((Number) row.get("size")).longValue();
            }
            List<String[]> uploads = new ArrayList<>();
            for (Map<String, Object> row : jdbc.queryForList(
                    "SELECT storage_key, multipart_id, status FROM media_uploads WHERE user_id = ?", userId)) {
                if ("UPLOADING".equals(row.get("status")) && row.get("multipart_id") != null) {
                    uploads.add(new String[]{(String) row.get("storage_key"), (String) row.get("multipart_id")});
                }
            }
            List<String> credentials = jdbc.queryForList(
                    "SELECT credential_id FROM social_channels WHERE user_id = ? AND credential_id IS NOT NULL", String.class, userId);

            // Invoices are kept for the tax records. Without a GSTIN (B2C) the name and address aren't needed.
            jdbc.update("UPDATE invoices SET customer_name = NULL, customer_address = NULL "
                    + "WHERE user_id = ? AND (customer_gstin IS NULL OR customer_gstin = '')", userId);
            jdbc.update("UPDATE payment_audits SET merchant_user_id = NULL WHERE user_id = ?", userId);
            for (String c : credentials) {
                jdbc.update("DELETE FROM oauth2_authorized_client WHERE principal_name = ?", c);
            }
            // Old upload schedules point at library files without ON DELETE; remove them first.
            jdbc.update("DELETE FROM scheduled_videos WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM scheduled_streams WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM media_assets WHERE user_id = ?", userId);
            users.addStorage(userId, -bytes);
            jdbc.update("DELETE FROM users WHERE id = ?", userId); // cascades: streams, runs, destinations, settings, ...
            log.info("Deleted account {} ({} files)", userId, keys.size());
            return new Files(keys, uploads);
        });
        if (files == null) {
            return false;
        }
        for (String[] up : files.openUploads()) {
            try {
                storage.abortMultipart(up[0], up[1]);
            } catch (Exception e) {
                log.warn("Couldn't abort upload {} of a deleted account", up[0], e);
            }
        }
        for (String key : files.keys()) {
            try {
                storage.deleteFile(key);
            } catch (Exception e) {
                log.warn("Couldn't delete {} of a deleted account", key, e); // the row is gone; an orphan only costs storage
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ helpers

    private static Object parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private static String iso(ZonedDateTime t) {
        return t == null ? null : t.withZoneSameInstant(ZoneId.of("UTC")).toOffsetDateTime().toString();
    }

    /** An ordered map that allows null values. */
    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
