package com.afklive.streamer.endpoint;

import com.afklive.streamer.service.AdminConsoleService;
import com.afklive.streamer.service.CostMeter;
import com.afklive.streamer.service.PlayoutCopies;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * The admin console's overview, global search, server history (AD-1, AD-2, AD-3), costs (FO-3) and
 * prepared copies (FO-6). Read-only.
 * {@code /api/admin/**} is ADMIN-only in SecurityConfig.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminConsoleController {

    private final AdminConsoleService console;
    private final CostMeter costs;
    private final PlayoutCopies copies;

    public AdminConsoleController(AdminConsoleService console, CostMeter costs, PlayoutCopies copies) {
        this.console = console;
        this.costs = costs;
        this.copies = copies;
    }

    /** What needs attention now, streams by health, today's sign-ups and first go-lives. */
    @GetMapping("/attention")
    public AdminConsoleService.Overview attention() {
        return console.overview();
    }

    @GetMapping("/search")
    public AdminConsoleService.SearchResult search(@RequestParam(required = false) String q) {
        return console.search(q);
    }

    /** Each worker's CPU and memory over the last {@code hours} (default 24, at most 168). */
    @GetMapping("/servers/history")
    public List<AdminConsoleService.ServerHistory> history(@RequestParam(defaultValue = "24") int hours) {
        return console.history(hours);
    }

    /** FO-3: cost per stream-hour this month (overall, by plan, by quality), traffic, projection and plan margins. */
    @GetMapping("/costs")
    public CostMeter.Report costs() {
        return costs.report(ZonedDateTime.now(ZoneOffset.UTC));
    }

    /** FO-6: prepared copies (count and storage, by kind) and how many are due for deletion. */
    @GetMapping("/copies")
    public PlayoutCopies.Stats copies() {
        return copies.stats();
    }
}
