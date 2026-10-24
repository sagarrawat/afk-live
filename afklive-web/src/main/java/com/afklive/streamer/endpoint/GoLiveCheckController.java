package com.afklive.streamer.endpoint;

import com.afklive.streamer.service.GoLiveCheckService;
import com.afklive.streamer.service.GoLiveCheckService.CheckView;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

/** GR-9: the go-live check card on a stream's page. Separate from going live: it never blocks it. */
@RestController
@RequestMapping("/api/streams/{id}/check")
public class GoLiveCheckController {

    private final GoLiveCheckService checks;

    public GoLiveCheckController(GoLiveCheckService checks) {
        this.checks = checks;
    }

    /** {@code thumbnailMediaId}: an image from the library; {@code clearThumbnail}: remove it. A null title leaves it as it is. */
    public record TitleRequest(String youtubeTitle, Long thumbnailMediaId, Boolean clearThumbnail) {
    }

    @GetMapping
    public CheckView get(@PathVariable Long id, Principal principal) {
        return checks.check(principal.getName(), id);
    }

    @PutMapping("/title")
    public CheckView title(@PathVariable Long id, @RequestBody TitleRequest req, Principal principal) {
        return checks.setTitleAndThumbnail(principal.getName(), id, req.youtubeTitle(), req.thumbnailMediaId(),
                Boolean.TRUE.equals(req.clearThumbnail()));
    }

    @PostMapping("/{flag}/fix")
    public CheckView fix(@PathVariable Long id, @PathVariable String flag, Principal principal) {
        return checks.fix(principal.getName(), id, flag);
    }

    /** Keep it as it is: the flag stays listed as kept, and the weekly check skips it. */
    @PostMapping("/{flag}/dismiss")
    public CheckView dismiss(@PathVariable Long id, @PathVariable String flag, Principal principal) {
        return checks.dismiss(principal.getName(), id, flag, true);
    }

    @DeleteMapping("/{flag}/dismiss")
    public CheckView restore(@PathVariable Long id, @PathVariable String flag, Principal principal) {
        return checks.dismiss(principal.getName(), id, flag, false);
    }
}
