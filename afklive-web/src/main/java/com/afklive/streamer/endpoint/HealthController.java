package com.afklive.streamer.endpoint;

import com.afklive.streamer.service.HealthzProbe;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * OPS: the public health check for an outside uptime monitor (UptimeRobot, Better Stack; setup in
 * docs/operations.md). {@code GET /healthz} answers 200 {@code ok} when this web app and the database
 * answer, 503 {@code unavailable} otherwise; {@code ?workers=1} also needs a stream server that reported
 * in the last two minutes ({@link HealthzProbe#WORKER_FRESH}). It never says why (no error text,
 * versions or counts), is never cached and asks search engines not to index it. Open to everyone
 * (HealthzSecurityConfig) and outside the API rate limiter. The localhost-only actuator readiness probe
 * (ActuatorSecurityConfig) is separate and unchanged.
 */
@RestController
public class HealthController {

    private final HealthzProbe probe;

    public HealthController(HealthzProbe probe) {
        this.probe = probe;
    }

    @GetMapping(value = "/healthz", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> healthz(@RequestParam(required = false) String workers) {
        boolean withWorkers = workers != null && !workers.isBlank() && !"0".equals(workers) && !"false".equalsIgnoreCase(workers);
        boolean ok = probe.healthy(withWorkers);
        return ResponseEntity.status(ok ? 200 : 503)
                .cacheControl(CacheControl.noStore())
                .header("X-Robots-Tag", "noindex")
                .contentType(MediaType.TEXT_PLAIN)
                .body(ok ? "ok" : "unavailable");
    }
}
