package com.afklive.streamer.endpoint;

import com.afklive.streamer.service.YouTubeService;
import com.google.api.services.youtubeAnalytics.v2.model.QueryResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/analytics")
@RequiredArgsConstructor
@Slf4j
public class AnalyticsController {

    private final YouTubeService youTubeService;

    @GetMapping
    public Map<String, Object> getAnalytics(
            @AuthenticationPrincipal OAuth2User principal,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {

        if (principal == null) {
            throw new IllegalStateException("Not authenticated");
        }
        String username = principal.getName();

        // Default to last 28 days if not provided
        if (startDate == null) {
            startDate = LocalDate.now().minusDays(28).format(DateTimeFormatter.ISO_DATE);
        }
        if (endDate == null) {
            endDate = LocalDate.now().format(DateTimeFormatter.ISO_DATE);
        }

        try {
            QueryResponse response = youTubeService.getChannelAnalytics(username, startDate, endDate);

            // Transform for Frontend (Chart.js)
            // Response rows are: [day, views, subsGained, minWatched]
            List<String> labels = new ArrayList<>();
            List<Long> views = new ArrayList<>();
            List<Long> subs = new ArrayList<>();
            List<Long> mins = new ArrayList<>();

            long totalViews = 0;
            long totalSubs = 0;
            long totalMins = 0;

            if (response.getRows() != null) {
                for (List<Object> row : response.getRows()) {
                    // row.get(0) is date string
                    labels.add(row.get(0).toString());

                    long v = ((Number) row.get(1)).longValue();
                    long s = ((Number) row.get(2)).longValue();
                    long m = ((Number) row.get(3)).longValue();

                    views.add(v);
                    subs.add(s);
                    mins.add(m);

                    totalViews += v;
                    totalSubs += s;
                    totalMins += m;
                }
            }

            Map<String, Object> result = new HashMap<>();
            result.put("labels", labels);
            result.put("views", views);
            result.put("subs", subs);
            result.put("watchTime", mins);

            result.put("summary", Map.of(
                "totalViews", totalViews,
                "totalSubs", totalSubs,
                "totalWatchTime", totalMins
            ));

            return result;

        } catch (Exception e) {
            log.error("Error fetching analytics", e);
            throw new RuntimeException("Failed to fetch analytics: " + e.getMessage());
        }
    }
}
