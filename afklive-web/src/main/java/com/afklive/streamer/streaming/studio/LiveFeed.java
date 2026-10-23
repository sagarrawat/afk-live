package com.afklive.streamer.streaming.studio;

import lombok.extern.slf4j.Slf4j;

/**
 * Never-drop: shows a live input (the creator's phone) while it is healthy and a fallback feed (the
 * BRB scene) while it isn't. The output never stops either way; only what's on screen changes.
 * <ul>
 *   <li>No frame from the input for {@code lossMillis} switches to the fallback.</li>
 *   <li>Back to live once the input has delivered steadily for {@code recoverMillis}.</li>
 *   <li>Short gaps below {@code lossMillis} hold the last picture (with silence) instead of flickering to BRB.</li>
 * </ul>
 */
@Slf4j
public final class LiveFeed implements Feed {

    public interface Listener {
        /** Called on the clock thread when the picture switches; must not block. */
        void onSwitch(boolean live);
    }

    public record Settings(long lossMillis, long recoverMillis) {
        public static Settings defaults() {
            return new Settings(1000, 1000);
        }
    }

    private final Canvas canvas;
    private final DecoderSource input;
    private final Feed fallback;
    private final Settings settings;
    private final Listener listener;
    private final Frame scratch;

    private volatile boolean onLive;
    private long steadySinceNanos;
    private long fallbackSinceNanos = System.nanoTime();

    public LiveFeed(Canvas canvas, DecoderSource input, Feed fallback, Settings settings, Listener listener) {
        this.canvas = canvas;
        this.input = input;
        this.fallback = fallback;
        this.settings = settings;
        this.listener = listener == null ? live -> { } : listener;
        this.scratch = new Frame(canvas);
    }

    public boolean onLive() {
        return onLive;
    }

    /** How long the fallback has been on screen (0 while live): the grace timer for ending a stream. */
    public long millisOnFallback() {
        return onLive ? 0 : (System.nanoTime() - fallbackSinceNanos) / 1_000_000;
    }

    public DecoderSource input() {
        return input;
    }

    @Override
    public void next(Frame out) {
        long now = System.nanoTime();
        if (onLive) {
            if (input.poll(out)) {
                return;
            }
            if (input.millisSinceLastFrame() < settings.lossMillis()) {
                out.silence(); // a short hiccup: keep the last picture
                return;
            }
            log.info("Live input {} lost; switching to fallback", input.name());
            onLive = false;
            fallbackSinceNanos = now;
            steadySinceNanos = 0;
            listener.onSwitch(false);
            fallback.next(out);
            return;
        }

        // On fallback: keep draining the input so it's fresh when we switch back.
        boolean got = input.poll(scratch);
        if (got) {
            if (steadySinceNanos == 0) {
                steadySinceNanos = now;
            }
        } else if (input.millisSinceLastFrame() > 300) {
            steadySinceNanos = 0; // not steady yet
        }
        if (got && steadySinceNanos != 0 && (now - steadySinceNanos) / 1_000_000 >= settings.recoverMillis()) {
            log.info("Live input {} is back", input.name());
            onLive = true;
            listener.onSwitch(true);
            out.copyFrom(scratch);
            return;
        }
        fallback.next(out);
    }

    @Override
    public void close() {
        input.close(); // the fallback is another scene's feed; the studio closes it with that scene
    }
}
