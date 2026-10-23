package com.afklive.streamer.streaming.studio;

import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Plays a list of files back to back with no gap: the next file's decoder starts a few seconds
 * before the current one ends, so the switch happens between two ticks. In order or shuffled (each
 * item once before any repeats), forever or a set number of times. Skips and list edits apply on the
 * next tick without touching the output. Used for video playlists and, with audio-only items, music.
 */
@Slf4j
public final class PlaylistFeed implements Feed {

    /** One playlist entry. {@code durationSeconds} is 0 when unknown (prerolling then waits for the decoder to finish). */
    /** {@code fit}: how this item fills the frame (PL-6); null uses the stream's. */
    public record Item(String id, String title, Path path, boolean hasAudio, double durationSeconds, DecoderSource.Fit fit) {
        public Item(String id, String title, Path path, boolean hasAudio, double durationSeconds) {
            this(id, title, path, hasAudio, durationSeconds, null);
        }
    }

    public enum Order { IN_ORDER, SHUFFLE }

    /** Where playback is, for resuming after a restart and for "now playing". */
    public record Position(String itemId, double offsetSeconds, int loopsCompleted) {
        public static final Position START = new Position(null, 0, 0);
    }

    public record NowPlaying(Item item, double elapsedSeconds, List<Item> upNext) {
    }

    /** Creates the decoder for an item; tests can substitute their own. */
    @FunctionalInterface
    public interface SourceFactory {
        DecoderSource open(Item item, double startSeconds, boolean audioOnly);
    }

    /** Start the next decoder this long before the current item ends. */
    static final double PREROLL_SECONDS = 3.0;
    /** An item that fails to start is skipped; a whole list of broken items rests this long between attempts. */
    static final int FAILED_PASS_PAUSE_TICKS = 150;

    private final Canvas canvas;
    private final SourceFactory factory;
    private final boolean audioOnly;
    private final Random random;
    private final ConcurrentLinkedQueue<Runnable> commands = new ConcurrentLinkedQueue<>();

    private List<Item> items;
    private Order order;
    private int loops; // 0 = forever
    private int loopsCompleted;
    /** Items still to play in the current pass, by id, in play order. */
    private final List<String> remainingInPass = new ArrayList<>();
    private String lastPlayedId;

    private Playing current;
    private Playing next;
    private boolean finished;
    private int failuresInARow;
    /** PL-7: items that failed to play (id -> reason), until they play again; read by the heartbeat. */
    private final java.util.concurrent.ConcurrentHashMap<String, Failed> failed = new java.util.concurrent.ConcurrentHashMap<>();

    public record Failed(Item item, String reason) {
    }

    /** PL-7: the items skipped because they couldn't be played. */
    public List<Failed> failedItems() {
        return List.copyOf(failed.values());
    }
    private int pauseTicks;
    /** AU-3: seconds a music track overlaps into the next (audio-only lists); 0 cuts straight. */
    private volatile double crossfadeSeconds;
    private Frame fadeFrame;
    private volatile NowPlaying nowPlaying;
    private long framesPlayed;
    private boolean nextStartsNewPass;

    private static final class Playing {
        final Item item;
        final DecoderSource source;
        final double startOffset;
        long frames;

        Playing(Item item, DecoderSource source, double startOffset) {
            this.item = item;
            this.source = source;
            this.startOffset = startOffset;
        }

        double elapsed(Canvas canvas) {
            return startOffset + (double) frames / canvas.fps();
        }
    }

    public PlaylistFeed(Canvas canvas, List<Item> items, Order order, int loops, Position resume,
                        SourceFactory factory, boolean audioOnly, Random random) {
        this.canvas = canvas;
        this.factory = factory;
        this.audioOnly = audioOnly;
        this.random = random;
        this.items = List.copyOf(items);
        this.order = order;
        this.loops = Math.max(0, loops);
        Position at = resume == null ? Position.START : resume;
        this.loopsCompleted = at.loopsCompleted();
        refillPass(at.itemId());
        if (at.itemId() != null && !remainingInPass.isEmpty() && remainingInPass.get(0).equals(at.itemId())) {
            Item item = find(at.itemId());
            remainingInPass.remove(0);
            current = open(item, Math.max(0, at.offsetSeconds()));
        }
    }

    // ---------------------------------------------------------------- control (any thread)

    /** Let the current pass through the list finish, then end (the "stop after the playlist" option). */
    public void endAfterThisPass() {
        commands.add(() -> {
            if (next != null && nextStartsNewPass) {
                // The item queued up next already belongs to the following pass: undo that.
                next.source.close();
                next = null;
                nextStartsNewPass = false;
                loopsCompleted--;
                remainingInPass.clear();
            }
            loops = loopsCompleted + 1;
        });
    }

