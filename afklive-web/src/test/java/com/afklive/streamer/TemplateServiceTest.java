package com.afklive.streamer;

import com.afklive.streamer.dto.StreamDtos.CreateStream;
import com.afklive.streamer.dto.StreamDtos.StreamView;
import com.afklive.streamer.model.*;
import com.afklive.streamer.repository.*;
import com.afklive.streamer.service.LiveStreamService;
import com.afklive.streamer.service.NicheTemplates;
import com.afklive.streamer.service.StreamScheduleService;
import com.afklive.streamer.service.TemplateService;
import com.afklive.streamer.streaming.StudioSpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** GR-8: niche templates applied through the stream's own services. */
@SpringBootTest
class TemplateServiceTest extends PostgresIntegrationTest {

    @Autowired TemplateService templates;
    @Autowired LiveStreamService streams;
    @Autowired StreamScheduleService schedules;
    @Autowired UserRepository users;
    @Autowired MediaAssetRepository media;

    private String newUser() {
        String email = "tpl-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        User u = new User(email);
        u.setPlanType(PlanType.ESSENTIALS);
        users.save(u);
        return email;
    }

    private LiveStream playlist(String email, boolean music) {
        LiveStream s = streams.create(email, new CreateStream("Bhajans", null, null, 720, 30));
        streams.update(email, s.getId(), new com.afklive.streamer.dto.StreamDtos.UpdateStream(null, null, null, null, null, null, null,
                null, null, null, "Asia/Kolkata"));
        User u = users.findByEmail(email).orElseThrow();
        List<Long> videos = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            MediaAsset m = new MediaAsset(u, "v" + i + ".mp4", "key/" + UUID.randomUUID(), 1L, MediaAsset.ContentType.VIDEO);
            m.setDurationSeconds(60.0);
            videos.add(media.save(m).getId());
        }
        streams.setItems(email, s.getId(), StreamItem.Kind.VIDEO, videos);
        if (music) {
            MediaAsset song = new MediaAsset(u, "song.mp3", "key/" + UUID.randomUUID(), 1L, MediaAsset.ContentType.AUDIO);
            song.setDurationSeconds(200.0);
            streams.setItems(email, s.getId(), StreamItem.Kind.AUDIO, List.of(media.save(song).getId()));
        }
        return s;
    }

    @Test
    void listsFiveNichesWithTheirSchedulePresets() {
        assertThat(templates.list()).extracting(TemplateService.TemplateView::id)
                .containsExactly("lofi", "devotional", "study", "sleep", "phone-gaming");
        assertThat(templates.list().get(1).schedules()).extracting(TemplateService.PresetView::id).containsExactly("aarti", "bhajans");
    }

    @Test
    void devotionalInHindiAddsLayersScenesAndTheAartiSchedule() {
        String email = newUser();
        LiveStream s = playlist(email, false);
        // The creator's own logo text stays.
        streams.setOverlays(email, s.getId(), List.of(new StudioSpec.OverlaySetting("mine", "TEXT", true, 0.1, 0.1, 0, 0.04, "Shri Mandir",
                null, null, "#FFFFFF", null, 1, null, null, 0, null)));

        TemplateService.Applied done = templates.apply(email, s.getId(), "devotional", "hi", "aarti");
        assertThat(done.schedulesAdded()).isEqualTo(2);

        StreamView v = streams.view(email, s.getId());
        assertThat(v.overlays()).extracting(StudioSpec.OverlaySetting::id).contains("mine", "tpl-now-playing", "tpl-up-next", "tpl-clock");
        assertThat(v.overlays()).filteredOn(o -> o.id().equals("tpl-now-playing")).singleElement()
                .extracting(StudioSpec.OverlaySetting::text).isEqualTo("अभी चल रहा है: {title}");
        assertThat(v.overlays()).filteredOn(o -> o.id().equals("tpl-clock")).singleElement()
                .extracting(StudioSpec.OverlaySetting::timeZone).isEqualTo("Asia/Kolkata");
        assertThat(v.scenes()).filteredOn(sc -> sc.name().equals(StudioSpec.BRB)).singleElement()
                .extracting(StudioSpec.SceneSetting::color).isEqualTo("#3A1C05");
        assertThat(v.playbackOrder()).as("an aarti sequence keeps its order").isEqualTo("IN_ORDER");
        assertThat(schedules.list(email, s.getId())).hasSize(2);

        // Applying another template replaces the template's layers, not the creator's.
        templates.apply(email, s.getId(), "lofi", "en", null);
        StreamView after = streams.view(email, s.getId());
        assertThat(after.overlays()).extracting(StudioSpec.OverlaySetting::id).contains("mine", "tpl-now-playing").doesNotContain("tpl-up-next");
        assertThat(after.overlays()).filteredOn(o -> o.id().startsWith("tpl-now")).singleElement()
                .extracting(StudioSpec.OverlaySetting::text).isEqualTo("Now playing: {title}");
        assertThat(after.playbackOrder()).as("lofi shuffles for variety").isEqualTo("SHUFFLE");
        assertThat(after.crossfadeSeconds()).isEqualTo(4);
    }

    @Test
    void nowPlayingShowsTheTrackWhenThereIsMusic() {
        String email = newUser();
        LiveStream s = playlist(email, true);
        templates.apply(email, s.getId(), "study", "en", null);
        assertThat(streams.view(email, s.getId()).overlays()).filteredOn(o -> o.id().equals("tpl-now-playing")).singleElement()
                .extracting(StudioSpec.OverlaySetting::text).isEqualTo("Now playing: {track}");
        assertThat(streams.view(email, s.getId()).musicOrder()).isEqualTo("SHUFFLE");
    }

    @Test
    void thePhoneTemplateReplacesTheStarterBrbAndFitsPhoneStreamsOnly() {
        String email = newUser();
        LiveStream phone = streams.create(email, new CreateStream("Gaming", "PHONE", "VERTICAL", 720, 30));
        templates.apply(email, phone.getId(), "phone-gaming", "en", null);
        assertThat(streams.view(email, phone.getId()).overlays()).extracting(StudioSpec.OverlaySetting::id)
                .doesNotContain("brb-text").contains("tpl-brb", "tpl-starting", "tpl-ending", "tpl-clock");
        assertThatThrownBy(() -> templates.apply(email, phone.getId(), "lofi", "en", null)).hasMessageContaining("playlist");
        LiveStream list = playlist(email, false);
        assertThatThrownBy(() -> templates.apply(email, list.getId(), "phone-gaming", "en", null)).hasMessageContaining("phone");
    }

    @Test
    void refusesWhatDoesntFit() {
        String email = newUser();
        String other = newUser();
        LiveStream s = playlist(email, false);
        assertThatThrownBy(() -> templates.apply(email, s.getId(), "nope", "en", null)).hasMessageContaining("Unknown template");
        assertThatThrownBy(() -> templates.apply(email, s.getId(), "lofi", "fr", null)).hasMessageContaining("English or Hindi");
        assertThatThrownBy(() -> templates.apply(email, s.getId(), "lofi", "en", "aarti")).hasMessageContaining("doesn't go with");
        assertThatThrownBy(() -> templates.apply(other, s.getId(), "lofi", "en", null)).hasMessageContaining("not found");
        assertThat(NicheTemplates.ALL).allSatisfy(t -> assertThat(NicheTemplates.overlays(t, "hi", "UTC", true)).allSatisfy(o ->
                assertThat(o.id()).startsWith(NicheTemplates.PREFIX)));
    }
}
