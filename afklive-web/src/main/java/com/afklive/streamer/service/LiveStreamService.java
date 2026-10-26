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
    private final com.afklive.streamer.repository.StreamIncidentRepository incidents;
    private final CapacityEstimator capacity;
    private final com.afklive.streamer.repository.StreamEventRepository events;
    private final DestinationTestService destinationTests;
    /** FO-4: the live video bitrate per plan; resolved once per run at go-live and recorded in its spec. */
    private final VideoBitrates videoBitrates;

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
                        : null,
                s.getFit(), s.getCrossfadeSeconds());
    }

    static final int MAX_CROSSFADE_SECONDS = 20;

    /** PL-6 / AU-3: live changes apply from the next item or track, without a drop. */
    @Transactional
    public void setPresentation(String email, Long id, com.afklive.streamer.dto.StreamDtos.Presentation req) {
        LiveStream s = get(email, id);
        if (req.fit() != null) {
            s.setFit(fitName(req.fit()));
        }
        if (req.crossfadeSeconds() != null) {
            if (req.crossfadeSeconds() < 0 || req.crossfadeSeconds() > MAX_CROSSFADE_SECONDS) {
                throw new IllegalArgumentException("Crossfade is 0 to " + MAX_CROSSFADE_SECONDS + " seconds.");
            }
            s.setCrossfadeSeconds(req.crossfadeSeconds());
        }
        streams.save(s);
        reloadIfLive(s);
    }

    /** PL-6: one playlist item's own fit; null or "" follows the stream. */
    @Transactional
    public void setItemFit(String email, Long id, int position, String fit) {
        LiveStream s = get(email, id);
        StreamItem item = items.findAllByStreamIdAndKindOrderByPositionAsc(id, StreamItem.Kind.VIDEO).stream()
                .filter(i -> i.getPosition() == position).findFirst()
                .orElseThrow(() -> new com.afklive.streamer.exception.NotFoundException("That video isn't in the playlist."));
        item.setFit(fit == null || fit.isBlank() ? null : fitName(fit));
        items.save(item);
        reloadIfLive(s);
    }

    private static String fitName(String fit) {
        var f = com.afklive.streamer.streaming.studio.DecoderSource.Fit.parse(fit);
        if (f == null) {
            throw new IllegalArgumentException("Fit is FIT, FILL or BLUR.");
        }
        return f.name();
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

    /**
     * ST-4: a copy to start a similar channel from: name, picture settings, playlist, music, audio,
     * overlays and scenes. Destinations come along switched off (two streams on one key would fight)
     * and schedules stay behind; a phone stream gets its own new ingest key.
     */
    @Transactional
    public LiveStream duplicate(String email, Long id) {
        LiveStream src = get(email, id);
        String base = src.getName().length() > 92 ? src.getName().substring(0, 92) : src.getName();
        LiveStream copy = create(email, new CreateStream(base + " (copy)", src.getSource().name(), src.getOrientation().name(),
                src.getQualityHeight(), src.getFps()));
        copy.setPlaybackOrder(src.getPlaybackOrder());
        copy.setLoopCount(src.getLoopCount());
        copy.setMusicOrder(src.getMusicOrder());
        copy.setAudioMode(src.getAudioMode());
        copy.setVideoVolume(src.getVideoVolume());
        copy.setMusicVolume(src.getMusicVolume());
        copy.setOverlays(src.getOverlays());
        copy.setScenes(src.getScenes());
        copy.setTimeZone(src.getTimeZone());
        copy.setEndOnInputLoss(src.isEndOnInputLoss());
        copy.setInputGraceSeconds(src.getInputGraceSeconds());
        copy.setSrtLatencyMs(src.getSrtLatencyMs());
        copy.setFit(src.getFit());
        copy.setCrossfadeSeconds(src.getCrossfadeSeconds());
        copy = streams.save(copy);
        for (StreamItem.Kind kind : StreamItem.Kind.values()) {
            for (StreamItem i : items.findAllByStreamIdAndKindOrderByPositionAsc(src.getId(), kind)) {
                StreamItem c = new StreamItem(copy.getId(), kind, i.getPosition(), i.getMedia());
                c.setFit(i.getFit());
                items.save(c);
            }
        }
        for (StreamTarget t : targets.findAllByStreamId(src.getId())) {
            targets.save(new StreamTarget(copy.getId(), t.getDestinationId(), false));
        }
        return copy;
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
        // PL-6: an item keeps its own fit through reorders and additions.
        Map<Long, java.util.Deque<String>> fits = new HashMap<>();
        for (StreamItem old : items.findAllByStreamIdAndKindOrderByPositionAsc(id, kind)) {
            fits.computeIfAbsent(old.getMedia().getId(), k -> new java.util.ArrayDeque<>()).add(old.getFit() == null ? "" : old.getFit());
        }
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
            StreamItem item = new StreamItem(id, kind, pos++, m);
            java.util.Deque<String> kept = fits.get(mid);
            String fit = kept == null ? null : kept.poll();
            item.setFit(fit == null || fit.isEmpty() ? null : fit);
            next.add(item);
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
        return preflight(email, id, null);
    }

    public Preflight preflight(String email, Long id, List<Long> playlist) {
        LiveStream s = get(email, id);
        User user = s.getUser();
        List<Check> checks = new ArrayList<>();

        if (s.getSource() == LiveStream.Source.PLAYLIST) {
            List<MediaAsset> videos = playlist == null || playlist.isEmpty()
                    ? items.findAllByStreamIdAndKindOrderByPositionAsc(id, StreamItem.Kind.VIDEO).stream().map(StreamItem::getMedia).toList()
                    : slotVideos(email, String.join(",", playlist.stream().map(String::valueOf).toList()));
            long ready = videos.stream().filter(m -> m.getStatus() == MediaStatus.READY).count();
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
        // GL-7: the plan's top quality isn't the server's capacity. A quality that won't keep up right now
        // is a warning with a lighter suggestion; only one no server could ever run fails the check.
        CapacityEstimator.Estimate estimate = capacity.estimate(new CapacityEstimator.Quality(s.getQualityHeight(), s.getFps()),
                playsByCopy(s, playlist), allowedQualities(user));
        if (estimate.impossible()) {
            checks.add(new Check("server-capacity", false, estimate.message(), "settings"));
        }

        int live = (int) jobs.countByUserEmailAndIsLiveTrue(email);
        int maxStreams = planService.getPlanConfig(user.getPlanType()).getMaxActiveStreams();
        List<StreamJob> running = jobs.findAllByStreamIdAndIsLiveTrue(id);
        if (!running.isEmpty()) {
            boolean stopping = running.stream().allMatch(j -> StreamStates.STOP.equals(j.getDesiredState()));
            checks.add(new Check("running", false, stopping ? "This stream is still stopping. Try again in a few seconds."
                    : "This stream is already live.", "overview"));
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
        return new Preflight(ok, checks, estimate.freeCpus() == null ? null : estimate);
    }

    /** Qualities the creator's plan includes, for GL-7's lighter suggestion. */
    private List<CapacityEstimator.Quality> allowedQualities(User user) {
        int max = planMaxHeight(user);
        int maxFps = planService.getPlanConfig(user.getPlanType()).getMaxFps();
        List<CapacityEstimator.Quality> out = new ArrayList<>();
        for (int h : new int[]{720, 1080, 1440, 2160}) {
            for (int f : new int[]{30, 60}) {
                if (h <= max && f <= maxFps) {
                    out.add(new CapacityEstimator.Quality(h, f));
                }
            }
        }
        return out;
    }

    /** FO-2: whether this run would play by stream copy (nothing drawn or mixed live), for GL-7's estimate. */
    private boolean playsByCopy(LiveStream s, List<Long> playlist) {
        if (s.getSource() != LiveStream.Source.PLAYLIST) {
            return false;
        }
        try {
            String override = playlist == null || playlist.isEmpty() ? null
                    : String.join(",", playlist.stream().map(String::valueOf).toList());
            return com.afklive.streamer.streaming.CopyRuns.ineligible(studioSpec(s, null, override), null) == null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** GL-2: go live by hand, past any GL-7 capacity warning (see {@link #goLive(String, Long, String, boolean)}). */
    @Transactional
    public StreamJob goLive(String email, Long id, String scene) {
        return goLive(email, id, scene, true);
    }

    /**
     * GL-2: go live by hand. Runs pre-flight; on success a worker picks the run up within seconds.
     * Idempotent per stream: a second request while the stream's run is starting or live (a double tap)
     * returns that run instead of creating another. GL-7: unless {@code anyway}, a quality the servers
     * can't keep up with right now is refused with a {@link CapacityWarningException} carrying the estimate.
     */
    @Transactional
    public StreamJob goLive(String email, Long id, String scene, boolean anyway) {
        return start(email, id, scene, null, true, !anyway);
    }

    /** A scheduled start (SC-1, SC-4): {@code playlist} is the slot's own videos (null: the stream's playlist). */
    @Transactional
    public StreamJob goLive(String email, Long id, String scene, List<Long> playlist) {
        return start(email, id, scene, playlist, false, false);
    }

    /** The stream's live run, if it has one (e.g. the run a double tap already started). */
    public Optional<StreamJob> liveRun(String email, Long id) {
        return currentJob(get(email, id));
    }

    private StreamJob start(String email, Long id, String scene, List<Long> playlist, boolean returnExisting, boolean warnCapacity) {
        get(email, id); // ownership first, before taking any lock
        // Plan limits count the user's live runs: serialize this user's starts (and free-tier starts
        // platform-wide), then this stream's, so two simultaneous starts can't both pass the check.
        User owner = users.lockByEmail(email).orElseThrow();
        if (owner.getDeletionScheduledFor() != null) {
            // ON-7: a closed account (deletion scheduled) can't go live, by hand or on a schedule.
            throw new com.afklive.streamer.exception.ConflictException("This account is scheduled for deletion, so streams can't start. Keep the account to stream again.");
        }
        if (owner.getPlanType() == PlanType.FREE) {
            jobs.lockFreeCapacity();
        }
        streams.lockById(id);
        if (returnExisting) {
            Optional<StreamJob> existing = jobs.findAllByStreamIdAndIsLiveTrue(id).stream()
                    .filter(j -> !StreamStates.STOP.equals(j.getDesiredState())).findFirst();
            if (existing.isPresent()) {
                log.info("Stream {} is already live as job {}: returning that run", id, existing.get().getId());
                return existing.get();
            }
        }
        Preflight pf = preflight(email, id, playlist);
        if (!pf.ok()) {
            String first = pf.checks().stream().filter(c -> !c.ok()).map(Check::message).findFirst().orElse("Pre-flight failed.");
            pf.checks().stream().filter(c -> !c.ok() && Set.of("concurrency", "quality", "hours", "capacity", "destinations-limit").contains(c.id()))
                    .findFirst().ifPresent(limit -> notifications.notify(owner, new NotificationService.Alert(
                            NotificationService.Types.PLAN_LIMIT, Notification.Level.INFO, id, "Plan limit reached",
                            limit.message() + " Upgrade to remove this limit.", "/pricing", "plan-limit:" + limit.id(),
                            java.time.Duration.ofDays(1), true)));
            throw new PreflightFailedException(first, pf);
        }
        if (warnCapacity && pf.capacity() != null && !pf.capacity().fits()) {
            throw new CapacityWarningException(pf.capacity().message(), pf);
        }
        LiveStream s = get(email, id);
        if (scene != null && !SCENES.contains(scene)) {
            throw new IllegalArgumentException("Unknown scene: " + scene);
        }
        String override = playlist == null || playlist.isEmpty() ? null
                : String.join(",", playlist.stream().map(String::valueOf).toList());
        // FO-4: the bitrate for the owner's plan now, recorded in the run's spec: every rebuild of the spec keeps it
        // (runSpec), so neither a plan change nor a new setting changes a running stream.
        StudioSpec studio = studioSpec(s, scene, override)
                .withVideoKbps(videoBitrates.videoKbps(s.getUser().getPlanType(), s.getQualityHeight(), s.getFps()));
        List<StreamDestination> enabled = enabledDestinations(s);
        List<StreamSpec.Destination> dests = new ArrayList<>();
        for (StreamDestination d : enabled) {
            dests.add(new StreamSpec.Destination(d.getName() == null ? "Destination" : d.getName(), d.getStreamKey()));
        }
        StreamSpec spec = StreamSpec.studio(email, s.getId(), studio, dests);

        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("UTC")).truncatedTo(ChronoUnit.MICROS);
        StreamJob job = new StreamJob(s.getUser(), dests.getFirst().url(), null, null, null, true, 0,
                s.getName(), null, null, now,
                String.join(", ", dests.stream().map(StreamSpec.Destination::name).toList()));
        job.setLastBillingTime(now);
        job.setStatus(StreamStates.STARTING);
        job.setDesiredState(StreamStates.RUN);
        job.setSpec(spec.toJson());
        job.setPlacement(com.afklive.streamer.streaming.WorkerPools.placementOf(spec)); // FO-8: which workers may claim it
        job.setStreamId(s.getId());
        job.setScene(studio.initialScene());
        job.setPlaylistOverride(override);
        for (int i = 0; i < dests.size(); i++) {
            job.getDestinations().add(new StreamJobDestination(job, i, dests.get(i).name()));
        }
        // Flushed here so a second live run of this stream (refused by uq_stream_jobs_one_live_per_stream,
        // whatever path created the first) fails in this call, where the caller can return the existing run.
        job = jobs.saveAndFlush(job);
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

    /**
     * GL-7: everything else is ready, but by the estimate the servers can't keep up with this quality
     * right now. The creator can take the suggested quality or go live anyway.
     */
    public static class CapacityWarningException extends PreflightFailedException {
        public CapacityWarningException(String message, Preflight preflight) {
            super(message, preflight);
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

    /**
     * GL-3: takes back "stop after this playlist pass". The run's spec loses the flag (so a worker that
     * restarts or takes the run over keeps going), and the running studio gets the playlist again, which
     * resets the pass count the flag had capped (PlaylistFeed.setItems). Nothing restarts and no
     * destination disconnects.
     */
    @Transactional
    public LiveState cancelFinishAfterPass(String email, Long id) {
        LiveStream s = get(email, id);
        StreamJob job = currentJob(s).orElseThrow(() -> new com.afklive.streamer.exception.ConflictException("This stream isn't live."));
        StreamSpec current = StreamSpec.fromJson(job.getSpec());
        if (current.studio() != null && current.studio().finishAfterPass()) {
            StudioSpec next = keepRunBitrate(studioSpec(s, job.getScene(), job.getPlaylistOverride()), current);
            String spec = StreamSpec.studio(current.username(), current.streamId(), next, current.destinations()).toJson();
            if (jobs.updateSpec(job.getId(), spec) != 1) {
                throw new com.afklive.streamer.exception.ConflictException("This stream isn't live.");
            }
            command(job, StreamCommand.Type.RELOAD, StudioSpec.json(next));
        }
        return liveState(get(email, id));
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
    /** GL-5: redial every destination; the picture and the playlist carry on. */
    public void reconnect(String email, Long id) {
        StreamJob job = currentJob(get(email, id)).orElseThrow(() -> new com.afklive.streamer.exception.ConflictException("This stream isn't live."));
        command(job, StreamCommand.Type.RECONNECT, null);
    }

    public void skip(String email, Long id) {
        StreamJob job = currentJob(get(email, id)).orElseThrow(() -> new com.afklive.streamer.exception.ConflictException("This stream isn't live."));
        command(job, StreamCommand.Type.SKIP, null);
    }

    /**
     * GL-1: each enabled destination's test before going live. With {@code start}, destinations that
     * haven't passed a test in 24 hours with their current key get one; fresh ones cost no wait at all.
     */
    public DestinationChecks destinationChecks(String email, Long id, boolean start) {
        LiveStream s = get(email, id);
        List<DestinationTestService.Check> checks = destinationTests.goLiveChecks(s.getUser(), s.getId(), enabledDestinations(s), start);
        boolean ready = checks.stream().allMatch(c -> DestinationTestService.PASSED.equals(c.state())
                || DestinationTestService.SKIPPED.equals(c.state()));
        return new DestinationChecks(ready, checks);
    }

    /** RE-9: what happened to the destinations of the stream's recent runs, newest first. */
    public List<TimelineEvent> timeline(String email, Long id) {
        get(email, id);
        List<Long> runIds = jobs.findTop20ByStreamIdOrderByStartTimeDesc(id).stream().map(StreamJob::getId).toList();
        if (runIds.isEmpty()) {
            return List.of();
        }
        return events.findRecent(runIds, org.springframework.data.domain.PageRequest.of(0, 100)).stream()
                .map(e -> new TimelineEvent(e.getId(), e.getJobId(), e.getAt(), e.getLastAt(), e.getKind(), e.getName(),
                        e.getMessage(), e.getRepeats(), switch (e.getKind()) {
                            case StreamEvent.Kinds.REACHED, StreamEvent.Kinds.RECOVERED -> StreamHealth.GREEN;
                            case StreamEvent.Kinds.RECONNECTING -> StreamHealth.AMBER;
                            default -> StreamHealth.RED;
                        }))
                .toList();
    }

    public List<RunView> runs(String email, Long id) {
        get(email, id);
        return jobs.findTop20ByStreamIdOrderByStartTimeDesc(id).stream()
                .map(j -> new RunView(j.getId(), j.getStartTime(), j.getEndTime(), j.getStatus(), j.getEndReason(), j.getRestarts()))
                .toList();
    }

    /**
     * RE-4: for the last 24 hours, 7 and 30 days, how much of the time the stream was on air it was
     * also up. "Down" is an outage the alert monitor recorded (down for a minute or more); shorter
     * hiccups don't count, the same line the alerts use.
     */
    public com.afklive.streamer.dto.StreamDtos.Uptime uptime(String email, Long id, ZonedDateTime now) {
        get(email, id);
        List<StreamJob> runs = jobs.findRunsSince(id, now.minusDays(30));
        Map<Long, StreamJob> byId = new HashMap<>();
        runs.forEach(j -> byId.put(j.getId(), j));
        List<com.afklive.streamer.model.StreamIncident> downs = byId.isEmpty() ? List.of()
                : incidents.findAllByJobIdInAndKindInOrderByStartedAtDesc(byId.keySet(), AlertMonitor.OUTAGE_KINDS);
        List<com.afklive.streamer.dto.StreamDtos.UptimeWindow> windows = new ArrayList<>();
        for (Object[] w : new Object[][]{{"24 hours", now.minusHours(24)}, {"7 days", now.minusDays(7)}, {"30 days", now.minusDays(30)}}) {
            ZonedDateTime from = (ZonedDateTime) w[1];
            long onAir = 0;
            for (StreamJob j : runs) {
                onAir += overlap(j.getStartTime(), j.getEndTime(), from, now);
            }
            long down = 0;
            for (var inc : downs) {
                StreamJob j = byId.get(inc.getJobId());
                ZonedDateTime end = inc.getResolvedAt() != null ? inc.getResolvedAt() : j != null && j.getEndTime() != null ? j.getEndTime() : now;
                down += overlap(inc.getStartedAt(), end, from, now);
            }
            down = Math.min(down, onAir);
            Double pct = onAir == 0 ? null : Math.floor(1000.0 * (onAir - down) / onAir) / 10.0;
            windows.add(new com.afklive.streamer.dto.StreamDtos.UptimeWindow((String) w[0], onAir, down, pct));
        }
        List<com.afklive.streamer.dto.StreamDtos.Outage> outages = downs.stream().limit(20)
                .map(i -> new com.afklive.streamer.dto.StreamDtos.Outage(i.getStartedAt(), i.getResolvedAt())).toList();
        return new com.afklive.streamer.dto.StreamDtos.Uptime(windows, outages);
    }

    private static long overlap(ZonedDateTime start, ZonedDateTime end, ZonedDateTime from, ZonedDateTime to) {
        if (start == null) {
            return 0;
        }
        ZonedDateTime a = start.isAfter(from) ? start : from;
        ZonedDateTime b = end == null || end.isAfter(to) ? to : end;
        return Math.max(0, java.time.Duration.between(a, b).getSeconds());
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
        // RE-10: running slower than real time for a minute or more (opened by the alert monitor).
        ZonedDateTime slowSince = incidents.findAllByJobIdAndResolvedAtIsNull(j.getId()).stream()
                .filter(i -> AlertMonitor.SLOW.equals(i.getKind())).map(com.afklive.streamer.model.StreamIncident::getStartedAt)
                .findFirst().orElse(null);
        // RE-8: whether the platforms are receiving it.
        String reach = "LIVE".equals(state) ? StreamHealth.reach(j.getDestinations()) : null;
        return new LiveState(state, j.getId(), j.getStartTime(), uptime, j.getStopAt(), j.getScene(), j.getInputLive(), np,
                StreamHealth.of(j, s.getQualityHeight(), s.getFps()), null, slowSince, reach, finishAfterPass(j));
    }

    /** GL-3: whether "stop after this playlist pass" is armed on this run (it lives in the run's spec). */
    private static boolean finishAfterPass(StreamJob j) {
        try {
            StreamSpec spec = j.getSpec() == null ? null : StreamSpec.fromJson(j.getSpec());
            return spec != null && spec.studio() != null && spec.studio().finishAfterPass();
        } catch (Exception e) {
            return false;
        }
    }

    private Optional<StreamJob> currentJob(LiveStream s) {
        return jobs.findAllByStreamIdAndIsLiveTrue(s.getId()).stream().findFirst();
    }

    // ------------------------------------------------------------------ building the run

    /** The worker's instructions, with every library reference resolved to a storage key the owner may use. */
    public StudioSpec studioSpec(LiveStream s, String scene) {
        return studioSpec(s, scene, null);
    }

    /** SC-4: the owner's video files for a slot's list ("12,7,9"), in that order; others are left out. */
    List<MediaAsset> slotVideos(String email, String ids) {
        List<Long> wanted = new ArrayList<>();
        for (String p : ids.split(",")) {
            try {
                wanted.add(Long.parseLong(p.trim()));
            } catch (NumberFormatException ignored) {
                // skip
            }
        }
        Map<Long, MediaAsset> owned = new HashMap<>();
        for (MediaAsset m : media.findAllByIdInAndUserEmail(new HashSet<>(wanted), email)) {
            if (m.getContentType() == MediaAsset.ContentType.VIDEO) {
                owned.put(m.getId(), m);
            }
        }
        return wanted.stream().map(owned::get).filter(Objects::nonNull).toList();
    }

    StudioSpec studioSpec(LiveStream s, String scene, String playlistOverride) {
        List<StudioSpec.Media> videos = new ArrayList<>();
        if (playlistOverride != null) {
            for (MediaAsset m : slotVideos(s.getUser().getEmail(), playlistOverride)) {
                if (m.getStatus() == MediaStatus.READY) {
                    videos.add(mediaOf(m));
                }
            }
        } else {
            for (StreamItem i : items.findAllByStreamIdAndKindOrderByPositionAsc(s.getId(), StreamItem.Kind.VIDEO)) {
                if (i.getMedia().getStatus() == MediaStatus.READY) {
                    videos.add(mediaOf(i.getMedia(), i.getFit()));
                }
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
        if (s.getResumePosition() != null && playlistOverride == null) { // a slot's own list starts at the top
            try {
                resume = StudioSpec.parse(s.getResumePosition(), StudioSpec.Position.class);
            } catch (Exception ignored) {
                // start from the top
            }
        }
        return new StudioSpec(s.getQualityHeight(), s.getOrientation() == LiveStream.Orientation.VERTICAL, s.getFps(),
                s.getSource().name(), videos, s.getPlaybackOrder().name(), s.getLoopCount(), music,
                s.getMusicOrder().name(), s.getAudioMode().name(), s.getVideoVolume(), s.getMusicVolume(),
                overlays, scenes, scene != null ? scene : StudioSpec.LIVE, s.getTimeZone(), resume, phone(s), false,
                s.getFit(), s.getCrossfadeSeconds());
    }

    private StudioSpec.Phone phone(LiveStream s) {
        if (s.getSource() != LiveStream.Source.PHONE || !ingest.enabled()) {
            return null;
        }
        LiveStream withKey = s.getIngestKey() == null ? ingest.ensureKey(s) : s;
        return new StudioSpec.Phone(withKey.getIngestKey(), null, s.isEndOnInputLoss() ? s.getInputGraceSeconds() : 0);
    }

    private static StudioSpec.Media mediaOf(MediaAsset m) {
        return mediaOf(m, null);
    }

    private static StudioSpec.Media mediaOf(MediaAsset m, String fit) {
        return new StudioSpec.Media(String.valueOf(m.getId()), m.getS3Key(), m.getTitle(),
                m.getDurationSeconds() == null ? 0 : m.getDurationSeconds(), m.getHasAudio(), fit, gainFor(m.getLoudnessLufs()));
    }

    /** AU-5: every file is brought to YouTube's playback loudness; no file is boosted or cut by more than 12 dB. */
    static final double TARGET_LUFS = -14;

    static double gainFor(Double lufs) {
        if (lufs == null) {
            return 0;
        }
        double g = TARGET_LUFS - lufs;
        return Math.round(Math.max(-12, Math.min(12, g)) * 10) / 10.0;
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
        StudioSpec next = keepRunBitrate(studioSpec(s, sceneFromJob ? job.getScene() : null, job.getPlaylistOverride()), current);
        if (current.studio() != null && current.studio().finishAfterPass()) {
            next = next.withFinishAfterPass();
        }
        jobs.updateSpec(job.getId(), StreamSpec.studio(current.username(), current.streamId(), next, current.destinations()).toJson());
        return next;
    }

    /** FO-4: a rebuilt spec keeps the bitrate the run went live with (none recorded: the built-in ladder's, as before). */
    static StudioSpec keepRunBitrate(StudioSpec next, StreamSpec current) {
        return next.withVideoKbps(current.studio() == null ? null : current.studio().videoKbps());
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

    // ------------------------------------------------------------------ destinations in live use (DS-8)

    /**
     * For each of the creator's destinations that a live run is sending to now, the names of those
     * streams: the destinations switched on for a live stream (DS-5 keeps the run in step with them),
     * plus any whose address is in the run's own list (runs keep a copy of each address in their spec;
     * this also covers runs without a saved stream). Destinations turned off for the run are left out.
     */
    public Map<Long, List<String>> destinationsLive(String email) {
        Map<Long, List<String>> out = new HashMap<>();
        List<StreamJob> live = jobs.findAllByUserEmailAndIsLiveTrue(email);
        if (live.isEmpty()) {
            return out;
        }
        List<StreamDestination> mine = destinations.findByUser(live.getFirst().getUser());
        for (StreamJob j : live) {
            List<StreamSpec.Destination> sending;
            try {
                StreamSpec spec = j.getSpec() == null ? null : StreamSpec.fromJson(j.getSpec());
                sending = spec == null || spec.destinations() == null ? List.of() : spec.destinations();
            } catch (Exception e) {
                sending = List.of();
            }
            Set<Long> on = new HashSet<>();
            if (j.getStreamId() != null) {
                targets.findAllByStreamId(j.getStreamId()).stream().filter(StreamTarget::isEnabled)
                        .forEach(t -> on.add(t.getDestinationId()));
            }
            String name = j.getStreamId() == null ? j.getTitle()
                    : streams.findById(j.getStreamId()).map(LiveStream::getName).orElse(j.getTitle());
            final List<StreamSpec.Destination> list = sending;
            for (StreamDestination d : mine) {
                boolean used = on.contains(d.getId())
                        || list.stream().anyMatch(x -> !x.off() && java.util.Objects.equals(x.url(), d.getStreamKey()));
                if (used) {
                    out.computeIfAbsent(d.getId(), k -> new ArrayList<>()).add(name == null ? "a stream" : name);
                }
            }
        }
        return out;
    }

    /**
     * Deletes a destination unless a live run is sending to it. Taken under the same user lock as
     * going live, so a go-live can't start sending to it between the check and the delete.
     */
    @Transactional
    public void deleteDestination(String email, Long id) {
        users.lockByEmail(email).orElseThrow(() -> new com.afklive.streamer.exception.NotFoundException("Account not found."));
        StreamDestination d = destinations.findByIdAndUserEmail(id, email)
                .orElseThrow(() -> new com.afklive.streamer.exception.NotFoundException("Destination not found."));
        List<String> live = destinationsLive(email).get(d.getId());
        if (live != null && !live.isEmpty()) {
            throw new com.afklive.streamer.exception.ConflictException(d.getName() + " is sending to a live stream (" + String.join(", ", live)
                    + "). Stop the stream or turn this destination off for it first.");
        }
        destinations.delete(d);
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
                    m.getContentType().name(), m.getStatus().name(), i.getFit(), m.getFps());
        }).toList();
    }

    private List<ScheduleView> scheduleViews(LiveStream s) {
        String email = s.getUser().getEmail();
        return schedules.findAllByStreamIdOrderByIdAsc(s.getId()).stream()
                .map(sc -> StreamScheduleService.view(sc, sc.getPlaylist() == null ? List.of()
                        : slotVideos(email, sc.getPlaylist()).stream()
                        .map(m -> new com.afklive.streamer.dto.StreamDtos.SlotItem(m.getId(), m.getTitle())).toList()))
                .toList();
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
