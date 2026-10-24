package com.afklive.streamer.service;

import com.afklive.streamer.service.GoLiveCheck.Flag;
import com.afklive.streamer.service.GoLiveCheck.Input;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** GR-9: each rule flags only what is certain, and says it in plain words. */
class GoLiveCheckTest {

    /** A healthy 24/7 playlist: several videos, several songs, an overlay, a good title. */
    private static Input good() {
        return new Input(false, true, 0, 5, 0, 0, 12, 1, "Lofi beats to study and relax", null, null);
    }

    private static List<String> ids(Input in) {
        return GoLiveCheck.run(in).stream().map(Flag::id).toList();
    }

    @Test
    void aVariedStreamHasNothingToFlag() {
        assertThat(GoLiveCheck.run(good())).isEmpty();
    }

    @Test
    void oneVideoOnLoopIsFlaggedOnlyWhenNothingElseVaries() {
        // One video, one song, forever.
        assertThat(ids(new Input(false, true, 0, 1, 0, 0, 1, 1, null, null, null))).containsExactly(GoLiveCheck.ONE_FILE_LOOP);
        // One video, no music, three passes.
        assertThat(ids(new Input(false, false, 3, 1, 0, 0, 0, 1, null, null, null))).containsExactly(GoLiveCheck.ONE_FILE_LOOP);
        // A looping visual under a real music playlist is the normal lofi setup: not flagged.
        assertThat(ids(new Input(false, true, 0, 1, 0, 0, 8, 1, null, null, null))).isEmpty();
        // Played once: not a loop.
        assertThat(ids(new Input(false, false, 1, 1, 0, 0, 0, 1, null, null, null))).isEmpty();
        // A phone stream is really live.
        assertThat(ids(new Input(true, true, 0, 1, 0, 0, 0, 0, null, null, null))).isEmpty();
    }

    @Test
    void aStillPictureIsFlaggedOnlyWhenMeasuredAndNothingIsOnScreen() {
        Flag f = GoLiveCheck.run(new Input(false, true, 0, 2, 2, 0, 6, 0, null, null, null)).getFirst();
        assertThat(f.id()).isEqualTo(GoLiveCheck.STILL_PICTURE);
        assertThat(f.fix()).isEqualTo(GoLiveCheck.Fix.ADD_OVERLAYS);
        assertThat(f.help()).isEqualTo(GoLiveCheck.HELP_LOOPS);
        // An overlay on screen: something changes.
        assertThat(ids(new Input(false, true, 0, 2, 2, 0, 6, 1, null, null, null))).isEmpty();
        // One video still, one moving.
        assertThat(ids(new Input(false, true, 0, 2, 1, 0, 6, 0, null, null, null))).isEmpty();
        // Not measured yet: no guess.
        assertThat(ids(new Input(false, true, 0, 2, 1, 1, 6, 0, null, null, null))).isEmpty();
    }

    @Test
    void aRepeatedReplyIsFlaggedFromThreeTimes() {
        Input three = new Input(false, true, 0, 5, 0, 0, 12, 1, null, null, new GoLiveCheck.RepeatedReply("Thanks for watching!", 3));
        Flag f = GoLiveCheck.run(three).getFirst();
        assertThat(f.id()).isEqualTo(GoLiveCheck.REPEATED_MESSAGE);
        assertThat(f.detail()).contains("“Thanks for watching!” 3 times");
        assertThat(f.fix()).isEqualTo(GoLiveCheck.Fix.STOP_REPLIES);
    }

    @Test
    void titlesNeedAWordPeopleSearchFor() {
        assertThat(GoLiveCheck.hasSearchablePhrase("Lofi beats")).isTrue();
        assertThat(GoLiveCheck.hasSearchablePhrase("हनुमान चालीसा 24/7")).as("any script").isTrue();
        assertThat(GoLiveCheck.hasSearchablePhrase("🔴 LIVE 24/7")).isFalse();
        assertThat(GoLiveCheck.hasSearchablePhrase("My live stream test")).isFalse();
        assertThat(GoLiveCheck.hasSearchablePhrase("Stream #2")).isFalse();
        assertThat(ids(new Input(false, true, 0, 5, 0, 0, 12, 1, "Live stream", null, null))).containsExactly(GoLiveCheck.TITLE_NO_PHRASE);
        assertThat(ids(new Input(false, true, 0, 5, 0, 0, 12, 1, "  ", null, null))).as("no title typed: nothing to check").isEmpty();
    }

