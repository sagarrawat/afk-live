package com.afklive.streamer.service;

import com.afklive.streamer.model.LiveStream;
import com.afklive.streamer.repository.LiveStreamRepository;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.ZonedDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Phone streaming (MS-1): a private ingest address and key per stream, QR codes for phone apps,
 * and the ingest server's (MediaMTX) authorization: phones may publish only to a valid stream key;
 * only workers, with the internal read credentials, may read.
 */
@Service
@Slf4j
public class IngestService {

    private static final String ALPHABET = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();
    /** MediaMTX path prefix for phone streams. */
    public static final String PATH_PREFIX = "live/";

    private final LiveStreamRepository streams;
    private final String publicHost;
    private final int rtmpPort;
    private final int srtPort;
    private final String readAllowed;
    private final com.afklive.streamer.repository.StreamJobRepository jobs;
    private final String readUser;
    private final String readPass;
    private final String authSecret;

    public IngestService(LiveStreamRepository streams, com.afklive.streamer.repository.StreamJobRepository jobs,
                         @Value("${app.ingest.public-host:}") String publicHost,
                         @Value("${app.ingest.rtmp-port:1935}") int rtmpPort,
                         @Value("${app.ingest.srt-port:8890}") int srtPort,
                         @Value("${app.ingest.read-allowed:127.0.0.0/8,::1/128,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16,fc00::/7}") String readAllowed,
                         @Value("${app.ingest.read-user:worker}") String readUser,
                         @Value("${app.ingest.read-pass:}") String readPass,
                         @Value("${app.ingest.auth-secret:}") String authSecret) {
        this.streams = streams;
        this.publicHost = publicHost;
        this.rtmpPort = rtmpPort;
        this.srtPort = srtPort;
        this.readAllowed = readAllowed;
        this.jobs = jobs;
        this.readUser = readUser;
        this.readPass = readPass;
        this.authSecret = authSecret;
    }

    public boolean enabled() {
        return publicHost != null && !publicHost.isBlank();
    }

    /** What the phone app needs, and ready-made links/QR payloads for popular apps. */
    public record Setup(String rtmpServer, String streamKey, String rtmpUrl, String srtUrl, int srtLatencyMs,
                        String larixLink, String larixSrtLink, ZonedDateTime lastConnectedAt, Map<String, String> guides) {
    }

    /** Makes sure the stream has an ingest key (creating one the first time, under the stream's row lock). */
    @Transactional
    public LiveStream ensureKey(LiveStream s) {
        if (s.getIngestKey() != null) {
            return s;
        }
        LiveStream locked = streams.lockById(s.getId()).orElseThrow();
        if (locked.getIngestKey() == null) {
            assignNewKey(locked);
            locked = streams.save(locked);
        }
        return locked;
    }

    /** Workers read from these networks only (CIDRs; private networks and this host by default). */
    boolean readerAllowed(String ip) {
        if (ip == null || ip.isBlank()) {
            return false;
        }
        for (String cidr : readAllowed.split(",")) {
            if (com.afklive.streamer.util.Cidr.contains(cidr.trim(), ip.trim())) {
                return true;
            }
        }
        return false;
    }

    /** MS-1: a new key; the old one stops working at once. */
    @Transactional
    public LiveStream regenerate(LiveStream s) {
        assignNewKey(s);
        s.setIngestLastPublishAt(null);
        return streams.save(s);
    }

