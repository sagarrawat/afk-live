package com.afklive.streamer.endpoint;

import com.afklive.streamer.service.StatusService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;

/**
 * RE-7: the public status data behind /status (and the homepage's proof line). No sign-in;
 * platform-level only (see StatusService): no user, stream or worker names, and only two platform
 * totals, left out while they're small.
 */
@RestController
public class StatusController {

    private final StatusService status;

    public StatusController(StatusService status) {
        this.status = status;
    }

    @GetMapping("/api/public/status")
    public ResponseEntity<StatusService.PublicStatus> status() {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(15, TimeUnit.SECONDS).cachePublic()).body(status.current());
    }
}
