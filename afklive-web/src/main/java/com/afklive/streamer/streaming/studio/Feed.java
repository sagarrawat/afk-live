package com.afklive.streamer.streaming.studio;

/**
 * Something that fills one frame per tick of the studio clock. {@link #next} is only ever called from
 * the clock thread and must return quickly; when it has no new picture it leaves the frame's video
 * untouched (the last picture stays on screen) and writes silence.
 */
public interface Feed extends AutoCloseable {

    void next(Frame out);

    /** True once the feed has nothing more to play (a playlist that finished its loops). */
    default boolean finished() {
        return false;
    }

    @Override
    default void close() {
    }
}