    /** AU-3: overlap tracks by this many seconds (0 to 20); applies from the next change of track. */
    public void setCrossfadeSeconds(double seconds) {
        crossfadeSeconds = Math.max(0, Math.min(20, seconds));
    }

    /** Jump to the next item now. */
    public void skip() {
        commands.add(() -> {
            if (current != null) {
                log.debug("Skipping {}", current.item.id());
                lastPlayedId = current.item.id();
                current.source.close();
                current = null;
            }
        });
    }

    /** Replace the list; the current item keeps playing, and what follows comes from the new list. */
    public void setItems(List<Item> newItems, Order newOrder, int newLoops) {
        List<Item> copy = List.copyOf(newItems);
        commands.add(() -> {
            this.items = copy;
            this.order = newOrder;
            this.loops = Math.max(0, newLoops);
            this.finished = false;
            this.failuresInARow = 0;
            String after = current != null ? current.item.id() : lastPlayedId;
            remainingInPass.clear();
            refillPass(null);
            if (newOrder == Order.IN_ORDER && after != null) {
                int idx = indexOf(after);
                if (idx >= 0) {
                    // Continue with the item after the one playing.
                    remainingInPass.clear();
                    for (int i = idx + 1; i < copy.size(); i++) {
                        remainingInPass.add(copy.get(i).id());
                    }
                }
            }
            if (current != null) {
                remainingInPass.remove(current.item.id());
            }
            if (next != null) {
                next.source.close(); // prepared from the old list; prepare again
                next = null;
                if (nextStartsNewPass) {
                    loopsCompleted--; // that pass never started
                    nextStartsNewPass = false;
                }
            }
        });
    }

    public NowPlaying nowPlaying() {
        return nowPlaying;
    }

    /** Current position, for saving and resuming. */
    public Position position() {
        NowPlaying np = nowPlaying;
        int loopsDone = publishedLoops;
        return np == null || np.item() == null ? new Position(null, 0, loopsDone)
                : new Position(np.item().id(), np.elapsedSeconds(), loopsDone);
    }

    /** Frames actually taken from files (not held or silent ticks). */
    long framesPlayed() {
        return framesPlayed;
    }

    @Override
    public boolean finished() {
        return finished;
    }

    // ---------------------------------------------------------------- clock thread

    @Override
    public void next(Frame out) {
        Runnable c;
        while ((c = commands.poll()) != null) {
            c.run();
        }
        if (pauseTicks > 0) {
            pauseTicks--;
            out.silence();
            return;
        }
        boolean got = false;
        for (int attempt = 0; attempt < 3 && !got; attempt++) {
            if (current == null) {
                current = takeNext();
                if (current == null) {
                    break; // finished, or nothing playable right now
                }
            }
            if (current.source.poll(out)) {
                current.frames++;
                framesPlayed++;
                failuresInARow = 0;
                if (current.frames == 1 && !failed.isEmpty()) {
                    failed.remove(current.item.id()); // it plays now (replaced file, or a passing glitch)
                }
                got = true;
            } else {
                DecoderSource.State s = current.source.state();
                if (s == DecoderSource.State.ENDED || s == DecoderSource.State.FAILED) {
                    if (s == DecoderSource.State.FAILED) {
                        log.warn("Playlist item {} could not be played: {}", current.item.id(), current.source.lastError());
                        if (failed.size() < 50) {
                            failed.put(current.item.id(), new Failed(current.item, String.valueOf(current.source.lastError())));
                        }
                        if (++failuresInARow >= Math.max(1, items.size())) {
                            pauseTicks = FAILED_PASS_PAUSE_TICKS; // nothing in the list plays: don't spin
                            failuresInARow = 0;
                        }
                    }
                    lastPlayedId = current.item.id();
                    current.source.close();
                    current = null;
                    continue; // straight to the (prerolled) next item, same tick
                }
                break; // still starting: hold the last picture for this tick
            }
        }
        if (!got) {
            out.silence();
        } else {
            crossfade(out);
        }
        maybePreroll();
        publishNowPlaying();
    }

