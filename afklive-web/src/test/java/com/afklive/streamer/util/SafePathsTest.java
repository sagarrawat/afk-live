package com.afklive.streamer.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SafePathsTest {

    @TempDir
    Path root;

    @Test
    void resolvesNormalNamesInsideRoot() {
        assertThat(SafePaths.resolveInside(root, "abc_video.mp4")).isEqualTo(root.toAbsolutePath().normalize().resolve("abc_video.mp4"));
    }

    @Test
    void rejectsTraversalAndAbsolutePaths() {
        assertThatThrownBy(() -> SafePaths.resolveInside(root, "../../etc/passwd")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> SafePaths.resolveInside(root, "/etc/passwd")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> SafePaths.resolveInside(root, "a/../../x")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> SafePaths.resolveInside(root, ".")).isInstanceOf(SecurityException.class);
    }

    @Test
    void sanitizeKeepsOnlyTheFileName() {
        assertThat(SafePaths.sanitizeFilename("../../etc/passwd")).isEqualTo("passwd");
        assertThat(SafePaths.sanitizeFilename("..\\..\\windows\\evil.mp4")).isEqualTo("evil.mp4");
        assertThat(SafePaths.sanitizeFilename("dir/My Video (final).mp4")).isEqualTo("My Video _final_.mp4");
        assertThat(SafePaths.sanitizeFilename(".hidden")).isEqualTo("hidden");
        assertThat(SafePaths.sanitizeFilename("..")).isEqualTo("file");
        assertThat(SafePaths.sanitizeFilename(null)).isEqualTo("file");
    }

    @Test
    void limitedStreamCountsAndStopsAtTheLimit() throws IOException {
        byte[] data = new byte[1000];
        LimitedInputStream ok = new LimitedInputStream(new ByteArrayInputStream(data), 1000);
        assertThat(ok.readAllBytes()).hasSize(1000);
        assertThat(ok.getCount()).isEqualTo(1000);

        InputStream tooBig = new LimitedInputStream(new ByteArrayInputStream(data), 999);
        assertThatThrownBy(tooBig::readAllBytes).isInstanceOf(LimitedInputStream.LimitExceededException.class);
    }
}
