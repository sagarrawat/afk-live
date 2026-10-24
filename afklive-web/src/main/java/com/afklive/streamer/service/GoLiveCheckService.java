package com.afklive.streamer.service;

import com.afklive.streamer.model.LiveStream;
import com.afklive.streamer.model.MediaAsset;
import com.afklive.streamer.model.MediaStatus;
import com.afklive.streamer.model.StreamItem;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.EngagementActivityRepository;
import com.afklive.streamer.repository.LiveStreamRepository;
import com.afklive.streamer.repository.MediaAssetRepository;
import com.afklive.streamer.repository.StreamItemRepository;
import com.afklive.streamer.repository.UserRepository;
import com.afklive.streamer.streaming.StudioSpec;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * GR-9: the go-live check for one stream, shown as a card on the stream page and sent once a week.
 * It never blocks going live: every flag can be fixed in one tap or kept (dismissed).
 */
@Service
@Slf4j
public class GoLiveCheckService {

    /** Phone size for the thumbnail check: YouTube's small thumbnails on phones (search, up next). */
    static final int PHONE_W = 168;
    static final int PHONE_H = 94;

    private final LiveStreamService streamService;
    private final LiveStreamRepository streams;
    private final StreamItemRepository items;
    private final MediaAssetRepository media;
    private final EngagementActivityRepository engagement;
    private final UserRepository users;
    private final FileStorageService storage;
    /** Thumbnail contrast by media id: a library file never changes, so each is measured once per instance. */
    private final Map<Long, Double> contrastCache = new ConcurrentHashMap<>();

    public GoLiveCheckService(LiveStreamService streamService, LiveStreamRepository streams, StreamItemRepository items,
                              MediaAssetRepository media, EngagementActivityRepository engagement, UserRepository users,
                              FileStorageService storage) {
        this.streamService = streamService;
        this.streams = streams;
        this.items = items;
        this.media = media;
        this.engagement = engagement;
        this.users = users;
        this.storage = storage;
    }

    public record FlagView(String id, String kind, String title, String detail, String fix, String fixLabel, String suggestion,
                           String help, boolean dismissed) {
    }

    public record ThumbnailView(Long mediaId, String title, String url) {
    }

    /**
     * @param flags      every flag, dismissed ones included (marked)
     * @param checking   some playlist videos are still being looked at; the still-picture check waits for them
     * @param musicNote  the general music note (null for a phone stream with no music, where nothing plays from us)
     */
    public record CheckView(List<FlagView> flags, int open, boolean checking, String musicNote, String musicHelp,
                            String youtubeTitle, ThumbnailView thumbnail) {
    }

    public CheckView check(String email, Long id) {
        return view(streamService.get(email, id));
    }

    CheckView view(LiveStream s) {
        List<StreamItem> videos = items.findAllByStreamIdAndKindOrderByPositionAsc(s.getId(), StreamItem.Kind.VIDEO);
        List<StreamItem> tracks = items.findAllByStreamIdAndKindOrderByPositionAsc(s.getId(), StreamItem.Kind.AUDIO);
        Set<Long> videoIds = readyIds(videos);
        Set<Long> trackIds = readyIds(tracks);
        int still = videoIds.isEmpty() ? 0 : media.findStillPictures(videoIds).size();
        int unchecked = videoIds.isEmpty() ? 0 : media.findStillUnchecked(videoIds).size();
        boolean phone = s.getSource() == LiveStream.Source.PHONE;

        int liveOverlays = 0;
        for (StudioSpec.OverlaySetting o : overlays(s)) {
            if (o.visible() && (o.scene() == null || StudioSpec.LIVE.equals(o.scene()))) {
                liveOverlays++;
            }
        }
        Optional<MediaAsset> thumb = thumbnail(s);
        GoLiveCheck.Thumbnail measured = thumb.map(this::measure).orElse(null);
        GoLiveCheck.RepeatedReply repeated = repeatedReply(s.getUser());

        List<GoLiveCheck.Flag> flags = GoLiveCheck.run(new GoLiveCheck.Input(phone, s.getLoopCount() == 0,
                s.getLoopCount(), videoIds.size(), still, unchecked, trackIds.size(), liveOverlays, s.getYoutubeTitle(),
                measured, repeated));
        Set<String> dismissed = dismissed(s);
        List<FlagView> views = new ArrayList<>();
        int open = 0;
        for (GoLiveCheck.Flag f : flags) {
            boolean d = dismissed.contains(f.id());
            if (!d) {
                open++;
            }
            views.add(new FlagView(f.id(), f.kind().name(), f.title(), f.detail(), f.fix().name(), f.fixLabel(), f.suggestion(),
                    f.help(), d));
        }
        // Sound reaches viewers unless the videos are muted and there's no music.
        boolean music = phone || !trackIds.isEmpty() || s.getAudioMode() != LiveStream.AudioMode.MUTE;
        ThumbnailView tv = thumb.map(m -> new ThumbnailView(m.getId(), m.getTitle(), "/api/media/" + m.getId() + "/thumbnail")).orElse(null);
        return new CheckView(views, open, !phone && unchecked > 0, music ? GoLiveCheck.MUSIC_NOTE : null, GoLiveCheck.HELP_MUSIC,
                s.getYoutubeTitle(), tv);
    }

