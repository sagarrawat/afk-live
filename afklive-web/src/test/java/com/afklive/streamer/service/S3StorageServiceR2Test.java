package com.afklive.streamer.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cloudflare R2 (and other S3-compatible stores) through {@link S3StorageService}: how the settings
 * resolve, and the shape of the requests and presigned URLs the app sends. A small in-process HTTP
 * server stands in for the bucket (no Docker), recording every request.
 */
class S3StorageServiceR2Test {

    private static final String R2 = "https://0123456789abcdef.r2.cloudflarestorage.com";

    record Seen(String method, String path, String query, Map<String, List<String>> headers) {
        boolean hasHeaderStartingWith(String prefix) {
            return headers.keySet().stream().anyMatch(h -> h.toLowerCase().startsWith(prefix));
        }

        String header(String name) {
            return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
                    .map(e -> e.getValue().get(0)).findFirst().orElse(null);
        }
    }

    private HttpServer server;
    private final List<Seen> seen = new CopyOnWriteArrayList<>();
    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

    @BeforeEach
    void startFakeBucket() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getRawPath();
            String query = ex.getRequestURI().getRawQuery();
            seen.add(new Seen(ex.getRequestMethod(), path, query, Map.copyOf(ex.getRequestHeaders())));
            byte[] body = ex.getRequestBody().readAllBytes();
            byte[] out = new byte[0];
            int status = 200;
            switch (ex.getRequestMethod()) {
                case "PUT" -> {
                    byte[] data = decodeChunked(ex.getRequestHeaders().getFirst("x-amz-content-sha256"), body);
                    objects.put(path, data);
                    ex.getResponseHeaders().add("ETag", "\"" + md5(data) + "\"");
                }
                case "POST" -> out = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><InitiateMultipartUploadResult>"
                        + "<Bucket>media</Bucket><Key>k</Key><UploadId>up-1</UploadId></InitiateMultipartUploadResult>")
                        .getBytes(StandardCharsets.UTF_8);
                case "GET" -> {
                    out = objects.getOrDefault(path, new byte[0]);
                    ex.getResponseHeaders().add("ETag", "\"" + md5(out) + "\"");
                }
                case "DELETE" -> status = 204;
                default -> status = 400;
            }
            ex.sendResponseHeaders(status, status == 204 ? -1 : out.length);
            if (status != 204) {
                ex.getResponseBody().write(out);
            }
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String fakeEndpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    // ------------------------------------------------------------------ settings

    @Test
    void anR2EndpointDefaultsToRegionAutoAndPathStyle() {
        var s = S3StorageService.Settings.resolve(R2, "", "media", "ak", "sk", "");
        assertThat(s.region()).isEqualTo(Region.of("auto"));
        assertThat(s.pathStyle()).isTrue();
        assertThat(s.endpoint()).isEqualTo(URI.create(R2));
    }

    @Test
    void otherEndpointsKeepTheOldDefaults() {
        var s = S3StorageService.Settings.resolve("https://blr1.digitaloceanspaces.com", "blr1", "media", "ak", "sk", null);
        assertThat(s.region()).isEqualTo(Region.of("blr1"));
        assertThat(s.pathStyle()).isFalse();
        var noRegion = S3StorageService.Settings.resolve("https://s3.example.com", "", "media", "ak", "sk", "");
        assertThat(noRegion.region()).isEqualTo(Region.US_EAST_1);
    }

    @Test
    void pathStyleCanBeForcedEitherWay() {
        assertThat(S3StorageService.Settings.resolve(R2, "auto", "m", "a", "s", "false").pathStyle()).isFalse();
        assertThat(S3StorageService.Settings.resolve("http://minio:9000", "", "m", "a", "s", "true").pathStyle()).isTrue();
    }

    @Test
    void anEndpointWithoutSchemeGetsHttps() {
        var s = S3StorageService.Settings.resolve("0123456789abcdef.r2.cloudflarestorage.com", "", "m", "a", "s", "");
        assertThat(s.endpoint().toString()).isEqualTo(R2);
        assertThat(s.region()).isEqualTo(Region.of("auto"));
    }

    @Test
    void missingEndpointOrBucketFailsAtStartupWithTheEnvName() {
        assertThatThrownBy(() -> S3StorageService.Settings.resolve("", "", "m", "a", "s", ""))
                .hasMessageContaining("STORAGE_ENDPOINT");
        assertThatThrownBy(() -> S3StorageService.Settings.resolve(R2, "", " ", "a", "s", ""))
                .hasMessageContaining("STORAGE_BUCKET");
    }

    @Test
    void settingsNeverPrintTheKeys() {
        var s = S3StorageService.Settings.resolve(R2, "", "media", "AKIDSECRET", "VERYSECRET", "");
        assertThat(s.toString()).doesNotContain("AKIDSECRET").doesNotContain("VERYSECRET");
    }

    // ------------------------------------------------------------------ request shapes

    private S3StorageService againstFakeBucket(String pathStyle) {
        return new S3StorageService(fakeEndpoint(), "auto", "media", "ak", "sk", pathStyle);
    }

    @Test
    void uploadsAndMultipartAreSignedForRegionAutoWithNoAclAndNoChecksumHeaders() throws Exception {
        var storage = againstFakeBucket("true");
        byte[] data = "hello r2".getBytes(StandardCharsets.UTF_8);

        String key = storage.uploadFile(new ByteArrayInputStream(data), "song.mp3", data.length);
        String uploadId = storage.beginMultipart(storage.newKey("big.mp4"));

        assertThat(uploadId).isEqualTo("up-1");
        assertThat(seen).hasSize(2);
        for (Seen r : seen) {
            assertThat(r.path()).startsWith("/media/");                       // path-style
            assertThat(r.hasHeaderStartingWith("x-amz-acl")).isFalse();       // R2 has no ACLs
            assertThat(r.hasHeaderStartingWith("x-amz-grant")).isFalse();
            assertThat(r.hasHeaderStartingWith("x-amz-checksum")).isFalse();  // no flexible checksums
            assertThat(r.hasHeaderStartingWith("x-amz-sdk-checksum")).isFalse();
            assertThat(r.header("x-amz-trailer")).isNull();
            assertThat(r.header("Authorization")).contains("/auto/s3/aws4_request");
        }
        assertThat(seen.get(0).method()).isEqualTo("PUT");
        assertThat(seen.get(0).path()).isEqualTo("/media/" + key);
        assertThat(seen.get(1).method()).isEqualTo("POST");
        assertThat(seen.get(1).query()).contains("uploads");
        assertThat(objects.get("/media/" + key)).isEqualTo(data);
    }

    @Test
    void readsComeBackFromTheSameBucketPath() throws Exception {
        var storage = againstFakeBucket("true");
        byte[] data = "clip bytes".getBytes(StandardCharsets.UTF_8);
        String key = storage.uploadFile(new ByteArrayInputStream(data), "clip.mp4", data.length);

        try (InputStream in = storage.downloadFile(key)) {
            assertThat(in.readAllBytes()).isEqualTo(data);
        }
        assertThat(seen.get(seen.size() - 1).path()).isEqualTo("/media/" + key);
    }

    @Test
    void presignedReadAndPartUrlsArePathStyleOnR2AndCarryNoAclOrChecksum() {
        var storage = new S3StorageService(S3StorageService.Settings.resolve(R2, "", "media", "ak", "sk", ""));

        URI read = URI.create(storage.generatePresignedUrl("abc_song.mp3").orElseThrow());
        URI part = URI.create(storage.presignPart("abc_big.mp4", "up-1", 3, 5_242_880).orElseThrow());

        for (URI u : List.of(read, part)) {
            assertThat(u.getHost()).isEqualTo("0123456789abcdef.r2.cloudflarestorage.com");
            assertThat(u.getScheme()).isEqualTo("https");
            String q = u.getQuery().toLowerCase();
            assertThat(q).contains("x-amz-credential=ak/").contains("/auto/s3/aws4_request");
            assertThat(q).doesNotContain("x-amz-acl").doesNotContain("checksum");
        }
        assertThat(read.getPath()).isEqualTo("/media/abc_song.mp3");
        assertThat(part.getPath()).isEqualTo("/media/abc_big.mp4");
        assertThat(part.getQuery()).contains("partNumber=3").contains("uploadId=up-1");
        // Only headers the browser sets by itself are signed (the part's exact length and the host).
        assertThat(part.getQuery()).contains("X-Amz-SignedHeaders=content-length;host&");
        assertThat(read.getQuery()).contains("X-Amz-SignedHeaders=host&");
    }

    @Test
    void virtualHostedStaysTheDefaultForSpaces() {
        var storage = new S3StorageService(S3StorageService.Settings.resolve(
                "https://blr1.digitaloceanspaces.com", "blr1", "media", "ak", "sk", ""));
        URI read = URI.create(storage.generatePresignedUrl("abc_song.mp3").orElseThrow());
        assertThat(read.getHost()).isEqualTo("media.blr1.digitaloceanspaces.com");
        assertThat(read.getPath()).isEqualTo("/abc_song.mp3");
    }

    // ------------------------------------------------------------------ helpers

    private static String md5(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Over plain HTTP the SDK may sign each chunk (aws-chunked); unwrap it so the stored bytes are the file. */
    private static byte[] decodeChunked(String contentSha, byte[] body) {
        if (contentSha == null || !contentSha.startsWith("STREAMING-")) {
            return body;
        }
        var out = new java.io.ByteArrayOutputStream();
        int i = 0;
        while (i < body.length) {
            int lineEnd = indexOfCrlf(body, i);
            String header = new String(body, i, lineEnd - i, StandardCharsets.US_ASCII);
            int size = Integer.parseInt(header.split(";")[0].trim(), 16);
            i = lineEnd + 2;
            if (size == 0) {
                break;
            }
            out.write(body, i, size);
            i += size + 2;
        }
        return out.toByteArray();
    }

    private static int indexOfCrlf(byte[] b, int from) {
        for (int i = from; i < b.length - 1; i++) {
            if (b[i] == '\r' && b[i + 1] == '\n') {
                return i;
            }
        }
        return b.length;
    }
}
