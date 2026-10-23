package com.afklive.streamer.service;

import com.afklive.streamer.dto.StreamDtos.*;
import com.afklive.streamer.model.*;
import com.afklive.streamer.repository.*;
import com.afklive.streamer.streaming.StreamSpec;
import com.afklive.streamer.streaming.StreamStates;
import com.afklive.streamer.streaming.StudioSpec;
import com.afklive.streamer.util.NetworkSafety;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Saved streams (ST-1..3): settings, playlists (PL-1..4), music and audio mode (AU-1, AU-2),
 * destinations, overlays and scenes; pre-flight (GL-1), going live and stopping (GL-2, GL-3); live
 * changes, which reach the worker as commands and apply between frames.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LiveStreamService {

    static final int MAX_STREAMS_PER_USER = 50;
    static final int MAX_PLAYLIST_ITEMS = 500;
    static final int MAX_OVERLAYS = 20;
    static final Set<String> SCENES = Set.of(StudioSpec.STARTING, StudioSpec.LIVE, StudioSpec.BRB, StudioSpec.ENDING);
    private static final Pattern OVERLAY_ID = Pattern.compile("[A-Za-z0-9_-]{1,40}");
    private static final Pattern COLOR = Pattern.compile("#?([0-9a-fA-F]{6}|[0-9a-fA-F]{8})");

    private final LiveStreamRepository streams;
    private final StreamItemRepository items;
    private final StreamTargetRepository targets;
    private final StreamScheduleRepository schedules;
    private final StreamCommandRepository commands;
    private final StreamJobRepository jobs;
    private final MediaAssetRepository media;
    private final StreamDestinationRepository destinations;
    private final UserService userService;
    private final PlanService planService;
    private final AppConfigService appConfigService;
    private final StreamService streamService;
    private final UserRepository users;
    private final IngestService ingest;
    private final NotificationService notifications;
    private final com.afklive.streamer.repository.StreamJobDestinationRepository jobDestinations;

    // ------------------------------------------------------------------ create, read, update, delete

    @Transactional
    public LiveStream create(String email, CreateStream req) {
        User user = userService.getOrCreateUser(email);
        if (streams.countByUserEmail(email) >= MAX_STREAMS_PER_USER) {
            throw new IllegalStateException("You can have up to " + MAX_STREAMS_PER_USER + " streams. Delete one you no longer use.");
        }
        LiveStream s = new LiveStream();
        s.setUser(user);
        s.setName(name(req.name()));
        if (req.source() != null) {
            s.setSource(parse(LiveStream.Source.class, req.source(), "source"));
        }
        if (req.orientation() != null) {
            s.setOrientation(parse(LiveStream.Orientation.class, req.orientation(), "orientation"));
        }
        int planMax = planMaxHeight(user);
        s.setQualityHeight(req.qualityHeight() == null ? Math.min(1080, planMax) : req.qualityHeight());
        s.setFps(req.fps() == null ? 30 : req.fps());
        checkQuality(user, s.getQualityHeight(), s.getFps());
        s.setScenes(StudioSpec.json(defaultScenes()));
        if (s.getSource() == LiveStream.Source.PHONE) {
            // Viewers should see something friendly when the signal drops, from day one.
            s.setOverlays(StudioSpec.json(List.of(new StudioSpec.OverlaySetting("brb-text", "TEXT", true, 0.32, 0.44, 0,
                    0.07, "Be right back", null, null, "#ffffff", "#99000000", 1, null, null, 0, StudioSpec.BRB))));
            s = streams.save(s);
            return ingest.ensureKey(s);
        }
        s.setOverlays(StudioSpec.json(List.of()));
        return streams.save(s);
    }

    public List<StreamSummary> list(String email) {
        List<StreamSummary> out = new ArrayList<>();
        for (LiveStream s : streams.findAllByUserEmailOrderByCreatedAtAsc(email)) {
            int count = items.findAllByStreamIdAndKindOrderByPositionAsc(s.getId(), StreamItem.Kind.VIDEO).size();
            out.add(new StreamSummary(s.getId(), s.getName(), s.getSource().name(), s.getOrientation().name(),
                    s.getQualityHeight(), count, liveState(s)));
        }
        return out;
    }

    public LiveStream get(String email, Long id) {
        return streams.findByIdAndUserEmail(id, email).orElseThrow(() -> new com.afklive.streamer.exception.NotFoundException("Stream not found."));
    }

    public StreamView view(String email, Long id) {
        LiveStream s = get(email, id);
        List<MediaRef> playlist = refs(items.findAllByStreamIdAndKindOrderByPositionAsc(id, StreamItem.Kind.VIDEO));
        List<MediaRef> music = refs(items.findAllByStreamIdAndKindOrderByPositionAsc(id, StreamItem.Kind.AUDIO));
        double runtime = playlist.stream().mapToDouble(m -> m.durationSeconds() == null ? 0 : m.durationSeconds()).sum();
        List<TargetView> tv = new ArrayList<>();
        for (StreamTarget t : targets.findAllByStreamId(id)) {
            destinations.findById(t.getDestinationId()).ifPresent(d ->
                    tv.add(new TargetView(d.getId(), d.getName(), d.getType(), t.isEnabled())));
        }
        return new StreamView(s.getId(), s.getName(), s.getSource().name(), s.getOrientation().name(), s.getQualityHeight(),
                s.getFps(), s.getPlaybackOrder().name(), s.getLoopCount(), s.getMusicOrder().name(), s.getAudioMode().name(),
                s.getVideoVolume(), s.getMusicVolume(), s.getTimeZone(), overlays(s), scenes(s), playlist, music, runtime,
                tv, scheduleViews(s), liveState(s),
                s.getSource() == LiveStream.Source.PHONE
                        ? new PhoneSettings(s.isEndOnInputLoss(), s.getInputGraceSeconds(), s.getSrtLatencyMs(), s.getIngestLastPublishAt())
                        : null);
    }

    @Transactional
    public LiveStream update(String email, Long id, UpdateStream req) {
        LiveStream s = get(email, id);
        User user = s.getUser();
        if (req.name() != null) {
            s.setName(name(req.name()));
        }
        if (req.orientation() != null) {
            s.setOrientation(parse(LiveStream.Orientation.class, req.orientation(), "orientation"));
        }
        boolean live = currentJob(s).isPresent();
        boolean picture = (req.orientation() != null && !req.orientation().equalsIgnoreCase(s.getOrientation().name()))
                || (req.qualityHeight() != null && req.qualityHeight() != s.getQualityHeight())
                || (req.fps() != null && req.fps() != s.getFps());
        if (live && picture) {
            throw new com.afklive.streamer.exception.ConflictException(
                    "Quality, frame rate and orientation can't change while live. Stop the stream to change them.");
        }
        if (req.qualityHeight() != null || req.fps() != null) {
            int h = req.qualityHeight() != null ? req.qualityHeight() : s.getQualityHeight();
            int f = req.fps() != null ? req.fps() : s.getFps();
            checkQuality(user, h, f);
            s.setQualityHeight(h);
            s.setFps(f);
        }
        if (req.playbackOrder() != null) {
            s.setPlaybackOrder(parse(LiveStream.Order.class, req.playbackOrder(), "playback order"));
        }
        if (req.loopCount() != null) {
            if (req.loopCount() < 0 || req.loopCount() > 1000) {
                throw new IllegalArgumentException("Loops must be 0 (forever) to 1000.");
            }
            s.setLoopCount(req.loopCount());
        }
        if (req.musicOrder() != null) {
            s.setMusicOrder(parse(LiveStream.Order.class, req.musicOrder(), "music order"));
        }
        if (req.audioMode() != null) {
            s.setAudioMode(parse(LiveStream.AudioMode.class, req.audioMode(), "audio mode"));
        }
        if (req.videoVolume() != null) {
            s.setVideoVolume(volume(req.videoVolume()));
        }
        if (req.musicVolume() != null) {
            s.setMusicVolume(volume(req.musicVolume()));
        }
        if (req.timeZone() != null) {
            s.setTimeZone(zone(req.timeZone()).getId());
        }
        if (req.endOnInputLoss() != null) {
            s.setEndOnInputLoss(req.endOnInputLoss());
        }
        if (req.inputGraceSeconds() != null) {
            if (req.inputGraceSeconds() < 10 || req.inputGraceSeconds() > 3600) {
                throw new IllegalArgumentException("Grace time must be between 10 seconds and 1 hour.");
            }
            s.setInputGraceSeconds(req.inputGraceSeconds());
        }
        if (req.srtLatencyMs() != null) {
            if (req.srtLatencyMs() < 200 || req.srtLatencyMs() > 5000) {
                throw new IllegalArgumentException("SRT latency must be between 200 and 5000 ms.");
            }
            s.setSrtLatencyMs(req.srtLatencyMs());
        }
        s = streams.save(s);
        reloadIfLive(s);
        return s;
    }

    @Transactional
    public void delete(String email, Long id) {
        LiveStream s = get(email, id);
        if (!jobs.findAllByStreamIdAndIsLiveTrue(id).isEmpty()) {
            throw new com.afklive.streamer.exception.ConflictException("Stop the stream before deleting it.");
        }
        streams.delete(s);
    }

    // ------------------------------------------------------------------ playlist, music, destinations

    /** PL-1, PL-2, AU-1: the full ordered list (adding, removing and reordering are all a new list). */
    @Transactional
    public void setItems(String email, Long id, StreamItem.Kind kind, List<Long> mediaIds) {
        LiveStream s = get(email, id);
        if (mediaIds == null) {
            mediaIds = List.of();
        }
        if (mediaIds.size() > MAX_PLAYLIST_ITEMS) {
            throw new IllegalArgumentException("A playlist can hold up to " + MAX_PLAYLIST_ITEMS + " items.");
        }
        Map<Long, MediaAsset> owned = new HashMap<>();
        for (MediaAsset m : media.findAllByIdInAndUserEmail(new HashSet<>(mediaIds), email)) {
            owned.put(m.getId(), m);
        }
        MediaAsset.ContentType wanted = kind == StreamItem.Kind.VIDEO ? MediaAsset.ContentType.VIDEO : MediaAsset.ContentType.AUDIO;
        List<StreamItem> next = new ArrayList<>();
        int pos = 0;
        for (Long mid : mediaIds) {
            MediaAsset m = owned.get(mid);
            if (m == null) {
                throw new IllegalArgumentException("A file in this list isn't in your library.");
            }
            if (m.getContentType() != wanted) {
                throw new IllegalArgumentException("\"" + m.getTitle() + "\" is " + m.getContentType().name().toLowerCase()
                        + "; this list takes " + wanted.name().toLowerCase() + " files.");
            }
            next.add(new StreamItem(id, kind, pos++, m));
        }
        items.deleteAll(items.findAllByStreamIdAndKindOrderByPositionAsc(id, kind));
        items.flush();
        items.saveAll(next);
        reloadIfLive(s);
    }

    @Transactional
    public void setTargets(String email, Long id, List<TargetRequest> req) {
        LiveStream s = get(email, id);
        List<StreamTarget> next = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (TargetRequest t : req == null ? List.<TargetRequest>of() : req) {
            if (t.destinationId() == null || !seen.add(t.destinationId())) {
                continue;
            }
            StreamDestination d = destinations.findByIdAndUserEmail(t.destinationId(), email)
                    .orElseThrow(() -> new IllegalArgumentException("Destination not found."));
            next.add(new StreamTarget(s.getId(), d.getId(), t.enabled()));
        }
        // BI-8: destinations per stream by plan.
        PlanConfig plan = planService.getPlanConfig(s.getUser().getPlanType());
        long enabled = next.stream().filter(StreamTarget::isEnabled).count();
        if (enabled > plan.getMaxDestinations()) {
            throw new IllegalArgumentException("Your " + plan.getDisplayName() + " plan sends a stream to " + plan.getMaxDestinations()
                    + " destination" + (plan.getMaxDestinations() == 1 ? "" : "s") + " at once. Upgrade to add more.");
        }
        Optional<StreamJob> live = currentJob(s);
        if (live.isPresent() && enabled == 0) {
            throw new IllegalArgumentException("A live stream needs at least one destination. Stop the stream to turn off the last one.");
        }
        targets.deleteAll(targets.findAllByStreamId(id));
        targets.flush();
        targets.saveAll(next);
        live.ifPresent(job -> applyTargetsLive(job, s));
    }

    /**
     * DS-5: destinations turned on or off while live reach the running stream without touching the
     * others. Positions in the run's list never change (destination health is keyed by them): a
     * destination turned off stays in the list marked off; a new one is added at the end.
     */
    private void applyTargetsLive(StreamJob job, LiveStream s) {
        StreamSpec spec = StreamSpec.fromJson(job.getSpec());
        List<StreamSpec.Destination> current = spec.destinations() == null ? List.of() : spec.destinations();
        List<StreamSpec.Destination> next = new ArrayList<>();
        boolean[] wanted = new boolean[current.size()];
        List<StreamDestination> added = new ArrayList<>();
        for (StreamDestination d : enabledDestinations(s)) {
            int match = -1;
            for (int i = 0; i < current.size(); i++) {
                if (!wanted[i] && java.util.Objects.equals(current.get(i).url(), d.getStreamKey())) {
                    match = i;
                    break;
                }
            }
            if (match >= 0) {
                wanted[match] = true;
            } else {
                added.add(d);
            }
        }
        for (int i = 0; i < current.size(); i++) {
            next.add(current.get(i).withOff(!wanted[i]));
        }
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("UTC"));
        for (StreamDestination d : added) {
            String name = d.getName() == null ? "Destination" : d.getName();
            next.add(new StreamSpec.Destination(name, d.getStreamKey()));
            StreamJobDestination row = new StreamJobDestination(job, next.size() - 1, name);
            row.setUpdatedAt(now);
            jobDestinations.save(row);
        }
        if (next.equals(current)) {
            return;
        }
        for (int i = 0; i < current.size(); i++) {
            if (next.get(i).off() != current.get(i).off()) {
                jobDestinations.updateState(job.getId(), i, next.get(i).off() ? "OFF" : "CONNECTING", 0, null, now);
            }
        }
        jobs.updateSpec(job.getId(), spec.withDestinations(next).toJson());
        command(job, StreamCommand.Type.TARGETS, StreamSpec.destinationsJson(next));
    }

    // ------------------------------------------------------------------ overlays and scenes

    @Transactional
    public List<StudioSpec.OverlaySetting> setOverlays(String email, Long id, List<StudioSpec.OverlaySetting> list) {
        get(email, id);
        LiveStream s = streams.lockById(id).orElseThrow(); // two tabs editing overlays mustn't drop each other's changes
        List<StudioSpec.OverlaySetting> clean = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        if (list != null && list.size() > MAX_OVERLAYS) {
            throw new IllegalArgumentException("Up to " + MAX_OVERLAYS + " overlays per stream.");
        }
        for (StudioSpec.OverlaySetting o : list == null ? List.<StudioSpec.OverlaySetting>of() : list) {
            String oid = o.id() == null || o.id().isBlank() ? UUID.randomUUID().toString().substring(0, 8) : o.id();
            if (!OVERLAY_ID.matcher(oid).matches() || !ids.add(oid)) {
                throw new IllegalArgumentException("Overlay ids must be unique letters, numbers, - or _.");
            }
            String type = o.type() == null ? "" : o.type().toUpperCase(Locale.ROOT);
            if (!Set.of("IMAGE", "TEXT", "CLOCK", "COUNTDOWN", "TICKER").contains(type)) {
                throw new IllegalArgumentException("Unknown overlay type: " + o.type());
            }
            if (o.text() != null && o.text().length() > 300) {
                throw new IllegalArgumentException("Overlay text is limited to 300 characters.");
            }
            Long imageId = null;
            if ("IMAGE".equals(type)) {
                MediaAsset img = media.findByIdAndUserEmail(o.imageMediaId(), email)
                        .filter(m -> m.getContentType() == MediaAsset.ContentType.IMAGE)
                        .orElseThrow(() -> new IllegalArgumentException("Pick an image from your library for the image overlay."));
                imageId = img.getId();
            }
            if (o.timeZone() != null && !o.timeZone().isBlank()) {
                zone(o.timeZone());
            }
            if (o.target() != null && !o.target().isBlank()) {
                try {
                    java.time.Instant.parse(o.target());
                } catch (Exception e) {
                    throw new IllegalArgumentException("Countdown time must be a full date and time.");
                }
            }
            if (o.scene() != null && !o.scene().isBlank() && !SCENES.contains(o.scene())) {
                throw new IllegalArgumentException("Unknown scene: " + o.scene());
            }
            clean.add(new StudioSpec.OverlaySetting(oid, type, o.visible(), fraction(o.x()), fraction(o.y()),
                    "IMAGE".equals(type) ? Math.max(0.02, Math.min(1, o.width() <= 0 ? 0.15 : o.width())) : fraction(o.width()),
                    o.size() <= 0 ? 0.045 : Math.max(0.015, Math.min(0.2, o.size())),
                    o.text(), imageId, null, color(o.color()), color(o.background()),
                    o.opacity() <= 0 ? 1.0 : Math.min(1, o.opacity()),
                    blankToNull(o.timeZone()), blankToNull(o.target()), Math.max(0, Math.min(1000, o.speed())),
                    blankToNull(o.scene())));
        }
        s.setOverlays(StudioSpec.json(clean));
        streams.save(s);
        reloadIfLive(s);
        return clean;
    }

    @Transactional
    public List<StudioSpec.SceneSetting> setScenes(String email, Long id, List<StudioSpec.SceneSetting> list) {
        get(email, id);
        LiveStream s = streams.lockById(id).orElseThrow();
        Map<String, StudioSpec.SceneSetting> byName = new LinkedHashMap<>();
        for (StudioSpec.SceneSetting sc : defaultScenes()) {
            byName.put(sc.name(), sc);
        }
        for (StudioSpec.SceneSetting sc : list == null ? List.<StudioSpec.SceneSetting>of() : list) {
            if (!SCENES.contains(sc.name())) {
                throw new IllegalArgumentException("Unknown scene: " + sc.name());
            }
            String type = "COLOR";
            Long bg = null;
            if (sc.backgroundMediaId() != null) {
                MediaAsset m = media.findByIdAndUserEmail(sc.backgroundMediaId(), email)
                        .orElseThrow(() -> new IllegalArgumentException("Scene background isn't in your library."));
                if (m.getContentType() == MediaAsset.ContentType.AUDIO) {
                    throw new IllegalArgumentException("A scene background must be an image or a video.");
                }
                type = m.getContentType() == MediaAsset.ContentType.IMAGE ? "IMAGE" : "VIDEO";
                bg = m.getId();
            }
            byName.put(sc.name(), new StudioSpec.SceneSetting(sc.name(), bg, null, type, color(sc.color()), sc.music()));
        }
        List<StudioSpec.SceneSetting> clean = new ArrayList<>(byName.values());
        s.setScenes(StudioSpec.json(clean));
        streams.save(s);
        reloadIfLive(s);
        return clean;
    }

    static List<StudioSpec.SceneSetting> defaultScenes() {
        return List.of(
                new StudioSpec.SceneSetting(StudioSpec.STARTING, null, null, "COLOR", "#101820", true),
                new StudioSpec.SceneSetting(StudioSpec.LIVE, null, null, "COLOR", "#000000", true),
                new StudioSpec.SceneSetting(StudioSpec.BRB, null, null, "COLOR", "#101820", true),
                new StudioSpec.SceneSetting(StudioSpec.ENDING, null, null, "COLOR", "#101820", true));
    }

    // ------------------------------------------------------------------ pre-flight and going live

    /** GL-1: everything that would make a start fail, each with where to fix it. */
    public Preflight preflight(String email, Long id) {
        LiveStream s = get(email, id);
        User user = s.getUser();
        List<Check> checks = new ArrayList<>();

        if (s.getSource() == LiveStream.Source.PLAYLIST) {
            List<StreamItem> videos = items.findAllByStreamIdAndKindOrderByPositionAsc(id, StreamItem.Kind.VIDEO);
            long ready = videos.stream().filter(i -> i.getMedia().getStatus() == MediaStatus.READY).count();
            if (videos.isEmpty()) {
                checks.add(new Check("playlist", false, "Add at least one video to the playlist.", "playlist"));
            } else if (ready == 0) {
                checks.add(new Check("playlist", false, "None of the playlist's videos are ready yet (still processing or failed).", "playlist"));
            } else if (ready < videos.size()) {
                checks.add(new Check("playlist", true, (videos.size() - ready) + " video(s) aren't ready and will be skipped.", "playlist"));
            } else {
                checks.add(new Check("playlist", true, videos.size() + " video(s) ready.", "playlist"));
            }
        }

        if (s.getSource() == LiveStream.Source.PHONE) {
            if (!ingest.enabled()) {
                checks.add(new Check("phone", false, "Phone streaming isn't available on this server yet.", "phone"));
            } else if (s.getIngestLastPublishAt() == null) {
                checks.add(new Check("phone", true, "Set up your phone app first (one QR scan). After you go live, start "
                        + "streaming from the app; until it connects viewers see the BRB scene.", "phone"));
            } else {
                checks.add(new Check("phone", true, "Phone app set up. After you go live, start streaming from the app.", "phone"));
            }
        }

        List<StreamDestination> enabled = enabledDestinations(s);
        int maxDest = planService.getPlanConfig(user.getPlanType()).getMaxDestinations();
        if (enabled.size() > maxDest) {
            checks.add(new Check("destinations-limit", false, "Your plan sends to " + maxDest + " destination"
                    + (maxDest == 1 ? "" : "s") + " at once; turn some off or upgrade.", "destinations"));
        }
        if (enabled.isEmpty()) {
            checks.add(new Check("destinations", false, "Turn on at least one destination (YouTube stream key or RTMP/SRT).", "destinations"));
        } else {
            for (StreamDestination d : enabled) {
                try {
                    NetworkSafety.requireSafeStreamDestination(d.getStreamKey());
                    checks.add(new Check("destination-" + d.getId(), true, (d.getName() == null ? "Destination" : d.getName()) + " is set up.", "destinations"));
                } catch (Exception e) {
                    checks.add(new Check("destination-" + d.getId(), false,
                            (d.getName() == null ? "A destination" : d.getName()) + ": " + e.getMessage(), "destinations"));
                }
            }
        }

        int planMax = planMaxHeight(user);
        String planName = planService.getPlanConfig(user.getPlanType()).getDisplayName();
        if (s.getQualityHeight() > planMax) {
            checks.add(new Check("quality", false, s.getQualityHeight() + "p is above your " + planName + " plan's " + planMax + "p.", "/pricing"));
        } else if (s.getFps() > planService.getPlanConfig(user.getPlanType()).getMaxFps()) {
            checks.add(new Check("quality", false, s.getFps() + " fps isn't included in your " + planName + " plan.", "/pricing"));
        } else {
            checks.add(new Check("quality", true, s.getQualityHeight() + "p" + s.getFps() + " is included in your plan.", "settings"));
        }

        int live = (int) jobs.countByUserEmailAndIsLiveTrue(email);
        int maxStreams = planService.getPlanConfig(user.getPlanType()).getMaxActiveStreams();
        boolean alreadyLive = !jobs.findAllByStreamIdAndIsLiveTrue(id).isEmpty();
        if (alreadyLive) {
            checks.add(new Check("running", false, "This stream is already live.", "overview"));
        } else if (live >= maxStreams) {
            checks.add(new Check("concurrency", false, "Your " + planName + " plan runs " + maxStreams
                    + " stream(s) at a time and " + live + " are live.", "/pricing"));
        }
        if (user.getPlanType() == PlanType.FREE) {
            if (streamService.freeHoursUsedUp(user)) {
                checks.add(new Check("hours", false, "You've used this month's free streaming hours. Upgrade to keep streaming.", "/pricing"));
            }
            if (jobs.countByIsLiveTrue() >= appConfigService.getGlobalStreamLimit()) {
                checks.add(new Check("capacity", false, "Free capacity is full right now. Try again soon or upgrade.", "/pricing"));
            }
        }
        boolean ok = checks.stream().allMatch(Check::ok);
        return new Preflight(ok, checks);
    }

    /** GL-2: go live. Runs pre-flight; on success a worker picks the run up within seconds. */
    @Transactional
    public StreamJob goLive(String email, Long id, String scene) {
        get(email, id); // ownership first, before taking any lock
        // Plan limits count the user's live runs: serialize this user's starts (and free-tier starts
        // platform-wide), then this stream's, so two simultaneous starts can't both pass the check.
        User owner = users.lockByEmail(email).orElseThrow();
        if (owner.getPlanType() == PlanType.FREE) {
            jobs.lockFreeCapacity();
        }
        streams.lockById(id);
        Preflight pf = preflight(email, id);
        if (!pf.ok()) {
            String first = pf.checks().stream().filter(c -> !c.ok()).map(Check::message).findFirst().orElse("Pre-flight failed.");
            pf.checks().stream().filter(c -> !c.ok() && Set.of("concurrency", "quality", "hours", "capacity", "destinations-limit").contains(c.id()))
                    .findFirst().ifPresent(limit -> notifications.notify(owner, new NotificationService.Alert(
                            NotificationService.Types.PLAN_LIMIT, Notification.Level.INFO, id, "Plan limit reached",
                            limit.message() + " Upgrade to remove this limit.", "/pricing", "plan-limit:" + limit.id(),
                            java.time.Duration.ofDays(1), true)));
            throw new PreflightFailedException(first, pf);
        }
        LiveStream s = get(email, id);
        if (scene != null && !SCENES.contains(scene)) {
            throw new IllegalArgumentException("Unknown scene: " + scene);
        }
        StudioSpec studio = studioSpec(s, scene);
        List<StreamDestination> enabled = enabledDestinations(s);
        List<StreamSpec.Destination> dests = new ArrayList<>();
        for (StreamDestination d : enabled) {
            dests.add(new StreamSpec.Destination(d.getName() == null ? "Destination" : d.getName(), d.getStreamKey()));
        }
        StreamSpec spec = StreamSpec.studio(email, s.getId(), studio, dests);

        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("UTC")).truncatedTo(ChronoUnit.MICROS);
        StreamJob job = new StreamJob(s.getUser(), dests.getFirst().url(), null, null, null, true, 0,
                s.getName(), null, null, now,
                String.join(", ", dests.stream().map(StreamSpec.Destination::name).toList()), false);
        job.setLastBillingTime(now);
        job.setStatus(StreamStates.STARTING);
        job.setDesiredState(StreamStates.RUN);
        job.setSpec(spec.toJson());
        job.setStreamId(s.getId());
        job.setScene(studio.initialScene());
        for (int i = 0; i < dests.size(); i++) {
            job.getDestinations().add(new StreamJobDestination(job, i, dests.get(i).name()));
        }
        job = jobs.save(job);
        log.info("Stream {} going live as job {} ({} destinations)", s.getId(), job.getId(), dests.size());
        return job;
    }

    /** Thrown when going live is blocked; carries every check for the UI. */
    public static class PreflightFailedException extends IllegalStateException {
        private final transient Preflight preflight;

        public PreflightFailedException(String message, Preflight preflight) {
            super(message);
            this.preflight = preflight;
        }

        public Preflight preflight() {
            return preflight;
        }
    }

    /** GL-3: stop now, at a time, or after the playlist's current pass. */
    @Transactional
    public LiveState stop(String email, Long id, StopRequest req) {
        LiveStream s = get(email, id);
        StreamJob job = currentJob(s).orElseThrow(() -> new com.afklive.streamer.exception.ConflictException("This stream isn't live."));
        String mode = req == null || req.mode() == null ? "NOW" : req.mode().toUpperCase(Locale.ROOT);
        switch (mode) {
            case "NOW" -> streamService.stopStream(job.getId(), email);
            case "AT" -> {
                if (req.at() == null) {
                    throw new IllegalArgumentException("Pick a stop time.");
                }
                ZoneId z = zone(req.timeZone() != null ? req.timeZone() : s.getTimeZone());
                ZonedDateTime at;
                try {
                    at = LocalDateTime.parse(req.at()).atZone(z);
                } catch (Exception e) {
                    throw new IllegalArgumentException("Stop time must look like 2026-10-23T22:30.");
                }
                streamService.setStopAt(job.getId(), email, at.withZoneSameInstant(ZoneId.of("UTC")));
            }
            case "AFTER_PLAYLIST" -> {
                if (s.getSource() != LiveStream.Source.PLAYLIST) {
                    throw new IllegalArgumentException("Only playlist streams can stop after the playlist.");
                }
                StreamSpec current = StreamSpec.fromJson(job.getSpec());
                if (current.studio() != null) {
                    jobs.updateSpec(job.getId(), StreamSpec.studio(current.username(), current.streamId(),
                            current.studio().withFinishAfterPass(), current.destinations()).toJson());
                }
                command(job, StreamCommand.Type.FINISH_PASS, null);
            }
            default -> throw new IllegalArgumentException("Stop mode must be NOW, AT or AFTER_PLAYLIST.");
        }
        return liveState(get(email, id));
    }

    @Transactional
    public void cancelScheduledStop(String email, Long id) {
        LiveStream s = get(email, id);
        StreamJob job = currentJob(s).orElseThrow(() -> new com.afklive.streamer.exception.ConflictException("This stream isn't live."));
        streamService.setStopAt(job.getId(), email, null);
    }

    // ------------------------------------------------------------------ live control (MS-5)

    @Transactional
    public void switchScene(String email, Long id, String scene) {
        if (scene == null || !SCENES.contains(scene)) {
            throw new IllegalArgumentException("Unknown scene: " + scene);
        }
        StreamJob job = currentJob(get(email, id)).orElseThrow(() -> new com.afklive.streamer.exception.ConflictException("This stream isn't live."));
        command(job, StreamCommand.Type.SCENE, scene);
    }

    /** Shows or hides an overlay now, and remembers it for the next time the stream goes live. */
    @Transactional
    public void setOverlayVisible(String email, Long id, String overlayId, boolean visible) {
        get(email, id);
        LiveStream s = streams.lockById(id).orElseThrow();
        List<StudioSpec.OverlaySetting> list = new ArrayList<>(overlays(s));
        boolean found = false;
        for (int i = 0; i < list.size(); i++) {
            StudioSpec.OverlaySetting o = list.get(i);
            if (o.id().equals(overlayId)) {
                list.set(i, new StudioSpec.OverlaySetting(o.id(), o.type(), visible, o.x(), o.y(), o.width(), o.size(),
                        o.text(), o.imageMediaId(), o.imageKey(), o.color(), o.background(), o.opacity(), o.timeZone(),
                        o.target(), o.speed(), o.scene()));
                found = true;
            }
        }
        if (!found) {
            throw new IllegalArgumentException("Overlay not found.");
        }
        s.setOverlays(StudioSpec.json(list));
        streams.save(s);
        currentJob(s).ifPresent(job -> {
            persistSpec(job, s, true); // a takeover keeps the toggle
            command(job, StreamCommand.Type.OVERLAY, StudioSpec.json(Map.of("id", overlayId, "visible", visible)));
        });
    }

    @Transactional
    public void skip(String email, Long id) {
        StreamJob job = currentJob(get(email, id)).orElseThrow(() -> new com.afklive.streamer.exception.ConflictException("This stream isn't live."));
        command(job, StreamCommand.Type.SKIP, null);
    }

    public List<RunView> runs(String email, Long id) {
        get(email, id);
        return jobs.findTop20ByStreamIdOrderByStartTimeDesc(id).stream()
                .map(j -> new RunView(j.getId(), j.getStartTime(), j.getEndTime(), j.getStatus(), j.getEndReason(), j.getRestarts()))
                .toList();
    }

    public List<QualityOption> qualityOptions(String email) {
        User user = userService.getOrCreateUser(email);
        int max = planMaxHeight(user);
        String planName = planService.getPlanConfig(user.getPlanType()).getDisplayName();
        List<QualityOption> out = new ArrayList<>();
        for (int h : new int[]{720, 1080, 1440, 2160}) {
            for (int f : new int[]{30, 60}) {
                String reason = null;
                if (h > max) {
                    reason = "Your " + planName + " plan streams up to " + max + "p.";
                } else if (f > planService.getPlanConfig(user.getPlanType()).getMaxFps()) {
                    reason = f + " fps isn't included in your " + planName + " plan.";
                }
                out.add(new QualityOption(h, f, reason == null, reason));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ state

    /** GL-2 states plus RE-3 health, from the stream's current (or last) run. */
    public LiveState liveState(LiveStream s) {
        Optional<StreamJob> live = currentJob(s);
        if (live.isEmpty()) {
            Optional<StreamJob> last = jobs.findFirstByStreamIdOrderByStartTimeDesc(s.getId());
            if (last.isEmpty()) {
                return new LiveState("DRAFT", null, null, 0, null, null, null, null, null, null);
            }
            StreamJob j = last.get();
            String state = StreamStates.FAILED.equals(j.getStatus()) ? "ERROR" : "ENDED";
            return new LiveState(state, j.getId(), j.getStartTime(), 0, null, null, null, null, null, j.getEndReason());
        }
        StreamJob j = live.get();
        String state;
        if (StreamStates.STOP.equals(j.getDesiredState())) {
            state = "STOPPING";
        } else if (StreamStates.STARTING.equals(j.getStatus()) || j.getStatus() == null) {
            state = "STARTING";
        } else {
            state = "LIVE";
        }
        long uptime = j.getStartTime() == null ? 0 : Math.max(0, Duration.between(j.getStartTime(), ZonedDateTime.now()).getSeconds());
        Map<String, Object> np = null;
        if (j.getNowPlaying() != null) {
            try {
                np = StudioSpec.parse(j.getNowPlaying(), new TypeReference<Map<String, Object>>() { });
            } catch (Exception ignored) {
                // stale or partial
            }
        }
        return new LiveState(state, j.getId(), j.getStartTime(), uptime, j.getStopAt(), j.getScene(), j.getInputLive(), np,
                StreamHealth.of(j, s.getQualityHeight(), s.getFps()), null);
    }

    private Optional<StreamJob> currentJob(LiveStream s) {
        return jobs.findAllByStreamIdAndIsLiveTrue(s.getId()).stream().findFirst();
    }

    // ------------------------------------------------------------------ building the run

    /** The worker's instructions, with every library reference resolved to a storage key the owner may use. */
    public StudioSpec studioSpec(LiveStream s, String scene) {
        List<StudioSpec.Media> videos = new ArrayList<>();
        for (StreamItem i : items.findAllByStreamIdAndKindOrderByPositionAsc(s.getId(), StreamItem.Kind.VIDEO)) {
            if (i.getMedia().getStatus() == MediaStatus.READY) {
                videos.add(mediaOf(i.getMedia()));
            }
        }
        List<StudioSpec.Media> music = new ArrayList<>();
        for (StreamItem i : items.findAllByStreamIdAndKindOrderByPositionAsc(s.getId(), StreamItem.Kind.AUDIO)) {
            if (i.getMedia().getStatus() == MediaStatus.READY) {
                music.add(mediaOf(i.getMedia()));
            }
        }
        String email = s.getUser().getEmail();
        List<StudioSpec.OverlaySetting> overlays = new ArrayList<>();
        for (StudioSpec.OverlaySetting o : overlays(s)) {
            String key = null;
            if (o.imageMediaId() != null) {
                key = media.findByIdAndUserEmail(o.imageMediaId(), email).map(MediaAsset::getS3Key).orElse(null);
                if (key == null) {
                    continue; // image was deleted from the library
                }
            }
            overlays.add(new StudioSpec.OverlaySetting(o.id(), o.type(), o.visible(), o.x(), o.y(), o.width(), o.size(),
                    o.text(), o.imageMediaId(), key, o.color(), o.background(), o.opacity(),
                    o.timeZone() != null ? o.timeZone() : s.getTimeZone(), o.target(), o.speed(), o.scene()));
        }
        if (planService.getPlanConfig(s.getUser().getPlanType()).isWatermark()) {
            // BI-1: the Free plan's small mark, added here so it can't be removed from the client.
            overlays.add(new StudioSpec.OverlaySetting(com.afklive.streamer.streaming.studio.OverlayCompositor.WATERMARK_ID, "TEXT", true, 0.8, 0.92, 0, 0.03, "AFK Live",
                    null, null, "#ffffff", "#66000000", 0.8, null, null, 0, null));
        }
        List<StudioSpec.SceneSetting> scenes = new ArrayList<>();
        for (StudioSpec.SceneSetting sc : scenes(s)) {
            String key = null;
            if (sc.backgroundMediaId() != null) {
                key = media.findByIdAndUserEmail(sc.backgroundMediaId(), email).map(MediaAsset::getS3Key).orElse(null);
            }
            scenes.add(new StudioSpec.SceneSetting(sc.name(), key == null ? null : sc.backgroundMediaId(), key,
                    key == null ? "COLOR" : sc.backgroundType(), sc.color(), sc.music()));
        }
        StudioSpec.Position resume = null;
        if (s.getResumePosition() != null) {
            try {
                resume = StudioSpec.parse(s.getResumePosition(), StudioSpec.Position.class);
            } catch (Exception ignored) {
                // start from the top
            }
        }
        return new StudioSpec(s.getQualityHeight(), s.getOrientation() == LiveStream.Orientation.VERTICAL, s.getFps(),
                s.getSource().name(), videos, s.getPlaybackOrder().name(), s.getLoopCount(), music,
                s.getMusicOrder().name(), s.getAudioMode().name(), s.getVideoVolume(), s.getMusicVolume(),
                overlays, scenes, scene != null ? scene : StudioSpec.LIVE, s.getTimeZone(), resume, phone(s), false);
    }

    private StudioSpec.Phone phone(LiveStream s) {
        if (s.getSource() != LiveStream.Source.PHONE || !ingest.enabled()) {
            return null;
        }
        LiveStream withKey = s.getIngestKey() == null ? ingest.ensureKey(s) : s;
        return new StudioSpec.Phone(withKey.getIngestKey(), null, s.isEndOnInputLoss() ? s.getInputGraceSeconds() : 0);
    }

    private static StudioSpec.Media mediaOf(MediaAsset m) {
        return new StudioSpec.Media(String.valueOf(m.getId()), m.getS3Key(), m.getTitle(),
                m.getDurationSeconds() == null ? 0 : m.getDurationSeconds(), m.getHasAudio());
    }

    /** Edits made while live reach the running studio without restarting anything. */
    private void reloadIfLive(LiveStream s) {
        currentJob(s).ifPresent(job -> {
            StudioSpec next = persistSpec(job, s, true);
            command(job, StreamCommand.Type.RELOAD, StudioSpec.json(next));
        });
    }

    /**
     * Writes the stream's current settings into the run's spec, so a worker that restarts or takes the
     * run over builds what the creator sees now, not what it was at go-live. Keeps the run's
     * destinations and any pending "stop after the playlist".
     */
    private StudioSpec persistSpec(StreamJob job, LiveStream s, boolean sceneFromJob) {
        StreamSpec current = StreamSpec.fromJson(job.getSpec());
        StudioSpec next = studioSpec(s, sceneFromJob ? job.getScene() : null);
        if (current.studio() != null && current.studio().finishAfterPass()) {
            next = next.withFinishAfterPass();
        }
        jobs.updateSpec(job.getId(), StreamSpec.studio(current.username(), current.streamId(), next, current.destinations()).toJson());
        return next;
    }

    private void command(StreamJob job, StreamCommand.Type type, String payload) {
        commands.save(new StreamCommand(job.getId(), type, payload));
    }

    private List<StreamDestination> enabledDestinations(LiveStream s) {
        List<StreamDestination> out = new ArrayList<>();
        for (StreamTarget t : targets.findAllByStreamId(s.getId())) {
            if (t.isEnabled()) {
                destinations.findById(t.getDestinationId()).ifPresent(out::add);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ library files in use (ML-6)

    public record MediaUse(Long streamId, String streamName, boolean live) {
    }

    /** For each of these files, the creator's streams that use it (playlist, music, overlay image, scene background). */
    public Map<Long, List<MediaUse>> mediaUses(String email, Collection<Long> mediaIds) {
        Map<Long, List<MediaUse>> out = new HashMap<>();
        if (mediaIds.isEmpty()) {
            return out;
        }
        Set<Long> wanted = new HashSet<>(mediaIds);
        for (LiveStream s : streams.findAllByUserEmailOrderByCreatedAtAsc(email)) {
            Set<Long> used = new HashSet<>();
            for (StreamItem i : items.findAllByStreamIdOrderByPositionAsc(s.getId())) {
                used.add(i.getMedia().getId());
            }
            for (StudioSpec.OverlaySetting o : overlays(s)) {
                if (o.imageMediaId() != null) {
                    used.add(o.imageMediaId());
                }
            }
            if (s.getScenes() != null && !s.getScenes().isBlank()) {
                for (StudioSpec.SceneSetting sc : scenes(s)) {
                    if (sc.backgroundMediaId() != null) {
                        used.add(sc.backgroundMediaId());
                    }
                }
            }
            used.retainAll(wanted);
            if (used.isEmpty()) {
                continue;
            }
            MediaUse use = new MediaUse(s.getId(), s.getName(), currentJob(s).isPresent());
            for (Long id : used) {
                out.computeIfAbsent(id, k -> new ArrayList<>()).add(use);
            }
        }
        return out;
    }

    /**
     * Removes a file from the creator's streams that aren't live (playlist entries go with the file via
     * the database; overlay images and scene backgrounds are cleared here).
     */
    @Transactional
    public void forgetMedia(String email, Long mediaId) {
        for (LiveStream s : streams.findAllByUserEmailOrderByCreatedAtAsc(email)) {
            boolean changed = false;
            List<StudioSpec.OverlaySetting> ov = new ArrayList<>();
            for (StudioSpec.OverlaySetting o : overlays(s)) {
                if (mediaId.equals(o.imageMediaId())) {
                    changed = true;
                    continue; // an image overlay without its image is removed
                }
                ov.add(o);
            }
            List<StudioSpec.SceneSetting> sc = new ArrayList<>();
            boolean hasScenes = s.getScenes() != null && !s.getScenes().isBlank();
            if (hasScenes) {
                for (StudioSpec.SceneSetting x : scenes(s)) {
                    if (mediaId.equals(x.backgroundMediaId())) {
                        changed = true;
                        sc.add(new StudioSpec.SceneSetting(x.name(), null, null, null, x.color(), x.music()));
                    } else {
                        sc.add(x);
                    }
                }
            }
            if (changed) {
                s.setOverlays(StudioSpec.json(ov));
                if (hasScenes) {
                    s.setScenes(StudioSpec.json(sc));
                }
                s.setUpdatedAt(java.time.ZonedDateTime.now());
                streams.save(s);
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    List<StudioSpec.OverlaySetting> overlays(LiveStream s) {
        if (s.getOverlays() == null || s.getOverlays().isBlank()) {
            return List.of();
        }
        return StudioSpec.parse(s.getOverlays(), new TypeReference<List<StudioSpec.OverlaySetting>>() { });
    }

    List<StudioSpec.SceneSetting> scenes(LiveStream s) {
        if (s.getScenes() == null || s.getScenes().isBlank()) {
            return defaultScenes();
        }
        return StudioSpec.parse(s.getScenes(), new TypeReference<List<StudioSpec.SceneSetting>>() { });
    }

    private List<MediaRef> refs(List<StreamItem> list) {
        return list.stream().map(i -> {
            MediaAsset m = i.getMedia();
            return new MediaRef(m.getId(), m.getTitle(), m.getDurationSeconds(), m.getWidth(), m.getHeight(),
                    m.getContentType().name(), m.getStatus().name());
        }).toList();
    }

    private List<ScheduleView> scheduleViews(LiveStream s) {
        return schedules.findAllByStreamIdOrderByIdAsc(s.getId()).stream().map(StreamScheduleService::view).toList();
    }

    private int planMaxHeight(User user) {
        int max = planService.getPlanConfig(user.getPlanType()).getMaxResolution();
        return max <= 0 ? 720 : max;
    }

    private void checkQuality(User user, int height, int fps) {
        if (height != 720 && height != 1080 && height != 1440 && height != 2160) {
            throw new IllegalArgumentException("Quality must be 720p, 1080p, 1440p or 2160p.");
        }
        if (fps != 30 && fps != 60) {
            throw new IllegalArgumentException("Frame rate must be 30 or 60.");
        }
        int max = planMaxHeight(user);
        String planName = planService.getPlanConfig(user.getPlanType()).getDisplayName();
        if (height > max) {
            throw new IllegalArgumentException("Your " + planName + " plan streams up to " + max + "p. Upgrade for " + height + "p.");
        }
        if (fps > planService.getPlanConfig(user.getPlanType()).getMaxFps()) {
            throw new IllegalArgumentException(fps + " fps isn't included in your " + planName + " plan. Upgrade for " + fps + " fps.");
        }
    }

    private static String name(String n) {
        String v = n == null ? "" : n.trim();
        if (v.isEmpty() || v.length() > 100) {
            throw new IllegalArgumentException("Give the stream a name (up to 100 characters).");
        }
        return v;
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, String what) {
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid " + what + ": " + value);
        }
    }

    static ZoneId zone(String z) {
        try {
            return ZoneId.of(z);
        } catch (Exception e) {
            throw new IllegalArgumentException("Unknown time zone: " + z);
        }
    }

    private static double volume(double v) {
        if (Double.isNaN(v) || v < 0 || v > 2) {
            throw new IllegalArgumentException("Volume must be between 0 and 2.");
        }
        return v;
    }

    private static double fraction(double v) {
        return Double.isNaN(v) ? 0 : Math.max(0, Math.min(1, v));
    }

    private static String color(String c) {
        if (c == null || c.isBlank()) {
            return null;
        }
        if (!COLOR.matcher(c).matches()) {
            throw new IllegalArgumentException("Colours look like #RRGGBB or #AARRGGBB.");
        }
        return c.startsWith("#") ? c : "#" + c;
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v;
    }
}
