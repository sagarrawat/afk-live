package com.afklive.streamer.streaming;

import com.afklive.streamer.service.FileStorageService;
import com.afklive.streamer.util.SafePaths;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Library files on this worker's disk, shared by every stream it runs. Playlists fetch the next
 * items ahead of time, so a 10 GB file never has to download while viewers wait. Files in use are
 * pinned; when the cache grows past its budget the least recently used unpinned files are removed.
 */
@Component
@Slf4j
public class MediaCache {

    /** A partial download untouched this long was interrupted (an active one is written to continuously). */
    static final java.time.Duration STALE_PART = java.time.Duration.ofHours(1);
    private final String processTag = java.util.UUID.randomUUID().toString().substring(0, 8);

    private final FileStorageService storage;
    private final Path root;
    private final long budgetBytes;
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    /** By file: pin counts and last use. Files left by an earlier process are found by a scan at start. */
    private final Map<Path, Integer> pins = new HashMap<>();
    private final Map<Path, Long> lastUsed = new HashMap<>();
    private final java.util.concurrent.atomic.AtomicLong useClock = new java.util.concurrent.atomic.AtomicLong();
    private final ExecutorService prefetcher = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "media-prefetch");
        t.setDaemon(true);
        return t;
    });

    public MediaCache(FileStorageService storage,
                      @Value("${app.streaming.work-dir:data/worker}") String workDir,
                      @Value("${app.streaming.cache-gb:50}") long cacheGb) {
        this.storage = storage;
        this.root = Paths.get(workDir).resolve("cache");
        this.budgetBytes = cacheGb * 1024L * 1024 * 1024;
        adoptExistingFiles();
    }

    /** Files from before a restart count against the budget and are evicted oldest first. */
    private synchronized void adoptExistingFiles() {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (var files = Files.list(root)) {
            List<Path> old = new java.util.ArrayList<>();
            long staleBefore = System.currentTimeMillis() - STALE_PART.toMillis();
            for (Path p : (Iterable<Path>) files::iterator) {
                if (p.getFileName().toString().endsWith(".part")) {
                    // An interrupted download. A recent one may be another worker process's download in
                    // progress (OPS-3: during a deploy two workers share this cache), so only old ones go.
                    if (p.toFile().lastModified() < staleBefore) {
                        Files.deleteIfExists(p);
                    }
                } else {
                    old.add(p);
                }
            }
            old.sort(java.util.Comparator.comparingLong(p -> p.toFile().lastModified()));
            long rank = -old.size();
            for (Path p : old) {
                lastUsed.put(p, rank++); // older than anything used from now on, oldest first
            }
        } catch (IOException e) {
            log.warn("Couldn't scan the media cache: {}", e.getMessage());
        }
    }

    /** The local copy of {@code key}, downloading it now if needed. Blocks; never call from the studio clock. */
    public Path fetch(String key) throws IOException {
        Path target = pathFor(key);
        ReentrantLock lock = locks.computeIfAbsent(key, k -> new ReentrantLock());
        lock.lock();
        try {
            if (!Files.exists(target)) {
                Files.createDirectories(root);
                // Named per process: two worker processes sharing the cache never write the same file.
                Path partial = target.resolveSibling(target.getFileName() + "." + processTag + ".part");
                long started = System.nanoTime();
                storage.downloadFileToPath(key, partial);
                Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                log.info("Cached {} ({} MB in {} ms)", target.getFileName(), Files.size(target) / (1024 * 1024),
                        (System.nanoTime() - started) / 1_000_000);
                touch(target);
                evictIfNeeded(target);
            }
            touch(target);
            return target;
        } finally {
            lock.unlock();
            if (!lock.hasQueuedThreads()) {
                locks.remove(key, lock); // don't keep a lock per file ever seen
            }
        }
    }

    /** Starts downloading {@code key} in the background if it isn't here yet. */
    public void prefetch(String key) {
        if (key == null || Files.exists(pathFor(key))) {
            return;
        }
        prefetcher.submit(() -> {
            try {
                fetch(key);
            } catch (Exception e) {
                log.warn("Prefetch of a library file failed: {}", e.getMessage());
            }
        });
    }

    /** Marks a file in use (it won't be evicted until released). */
    public synchronized void pin(String key) {
        Path p = pathFor(key);
        pins.merge(p, 1, Integer::sum);
        lastUsed.put(p, useClock.incrementAndGet());
    }

    public synchronized void release(String key) {
        pins.computeIfPresent(pathFor(key), (k, n) -> n <= 1 ? null : n - 1);
    }

    private synchronized void touch(Path p) {
        lastUsed.put(p, useClock.incrementAndGet());
    }

    /** Removes least recently used, unpinned files until the cache fits its budget (never {@code keep}). */
    private synchronized void evictIfNeeded(Path keep) throws IOException {
        long total = 0;
        try (var files = Files.list(root)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                if (!p.getFileName().toString().endsWith(".part")) {
                    total += Files.size(p);
                    lastUsed.putIfAbsent(p, Long.MIN_VALUE);
                }
            }
        }
        if (total <= budgetBytes) {
            return;
        }
        var candidates = new java.util.ArrayList<>(lastUsed.entrySet());
        candidates.sort(Map.Entry.comparingByValue());
        for (Map.Entry<Path, Long> e : candidates) {
            if (total <= budgetBytes) {
                break;
            }
            Path p = e.getKey();
            if (pins.containsKey(p) || p.equals(keep)) {
                continue;
            }
            if (Files.exists(p)) {
                long size = Files.size(p);
                Files.deleteIfExists(p);
                total -= size;
                log.info("Evicted {} from the media cache", p.getFileName());
            }
            lastUsed.remove(p);
        }
    }

    /** A safe file name for a storage key: a hash (keys may contain slashes) plus the original extension. */
    Path pathFor(String key) {
        String ext = "";
        int dot = key.lastIndexOf('.');
        if (dot >= 0 && key.length() - dot <= 6) {
            ext = key.substring(dot).replaceAll("[^A-Za-z0-9.]", "");
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return SafePaths.resolveInside(root, HexFormat.of().formatHex(hash, 0, 16) + ext);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
