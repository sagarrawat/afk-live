package com.afklive.streamer.streaming;

import com.afklive.streamer.service.FileStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class MediaCacheTest {

    @TempDir
    Path dir;

    /** Storage that "downloads" 1 KB files and counts downloads. */
    static final class FakeStorage implements FileStorageService {
        final AtomicInteger downloads = new AtomicInteger();

        @Override public String uploadFile(InputStream in, String name, long len) { return name; }
        @Override public InputStream downloadFile(String key) { return InputStream.nullInputStream(); }
        @Override public void downloadFileToPath(String key, Path destination) {
            downloads.incrementAndGet();
            try {
                Files.write(destination, new byte[1024]);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        @Override public org.springframework.core.io.Resource loadFileAsResource(String key) { return null; }
        @Override public void deleteFile(String key) { }
        @Override public Optional<String> generatePresignedUrl(String key) { return Optional.empty(); }
        @Override public String newKey(String filename) { return filename; }
        @Override public String beginMultipart(String key) { throw new UnsupportedOperationException(); }
        @Override public Optional<String> presignPart(String key, String id, int n, long len) { return Optional.empty(); }
        @Override public String writePart(String key, String id, int n, InputStream in, long len) { throw new UnsupportedOperationException(); }
        @Override public java.util.List<Part> listParts(String key, String id) { return java.util.List.of(); }
        @Override public void completeMultipart(String key, String id, java.util.List<Part> parts) { }
        @Override public void abortMultipart(String key, String id) { }
        @Override public String readLocation(String key) { return key; }
        @Override public long size(String key) { return 1024; }
    }

    @Test
    void downloadsOnceAndKeepsPinnedFilesWhenOverBudget() throws Exception {
        FakeStorage storage = new FakeStorage();
        MediaCache cache = new MediaCache(storage, dir.toString(), 0); // budget 0: evict whatever it can
        cache.pin("a.mp4");
        Path a = cache.fetch("a.mp4");
        assertThat(cache.fetch("a.mp4")).isEqualTo(a);
        assertThat(storage.downloads.get()).isEqualTo(1);

        Path b = cache.fetch("b.mp4");
        assertThat(a).exists(); // pinned: in use by a playing item
        Path c = cache.fetch("c.mp4");
        assertThat(b).doesNotExist(); // least recently used and not pinned
        assertThat(c).exists(); // the file just fetched is never evicted
        cache.release("a.mp4");
        cache.fetch("d.mp4");
        assertThat(a).doesNotExist();
    }

    @Test
    void filesFromAnEarlierRunCountAndAreEvictedFirst() throws Exception {
        FakeStorage storage = new FakeStorage();
        MediaCache first = new MediaCache(storage, dir.toString(), 50);
        Path old = first.fetch("old.mp4");
        Path interrupted = old.resolveSibling("x.mp4.abcd1234.part");
        Files.writeString(interrupted, "partial");
        Files.setLastModifiedTime(interrupted, java.nio.file.attribute.FileTime.fromMillis(
                System.currentTimeMillis() - MediaCache.STALE_PART.toMillis() - 60_000));

        MediaCache restarted = new MediaCache(storage, dir.toString(), 0);
        assertThat(interrupted).doesNotExist(); // interrupted downloads are cleaned up
        Path fresh = restarted.fetch("new.mp4");
        assertThat(old).doesNotExist();
        assertThat(fresh).exists();
    }

    /** OPS-3: during a deploy two worker processes share the cache; one starting never breaks the other's downloads. */
    @Test
    void aSecondProcessLeavesDownloadsInProgressAlone() throws Exception {
        FakeStorage storage = new FakeStorage();
        MediaCache running = new MediaCache(storage, dir.toString(), 50);
        running.fetch("seed.mp4");
        Path inProgress = dir.resolve("cache").resolve("y.mp4.0f0f0f0f.part");
        Files.writeString(inProgress, "downloading");

        MediaCache starting = new MediaCache(storage, dir.toString(), 50);
        assertThat(inProgress).exists();

        // Both fetch the same file: each writes its own partial file, and both end with the complete copy.
        Path a = running.fetch("same.mp4");
        Path b = starting.fetch("same.mp4");
        assertThat(a).isEqualTo(b).exists();
        try (var files = Files.list(dir.resolve("cache"))) {
            assertThat(files.filter(p -> p.toString().endsWith(".part")).toList()).containsExactly(inProgress);
        }
    }
}