    /** Open (not dismissed) flags, for the weekly check. */
    List<FlagView> openFlags(LiveStream s) {
        return view(s).flags().stream().filter(f -> !f.dismissed()).toList();
    }

    // ------------------------------------------------------------------ what the creator types in

    /** The title and thumbnail the creator uses on YouTube; blank clears. The thumbnail must be an image in their library. */
    @Transactional
    public CheckView setTitleAndThumbnail(String email, Long id, String title, Long thumbnailMediaId, boolean clearThumbnail) {
        LiveStream s = streamService.get(email, id);
        if (title != null) {
            String t = title.strip();
            if (t.length() > 100) {
                throw new IllegalArgumentException("YouTube titles are at most 100 characters.");
            }
            s.setYoutubeTitle(t.isEmpty() ? null : t);
        }
        if (clearThumbnail) {
            s.setThumbnailMediaId(null);
        } else if (thumbnailMediaId != null) {
            MediaAsset m = media.findByIdAndUserEmail(thumbnailMediaId, email)
                    .filter(a -> a.getContentType() == MediaAsset.ContentType.IMAGE)
                    .orElseThrow(() -> new IllegalArgumentException("Pick a picture from your library."));
            s.setThumbnailMediaId(m.getId());
        }
        streams.save(s);
        return view(s);
    }

    @Transactional
    public CheckView dismiss(String email, Long id, String flagId, boolean dismiss) {
        if (!GoLiveCheck.IDS.contains(flagId)) {
            throw new IllegalArgumentException("Unknown check.");
        }
        LiveStream s = streamService.get(email, id);
        Set<String> d = dismissed(s);
        if (dismiss) {
            d.add(flagId);
        } else {
            d.remove(flagId);
        }
        s.setCheckDismissed(d.isEmpty() ? null : String.join(",", d));
        streams.save(s);
        return view(s);
    }

    /** The one-tap fixes that change something in AFK Live (the rest are links in the app). */
    @Transactional
    public CheckView fix(String email, Long id, String flagId) {
        LiveStream s = streamService.get(email, id);
        switch (flagId) {
            case GoLiveCheck.STILL_PICTURE -> addClockAndNowPlaying(email, s);
            case GoLiveCheck.TITLE_LIVE -> {
                if (s.getYoutubeTitle() != null) {
                    s.setYoutubeTitle(GoLiveCheck.honestTitle(s.getYoutubeTitle(), s.getLoopCount() == 0));
                    streams.save(s);
                }
            }
            case GoLiveCheck.REPEATED_MESSAGE -> stopReplies(s.getUser());
            default -> throw new IllegalArgumentException("This one is fixed from its own screen.");
        }
        return view(streamService.get(email, id));
    }

    /** Ids of the clock and now-playing layers the fix adds (so a second tap doesn't add them twice). */
    static final String CLOCK_ID = "check-clock";
    static final String NOW_PLAYING_ID = "check-now-playing";

    private void addClockAndNowPlaying(String email, LiveStream s) {
        List<StudioSpec.OverlaySetting> list = new ArrayList<>(overlays(s));
        boolean music = !readyIds(items.findAllByStreamIdAndKindOrderByPositionAsc(s.getId(), StreamItem.Kind.AUDIO)).isEmpty();
        list.removeIf(o -> CLOCK_ID.equals(o.id()) || NOW_PLAYING_ID.equals(o.id()));
        list.add(new StudioSpec.OverlaySetting(CLOCK_ID, "CLOCK", true, 0.04, 0.05, 0, 0.05, null, null, null, "#FFFFFF",
                "#99000000", 1, s.getTimeZone(), null, 0, null));
        list.add(new StudioSpec.OverlaySetting(NOW_PLAYING_ID, "TEXT", true, 0.04, 0.88, 0, 0.04,
                music ? "Now playing: {track}" : "Now playing: {title}", null, null, "#FFFFFF", "#99000000", 1, null, null, 0, null));
        // Through the stream service: validated, saved, and applied to a running stream between frames.
        streamService.setOverlays(email, s.getId(), list);
    }

