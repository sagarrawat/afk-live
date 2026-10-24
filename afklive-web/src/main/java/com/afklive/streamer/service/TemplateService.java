package com.afklive.streamer.service;

import com.afklive.streamer.dto.StreamDtos;
import com.afklive.streamer.model.LiveStream;
import com.afklive.streamer.model.StreamItem;
import com.afklive.streamer.repository.StreamItemRepository;
import com.afklive.streamer.streaming.StudioSpec;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * GR-8: applies a niche template to a stream, through the same services a creator's own edits use, so
 * a running stream picks the new look up between frames (no restart, no disconnect).
 */
@Service
public class TemplateService {

    private final LiveStreamService streams;
    private final StreamScheduleService schedules;
    private final StreamItemRepository items;

    public TemplateService(LiveStreamService streams, StreamScheduleService schedules, StreamItemRepository items) {
        this.streams = streams;
        this.schedules = schedules;
        this.items = items;
    }

    public record SlotView(String start, String end) {
    }

    public record PresetView(String id, String name, String description, List<SlotView> slots) {
    }

    public record TemplateView(String id, String name, String description, String source, List<String> languages,
                               List<PresetView> schedules) {
    }

    /** What applying did, in words for the toast. */
    public record Applied(String template, int layers, int schedulesAdded) {
    }

    public List<TemplateView> list() {
        return NicheTemplates.ALL.stream().map(TemplateService::view).toList();
    }

    static TemplateView view(NicheTemplates.Template t) {
        return new TemplateView(t.id(), t.name(), t.description(), t.source().name(), NicheTemplates.LANGUAGES,
                t.schedules().stream().map(p -> new PresetView(p.id(), p.name(), p.description(),
                        p.slots().stream().map(sl -> new SlotView(sl.start(), sl.end())).toList())).toList());
    }

    /**
     * Replaces the template layers (ids starting "tpl-") with this template's and keeps the creator's
     * own; colours the scenes that have no background picture; sets shuffle and the music blend; adds
     * the chosen schedule preset (if any) as daily schedules in the stream's time zone.
     */
    @Transactional
    public Applied apply(String email, Long streamId, String templateId, String language, String schedulePreset) {
        NicheTemplates.Template t = NicheTemplates.find(templateId == null ? "" : templateId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown template."));
        String lang = language == null || language.isBlank() ? "en" : language;
        if (!NicheTemplates.LANGUAGES.contains(lang)) {
            throw new IllegalArgumentException("Template labels come in English or Hindi.");
        }
        LiveStream s = streams.get(email, streamId);
        boolean phone = s.getSource() == LiveStream.Source.PHONE;
        if (t.source() == NicheTemplates.Source.PHONE && !phone) {
            throw new IllegalArgumentException("This template is for streams from your phone.");
        }
        if (t.source() == NicheTemplates.Source.PLAYLIST && phone) {
            throw new IllegalArgumentException("This template is for playlist streams.");
        }
        NicheTemplates.SchedulePreset preset = null;
        if (schedulePreset != null && !schedulePreset.isBlank()) {
            preset = t.schedules().stream().filter(p -> p.id().equals(schedulePreset)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("That schedule doesn't go with this template."));
        }

        boolean hasMusic = !items.findAllByStreamIdAndKindOrderByPositionAsc(streamId, StreamItem.Kind.AUDIO).isEmpty();
        List<StudioSpec.OverlaySetting> mine = new ArrayList<>();
        for (StudioSpec.OverlaySetting o : overlays(s)) {
            boolean template = o.id() != null && o.id().startsWith(NicheTemplates.PREFIX);
            boolean defaultBrb = "brb-text".equals(o.id()) && "Be right back".equals(o.text()); // the phone stream's starter
            if (!template && !defaultBrb) {
                mine.add(o);
            }
        }
        List<StudioSpec.OverlaySetting> layers = NicheTemplates.overlays(t, lang, s.getTimeZone(), hasMusic);
        if (mine.size() + layers.size() > LiveStreamService.MAX_OVERLAYS) {
            throw new IllegalArgumentException("This stream has too many overlays to add the template's " + layers.size()
                    + ". Remove a few first.");
        }
        List<StudioSpec.OverlaySetting> next = new ArrayList<>(mine);
        next.addAll(layers);
        streams.setOverlays(email, streamId, next);

        List<StudioSpec.SceneSetting> scenes = new ArrayList<>();
        for (StudioSpec.SceneSetting sc : streams.view(email, streamId).scenes()) {
            boolean colourOnly = sc.backgroundMediaId() == null;
            boolean live = StudioSpec.LIVE.equals(sc.name());
            scenes.add(colourOnly && !live
                    ? new StudioSpec.SceneSetting(sc.name(), null, null, "COLOR", t.palette(), sc.music())
                    : sc);
        }
        streams.setScenes(email, streamId, scenes);

        if (!phone) {
            String order = t.shuffle() ? "SHUFFLE" : null;
            streams.update(email, streamId, new StreamDtos.UpdateStream(null, null, null, null, order, null,
                    hasMusic ? "SHUFFLE" : null, null, null, null, null));
            streams.setPresentation(email, streamId, new StreamDtos.Presentation(null, t.crossfade()));
        }

        int added = 0;
        if (preset != null) {
            for (NicheTemplates.Slot slot : preset.slots()) {
                schedules.create(email, streamId, new StreamDtos.ScheduleRequest("DAILY", null, null, null, slot.start(), slot.end(),
                        s.getTimeZone(), null, true));
                added++;
            }
        }
        return new Applied(t.name(), layers.size(), added);
    }

    private static List<StudioSpec.OverlaySetting> overlays(LiveStream s) {
        if (s.getOverlays() == null || s.getOverlays().isBlank()) {
            return List.of();
        }
        return StudioSpec.parse(s.getOverlays(), new TypeReference<List<StudioSpec.OverlaySetting>>() { });
    }
}
