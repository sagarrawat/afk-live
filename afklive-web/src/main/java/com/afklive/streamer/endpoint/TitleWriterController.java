package com.afklive.streamer.endpoint;

import com.afklive.streamer.service.TitleWriterService;
import com.afklive.streamer.service.TitleWriterService.Result;
import com.afklive.streamer.service.TitleWriterService.Status;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

/**
 * AI-1 / GR-10: titles, a description and tags for one stream, written as drafts the creator edits and
 * copies. Always answers 200 with a status (OK, FALLBACK, LIMIT, UNAVAILABLE), so a missing AI key or a
 * used-up limit is a message in the app, never an error page.
 */
@RestController
@RequestMapping("/api/streams/{id}/ai/metadata")
public class TitleWriterController {

    private final TitleWriterService writer;

    public TitleWriterController(TitleWriterService writer) {
        this.writer = writer;
    }

    /** @param language en, hi, ta, te, bn or mr (null: the last one chosen, else English); brief: optional, ≤ 200 characters */
    public record WriteRequest(String language, String brief) {
    }

    @GetMapping
    public Status status(@PathVariable Long id, Principal principal) {
        return writer.status(principal.getName(), id);
    }

    @PostMapping
    public Result write(@PathVariable Long id, @RequestBody(required = false) WriteRequest req, Principal principal) {
        return writer.write(principal.getName(), id, req == null ? null : req.language(), req == null ? null : req.brief());
    }
}
