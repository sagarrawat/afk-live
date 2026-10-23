package com.afklive.streamer.endpoint;

import com.afklive.streamer.service.IngestService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Called by the ingest server (MediaMTX, authMethod: http) for every phone that publishes and every
 * worker that reads. Not for browsers: it requires the shared secret configured on both sides.
 * 2xx allows, anything else denies.
 */
@RestController
@RequiredArgsConstructor
public class IngestAuthController {

    private final IngestService ingest;
    private final com.afklive.streamer.service.StatusService status;

    @PostMapping("/internal/ingest/auth")
    public ResponseEntity<Void> auth(@RequestParam(name = "secret", required = false) String secret,
                                     @RequestBody(required = false) IngestService.AuthRequest req) {
        if (!ingest.authCallerOk(secret)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        // RE-7: the ingest server is up and reaching us (the status page's phone streaming signal).
        status.ingestSeen();
        return ingest.authorize(req) ? ResponseEntity.ok().build() : ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
}
