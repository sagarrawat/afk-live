package com.afklive.streamer.endpoint;

import com.afklive.streamer.streaming.StreamWorker;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OPS-3: the deploy script ({@code afklive-scripts/worker-handover-deploy.sh}) asks the running worker
 * to hand its streams to the new version, and watches it do so. Only in the worker process (whose web
 * server listens on 127.0.0.1), only from this machine, and only with the shared
 * {@code WORKER_CONTROL_TOKEN}; without a token it answers 403 to everything.
 * <p>
 * Draining is recorded in the database ({@code stream_workers.draining_since}) and the worker reads it
 * from there on every pass; streams move only when another worker takes them, through conditional
 * updates like every other ownership change. The script talks to this endpoint rather than the
 * database so it needs no database credentials and addresses exactly the old process.
 */
@RestController
@ConditionalOnExpression("'${app.streaming.role:all}' == 'worker'")
public class WorkerControlController {

    private final StreamWorker worker;
    private final byte[] token;

    public WorkerControlController(StreamWorker worker, @Value("${app.streaming.worker.control-token:}") String token) {
        this.worker = worker;
        this.token = token == null ? new byte[0] : token.getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping("/internal/worker/status")
    public ResponseEntity<Map<String, Object>> status(@RequestHeader(name = "X-Worker-Token", required = false) String given,
                                                      HttpServletRequest request) {
        if (!allowed(given, request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(body());
    }

    @PostMapping("/internal/worker/drain")
    public ResponseEntity<Map<String, Object>> drain(@RequestHeader(name = "X-Worker-Token", required = false) String given,
                                                     HttpServletRequest request) {
        if (!allowed(given, request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        worker.drain();
        return ResponseEntity.ok(body());
    }

    @PostMapping("/internal/worker/undrain")
    public ResponseEntity<Map<String, Object>> undrain(@RequestHeader(name = "X-Worker-Token", required = false) String given,
                                                       HttpServletRequest request) {
        if (!allowed(given, request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        worker.stopDraining();
        return ResponseEntity.ok(body());
    }

    private Map<String, Object> body() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("workerId", worker.workerId());
        m.put("ready", worker.ready());
        m.put("draining", worker.isDraining());
        m.put("owned", worker.ownedInDatabase());
        m.put("running", worker.ownedCount());
        m.put("incoming", worker.incomingCount());
        return m;
    }

    private boolean allowed(String given, HttpServletRequest request) {
        if (token.length == 0 || given == null || !loopback(request.getRemoteAddr())) {
            return false;
        }
        return MessageDigest.isEqual(token, given.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean loopback(String address) {
        try {
            return address != null && InetAddress.getByName(address).isLoopbackAddress();
        } catch (Exception e) {
            return false;
        }
    }
}
