package com.afklive.streamer.endpoint;

import com.afklive.streamer.service.PublicTitleWriter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * MK-7: the free title and description writer on the public site, no sign-in. The POST needs the CSRF token like
 * every other form (the page reads the XSRF-TOKEN cookie the status call sets); limits and Turnstile are in
 * {@link PublicTitleWriter}. Also under the API's per-IP rate limit for expensive calls (RateLimitFilter).
 */
@RestController
@RequestMapping("/api/public/title-writer")
public class PublicTitleWriterController {

    private final PublicTitleWriter writer;

    public PublicTitleWriterController(PublicTitleWriter writer) {
        this.writer = writer;
    }

    @GetMapping("/status")
    public ResponseEntity<PublicTitleWriter.Status> status() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(writer.status());
    }

    @PostMapping
    public ResponseEntity<PublicTitleWriter.Result> write(@RequestBody PublicTitleWriter.Request req, HttpServletRequest http) {
        // forward-headers-strategy=native: behind Caddy this is the visitor's address.
        PublicTitleWriter.Result r = writer.write(req, http.getRemoteAddr());
        return ResponseEntity.status(PublicTitleWriter.httpStatus(r.status())).cacheControl(CacheControl.noStore()).body(r);
    }
}