    private static void assignNewKey(LiveStream s) {
        StringBuilder sb = new StringBuilder(24);
        for (int i = 0; i < 24; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        s.setIngestKey(sb.toString());
        s.setIngestKeyHash(hash(sb.toString()));
    }

    public Setup setup(LiveStream s) {
        if (!enabled()) {
            throw new IllegalStateException("Phone streaming isn't available on this server yet.");
        }
        String key = s.getIngestKey();
        String server = "rtmp://" + publicHost + (rtmpPort == 1935 ? "" : ":" + rtmpPort) + "/live";
        String rtmpUrl = server + "/" + key;
        String srtUrl = srtUrl(key, s.getSrtLatencyMs());
        String name = "AFK Live - " + s.getName();
        String larix = "larix://set/v1?conn[][url]=" + enc(rtmpUrl) + "&conn[][name]=" + enc(name)
                + "&conn[][mode]=va&conn[][overwrite]=on";
        String larixSrt = "larix://set/v1?conn[][url]=" + enc("srt://" + publicHost + ":" + srtPort)
                + "&conn[][name]=" + enc(name + " (SRT)") + "&conn[][mode]=va"
                + "&conn[][srtstreamid]=" + enc("publish:" + PATH_PREFIX + key)
                + "&conn[][srtlatency]=" + s.getSrtLatencyMs() + "&conn[][overwrite]=on";
        Map<String, String> guides = new LinkedHashMap<>();
        guides.put("larix", "Install Larix Broadcaster, scan the Larix QR code with your camera, then tap the record button.");
        guides.put("prism", "In PRISM Live Studio choose Custom RTMP, paste the server and stream key below (or scan the RTMP QR code).");
        guides.put("streamlabs", "In Streamlabs mobile go to Settings > Custom RTMP and paste the server and stream key.");
        guides.put("srt", "Apps with SRT (better on weak signal): use the SRT address; latency " + s.getSrtLatencyMs() + " ms.");
        return new Setup(server, key, rtmpUrl, srtUrl, s.getSrtLatencyMs(), larix, larixSrt, s.getIngestLastPublishAt(), guides);
    }

    String srtUrl(String key, int latencyMs) {
        return "srt://" + publicHost + ":" + srtPort + "?streamid=publish:" + PATH_PREFIX + key + "&latency=" + (latencyMs * 1000L);
    }


    /** A QR code (PNG) for a link or address. */
    public byte[] qrPng(String content, int size) {
        try {
            BitMatrix m = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size,
                    Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M, EncodeHintType.MARGIN, 2));
            BufferedImage img = new BufferedImage(m.getWidth(), m.getHeight(), BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < m.getHeight(); y++) {
                for (int x = 0; x < m.getWidth(); x++) {
                    img.setRGB(x, y, m.get(x, y) ? 0x000000 : 0xffffff);
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Couldn't make the QR code", e);
        }
    }

    // ------------------------------------------------------------------ ingest server authorization

    /** The JSON MediaMTX posts to its HTTP auth endpoint. */
    public record AuthRequest(String user, String password, String token, String ip, String action, String path,
                              String protocol, String id, String query, String userAgent) {
    }

    /** True when the shared secret on the auth call matches (constant time). */
    public boolean authCallerOk(String secret) {
        if (authSecret == null || authSecret.isBlank() || secret == null) {
            return false;
        }
        // Compare digests: equal length whatever was sent, so the comparison leaks nothing.
        return MessageDigest.isEqual(hash(authSecret).getBytes(StandardCharsets.UTF_8), hash(secret).getBytes(StandardCharsets.UTF_8));
    }

    /** Publishing needs a valid stream key in the path; reading needs the workers' credentials. */
    @Transactional
    public boolean authorize(AuthRequest req) {
        if (req == null || req.action() == null || req.path() == null) {
            return false;
        }
        switch (req.action()) {
            case "publish" -> {
                Optional<LiveStream> s = streamForPath(req.path());
                if (s.isEmpty() || s.get().getSource() != LiveStream.Source.PHONE) {
                    log.info("Rejected phone publish to an unknown key from {}", req.ip());
                    return false;
                }
                // Only while the stream is live (a leaked key isn't free ingest) and the account is active.
                // Phone apps retry on their own, so starting the app before pressing Go Live just works a moment later.
                if (!s.get().getUser().isEnabled() || jobs.findAllByStreamIdAndIsLiveTrue(s.get().getId()).isEmpty()) {
                    return false;
                }
                streams.markIngestPublish(s.get().getId(), ZonedDateTime.now());
                return true;
            }
            case "read" -> {
                boolean ok = readPass != null && !readPass.isBlank()
                        && MessageDigest.isEqual(hash(readUser).getBytes(StandardCharsets.UTF_8), hash(nz(req.user())).getBytes(StandardCharsets.UTF_8))
                        && MessageDigest.isEqual(hash(readPass).getBytes(StandardCharsets.UTF_8), hash(nz(req.password())).getBytes(StandardCharsets.UTF_8));
                if (ok && !readerAllowed(req.ip())) {
                    log.warn("Rejected a read with valid worker credentials from {} (not a worker address)", req.ip());
                    return false;
                }
                return ok && streamForPath(req.path()).isPresent();
            }
            default -> {
                return false; // api, metrics, playback, pprof: never from outside
            }
        }
    }

    private Optional<LiveStream> streamForPath(String path) {
        String p = path.startsWith("/") ? path.substring(1) : path;
        if (!p.startsWith(PATH_PREFIX)) {
            return Optional.empty();
        }
        String key = p.substring(PATH_PREFIX.length());
        if (key.isEmpty() || key.length() > 64 || key.contains("/")) {
            return Optional.empty();
        }
        return streams.findByIngestKeyHash(hash(key));
    }

    static String hash(String key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    private static String nz(String v) {
        return v == null ? "" : v;
    }

    /** For tests: QR images must decode back to their content. */
    static BufferedImage read(byte[] png) throws IOException {
        return ImageIO.read(new java.io.ByteArrayInputStream(png));
    }
}