    private void stopReplies(User user) {
        User u = users.findById(user.getId()).orElseThrow();
        u.setAutoReplyEnabled(false);
        users.save(u);
        int jobs = streams.stopLiveAutoReplies(u.getId());
        log.info("Go-live check: automatic replies stopped for user {} ({} running stream(s))", u.getId(), jobs);
    }

    // ------------------------------------------------------------------ helpers

    private static Set<Long> readyIds(List<StreamItem> list) {
        Set<Long> ids = new LinkedHashSet<>();
        for (StreamItem i : list) {
            if (i.getMedia() != null && i.getMedia().getStatus() == MediaStatus.READY) {
                ids.add(i.getMedia().getId());
            }
        }
        return ids;
    }

    private static List<StudioSpec.OverlaySetting> overlays(LiveStream s) {
        if (s.getOverlays() == null || s.getOverlays().isBlank()) {
            return List.of();
        }
        return StudioSpec.parse(s.getOverlays(), new TypeReference<List<StudioSpec.OverlaySetting>>() { });
    }

    private static Set<String> dismissed(LiveStream s) {
        if (s.getCheckDismissed() == null || s.getCheckDismissed().isBlank()) {
            return new LinkedHashSet<>();
        }
        return Arrays.stream(s.getCheckDismissed().split(",")).map(String::strip).filter(GoLiveCheck.IDS::contains)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Optional<MediaAsset> thumbnail(LiveStream s) {
        if (s.getThumbnailMediaId() == null) {
            return Optional.empty();
        }
        return media.findById(s.getThumbnailMediaId()).filter(m -> m.getContentType() == MediaAsset.ContentType.IMAGE);
    }

    /** Size from the library; contrast measured at phone size (null measurements leave those checks out). */
    private GoLiveCheck.Thumbnail measure(MediaAsset m) {
        if (m.getWidth() == null || m.getHeight() == null || m.getStatus() != MediaStatus.READY) {
            return null;
        }
        Double contrast = contrastCache.computeIfAbsent(m.getId(), k -> contrastOf(m));
        if (contrastCache.size() > 2000) {
            contrastCache.clear();
        }
        return new GoLiveCheck.Thumbnail(m.getWidth(), m.getHeight(), contrast == null ? -1 : contrast);
    }

    private Double contrastOf(MediaAsset m) {
        String key = m.getThumbnailKey() != null ? m.getThumbnailKey() : m.getS3Key();
        try (InputStream in = storage.downloadFile(key)) {
            BufferedImage img = ImageIO.read(in);
            return img == null ? null : phoneContrast(img);
        } catch (Exception e) {
            log.debug("Thumbnail {} couldn't be measured: {}", m.getId(), e.getMessage());
            return null;
        }
    }

    /**
     * RMS contrast (0..1) of the picture shrunk to phone size: the spread of brightness around its
     * average. Near 0, the picture is one flat colour at that size and nothing on it stands out.
     */
    static double phoneContrast(BufferedImage src) {
        BufferedImage small = new BufferedImage(PHONE_W, PHONE_H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = small.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src.getScaledInstance(PHONE_W, PHONE_H, java.awt.Image.SCALE_AREA_AVERAGING), 0, 0, null);
        g.dispose();
        int n = PHONE_W * PHONE_H;
        double[] luma = new double[n];
        double sum = 0;
        for (int y = 0; y < PHONE_H; y++) {
            for (int x = 0; x < PHONE_W; x++) {
                int rgb = small.getRGB(x, y);
                double l = (0.2126 * ((rgb >> 16) & 0xff) + 0.7152 * ((rgb >> 8) & 0xff) + 0.0722 * (rgb & 0xff)) / 255.0;
                luma[y * PHONE_W + x] = l;
                sum += l;
            }
        }
        double mean = sum / n;
        double var = 0;
        for (double l : luma) {
            var += (l - mean) * (l - mean);
        }
        return Math.sqrt(var / n);
    }

    private GoLiveCheck.RepeatedReply repeatedReply(User user) {
        if (user == null || user.getId() == null) {
            return null;
        }
        List<Object[]> rows = engagement.findRepeatedReplies(user.getId(), LocalDateTime.now().minusDays(7), 3, PageRequest.of(0, 1));
        if (rows.isEmpty()) {
            return null;
        }
        Object[] r = rows.getFirst();
        return new GoLiveCheck.RepeatedReply((String) r[0], ((Number) r[1]).longValue());
    }
}
