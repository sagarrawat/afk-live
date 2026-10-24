package com.afklive.streamer.endpoint;

import com.afklive.streamer.service.AdminConsoleService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The admin console's overview, global search and server history (AD-1, AD-2, AD-3). Read-only.
 * {@code /api/admin/**} is ADMIN-only in SecurityConfig.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminConsoleController {

    private final AdminConsoleService console;

    public AdminConsoleController(AdminConsoleService console) {
        this.console = console;
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
}