    /**
     * AU-3: in the last seconds of a track, bring the next one in under it (equal-power curves, so
     * the loudness holds). The next track is then already playing when this one ends.
     */
    private void crossfade(Frame out) {
        double fade = crossfadeSeconds;
        if (!audioOnly || fade <= 0 || current == null || next == null) {
            return;
        }
        double remaining = duration(current) - current.elapsed(canvas);
        if (duration(current) <= 0 || remaining > fade) {
            return;
        }
        if (fadeFrame == null) {
            fadeFrame = new Frame(canvas);
        }
        if (!next.source.poll(fadeFrame)) {
            return; // not ready yet: this tick stays the outgoing track alone
        }
        next.frames++;
        double t = Math.max(0, Math.min(1, 1 - remaining / fade));
        Yuv.mix(out.audio, Math.cos(t * Math.PI / 2), fadeFrame.audio, Math.sin(t * Math.PI / 2));
    }

    private double duration(Playing p) {
        double d = p.item.durationSeconds();
        if (d <= 0 && p.source.knownDurationSeconds() != null) {
            d = p.source.knownDurationSeconds();
        }
        return d;
    }

    private void maybePreroll() {
        if (current == null || next != null) {
            return;
        }
        double duration = current.item.durationSeconds();
        if (duration <= 0 && current.source.knownDurationSeconds() != null) {
            duration = current.source.knownDurationSeconds(); // learned when the file arrived
        }
        double lead = audioOnly ? Math.max(PREROLL_SECONDS, crossfadeSeconds + 2) : PREROLL_SECONDS;
        boolean nearEnd = duration > 0 && duration - current.elapsed(canvas) < lead;
        if (nearEnd || current.source.decoderFinished()) {
            int before = loopsCompleted;
            next = takeNextUnopened();
            nextStartsNewPass = next != null && loopsCompleted != before;
        }
    }

    /** The prerolled item if there is one, else open the next. */
    private Playing takeNext() {
        if (next != null) {
            Playing p = next;
            next = null;
            nextStartsNewPass = false;
            return p;
        }
        return takeNextUnopened();
    }

    private Playing takeNextUnopened() {
        if (items.isEmpty() || finished) {
            return null;
        }
        if (remainingInPass.isEmpty()) {
            if (loops > 0 && loopsCompleted + 1 >= loops) {
                if (current == null) { // the last item of the last pass has ended
                    loopsCompleted++;
                    finished = true;
                }
                return null;
            }
            loopsCompleted++;
            refillPass(null);
        }
        Item item = find(remainingInPass.remove(0));
        return item == null ? null : open(item, 0);
    }

    private Playing open(Item item, double startSeconds) {
        DecoderSource source = factory.open(item, startSeconds, audioOnly);
        source.start();
        return new Playing(item, source, startSeconds);
    }

    private void refillPass(String startAt) {
        List<String> ids = new ArrayList<>();
        for (Item i : items) {
            ids.add(i.id());
        }
        if (order == Order.SHUFFLE) {
            Collections.shuffle(ids, random);
            String previous = current != null ? current.item.id() : lastPlayedId;
            if (ids.size() > 1 && ids.get(0).equals(previous)) {
                Collections.swap(ids, 0, ids.size() - 1); // never the same item twice in a row
            }
            if (startAt != null && ids.remove(startAt)) {
                ids.add(0, startAt);
            }
        } else if (startAt != null) {
            int idx = ids.indexOf(startAt);
            if (idx > 0) {
                ids = new ArrayList<>(ids.subList(idx, ids.size()));
            }
        }
        remainingInPass.addAll(ids);
    }

    /** Completed passes as of the item now playing (a prerolled item from the next pass doesn't count yet). */
    private volatile int publishedLoops;

    private void publishNowPlaying() {
        publishedLoops = loopsCompleted - (nextStartsNewPass ? 1 : 0);
        if (current == null) {
            nowPlaying = new NowPlaying(null, 0, upNext());
            return;
        }
        nowPlaying = new NowPlaying(current.item, current.elapsed(canvas), upNext());
    }

    private List<Item> upNext() {
        List<Item> result = new ArrayList<>(3);
        if (next != null) {
            result.add(next.item);
        }
        for (String id : remainingInPass) {
            if (result.size() >= 3) {
                break;
            }
            Item i = find(id);
            if (i != null) {
                result.add(i);
            }
        }
        if (result.size() < 3 && (loops == 0 || loopsCompleted + 1 < loops) && order == Order.IN_ORDER) {
            for (Item i : items) {
                if (result.size() >= 3) {
                    break;
                }
                result.add(i);
            }
        }
        return List.copyOf(result);
    }

    private Item find(String id) {
        for (Item i : items) {
            if (i.id().equals(id)) {
                return i;
            }
        }
        return null;
    }

    private int indexOf(String id) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public void close() {
        if (current != null) {
            current.source.close();
        }
        if (next != null) {
            next.source.close();
        }
    }
}