    @Test
    void liveNowOverAReplayIsFlaggedWithAnHonestTitle() {
        assertThat(GoLiveCheck.misleadingLive("🔴 LIVE: Lofi beats")).isEqualTo("🔴 LIVE");
        assertThat(GoLiveCheck.misleadingLive("Lofi beats live now")).isEqualTo("live now");
        assertThat(GoLiveCheck.misleadingLive("Bhajans LIVE")).isEqualTo("LIVE");
        assertThat(GoLiveCheck.misleadingLive("24/7 lofi live radio")).as("a format word, not a claim").isNull();
        assertThat(GoLiveCheck.misleadingLive("OLIVER's lofi")).isNull();

        Flag f = GoLiveCheck.run(new Input(false, true, 0, 5, 0, 0, 12, 1, "🔴 LIVE: Lofi beats to study", null, null)).getFirst();
        assertThat(f.id()).isEqualTo(GoLiveCheck.TITLE_LIVE);
        assertThat(f.suggestion()).isEqualTo("Lofi beats to study · 24/7 replay");
        assertThat(f.fix()).isEqualTo(GoLiveCheck.Fix.USE_TITLE);
        // Really live from a phone: fine.
        assertThat(ids(new Input(true, true, 0, 0, 0, 0, 0, 0, "🔴 LIVE: Morning aarti", null, null))).isEmpty();
    }

    @Test
    void honestTitlesKeepTheirMeaningAndFitYouTubesLimit() {
        assertThat(GoLiveCheck.honestTitle("24/7 LIVE | Lofi radio", true)).isEqualTo("24/7 | Lofi radio · replay");
        assertThat(GoLiveCheck.honestTitle("Morning bhajans live now", false)).isEqualTo("Morning bhajans · replay");
        assertThat(GoLiveCheck.honestTitle("🔴 LIVE", true)).isEqualTo("24/7 replay");
        assertThat(GoLiveCheck.honestTitle("LIVE " + "a".repeat(99), true)).hasSizeLessThanOrEqualTo(100).endsWith("24/7 replay");
    }

    @Test
    void thumbnailsAreCheckedForSizeShapeAndContrastAtPhoneSize() {
        assertThat(ids(new Input(false, true, 0, 5, 0, 0, 12, 1, null, new GoLiveCheck.Thumbnail(1280, 720, 0.3), null))).isEmpty();
        assertThat(ids(new Input(false, true, 0, 5, 0, 0, 12, 1, null, new GoLiveCheck.Thumbnail(480, 270, 0.3), null)))
                .containsExactly(GoLiveCheck.THUMB_SMALL);
        assertThat(ids(new Input(false, true, 0, 5, 0, 0, 12, 1, null, new GoLiveCheck.Thumbnail(1080, 1080, 0.3), null)))
                .containsExactly(GoLiveCheck.THUMB_SHAPE);
        assertThat(ids(new Input(false, true, 0, 5, 0, 0, 12, 1, null, new GoLiveCheck.Thumbnail(1280, 720, 0.02), null)))
                .containsExactly(GoLiveCheck.THUMB_FLAT);
        // Contrast couldn't be measured (-1): only size and shape are checked.
        assertThat(ids(new Input(false, true, 0, 5, 0, 0, 12, 1, null, new GoLiveCheck.Thumbnail(1280, 720, -1), null))).isEmpty();
    }

    @Test
    void phoneContrastSeesThinPaleTextAsFlatAndBoldTextAsReadable() {
        BufferedImage pale = picture(new Color(0x40, 0x40, 0x48), new Color(0x4c, 0x4c, 0x54), 28);
        BufferedImage bold = picture(new Color(0x10, 0x10, 0x18), Color.WHITE, 160);
        assertThat(GoLiveCheckService.phoneContrast(pale)).isLessThan(GoLiveCheck.THUMB_FLAT_CONTRAST);
        assertThat(GoLiveCheckService.phoneContrast(bold)).isGreaterThan(GoLiveCheck.THUMB_FLAT_CONTRAST * 2);
    }

    private static BufferedImage picture(Color bg, Color fg, int fontPx) {
        BufferedImage img = new BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(bg);
        g.fillRect(0, 0, 1280, 720);
        g.setColor(fg);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, fontPx));
        g.drawString("LOFI", 120, 420);
        g.dispose();
        return img;
    }
}
