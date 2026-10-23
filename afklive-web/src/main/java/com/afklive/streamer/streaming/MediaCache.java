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

    private final FileStorageService storage;
    private final Path root;
    private final long budgetBytes;
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final Map<String, Integer> pins = new HashMap<>();
    private final Map<String, Long> lastUsed = new HashMap<>();
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
    }

    /** The local copy of {@code key}, downloading it now if needed. Blocks; never call from the studio clock. */
    public Path fetch(String key) throws IOException {
        Path target = pathFor(key);
        ReentrantLock lock = locks.computeIfAbsent(key, k -> new ReentrantLock());
        lock.lock();
        try {
            if (!Files.exists(target)) {
                Files.createDirectories(root);
                Path partial = target.resolveSibling(target.getFileName() + ".part");
                long started = System.nanoTime();
                storage.downloadFileToPath(key, partial);
                Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                log.info("Cached {} ({} MB in {} ms)", target.getFileName(), Files.size(target) / (1024 * 1024),
                        (System.nanoTime() - started) / 1_000_000);
                evictIfNeeded();
            }
            touch(key);
            return target;
        } finally {
            lock.unlock();
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
        pins.merge(key, 1, Integer::sum);
        lastUsed.put(key, System.nanoTime());
    }

    public synchronized void release(String key) {
        pins.computeIfPresent(key, (k, n) -> n <= 1 ? null : n - 1);
    }

    private synchronized void touch(String key) {
        lastUsed.put(key, System.nanoTime());
    }

    private synchronized void evictIfNeeded() throws IOException {
        long total = 0;
        Map<Path, String> byPath = new HashMap<>();
        try (var files = Files.list(root)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                if (!p.getFileName().toString().endsWith(".part")) {
                    total += Files.size(p);
                }
            }
        }
        if (total <= budgetBytes) {
            return;
        }
        for (String key : lastUsed.keySet()) {
            byPath.put(pathFor(key), key);
        }
        var candidates = new java.util.ArrayList<>(lastUsed.entrySet());
        candidates.sort(Map.Entry.comparingByValue());
        for (Map.Entry<String, Long> e : candidates) {
            if (total <= budgetBytes) {
                break;
            }
            if (pins.containsKey(e.getKey())) {
                continue;
            }
            Path p = pathFor(e.getKey());
            if (Files.exists(p)) {
                long size = Files.size(p);
                Files.deleteIfExists(p);
                total -= size;
                lastUsed.remove(e.getKey());
                log.info("Evicted {} from the media cache", p.getFileName());
            }
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
