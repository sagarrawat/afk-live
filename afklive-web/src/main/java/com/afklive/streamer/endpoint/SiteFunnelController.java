package com.afklive.streamer.endpoint;

import com.afklive.streamer.service.SiteFunnelService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The homepage funnel (SiteFunnelService). The site sends {@code view} or {@code start} as the plain-text
 * body of a beacon; nothing identifying is read or kept. Admins read daily counts at /api/admin/funnel
 * ({@code /api/admin/**} is ADMIN-only in SecurityConfig).
 */
@RestController
public class SiteFunnelController {

    private final SiteFunnelService funnel;

    public SiteFunnelController(SiteFunnelService funnel) {
        this.funnel = funnel;
    }

    @PostMapping("/api/public/funnel")
    public ResponseEntity<Void> count(@RequestBody(required = false) String step,
                                      @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {
        if (step != null && step.length() <= 20) {
            funnel.record(step, userAgent);
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/admin/funnel")
    public List<SiteFunnelService.Day> daily(@RequestParam(defaultValue = "30") int days) {
        return funnel.daily(days);
    }
}
