package com.afklive.streamer.endpoint;

import com.afklive.streamer.service.ChannelService;
import com.afklive.streamer.service.YouTubeService;
import com.afklive.streamer.util.SecurityUtils;
import com.google.api.services.youtubeAnalytics.v2.model.QueryResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/analytics")
@RequiredArgsConstructor
public class AnalyticsController {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AnalyticsController.class);

    private final YouTubeService youTubeService;
    private final ChannelService channelService;

    @GetMapping
    public Map<String, Object> getAnalytics(
            Principal principal,
            @RequestParam(required = false) String range) {

        if (principal == null) {
            throw new IllegalStateException("Not authenticated");
        }
        String username = SecurityUtils.getEmail(principal);

        // Calculate dates
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(28); // Default

        if (range != null) {
            switch (range) {
                case "7": start = end.minusDays(7); break;
                case "28": start = end.minusDays(28); break;
                case "90": start = end.minusDays(90); break;
                case "365": start = end.minusDays(365); break;
                // 'lifetime' or others can default to a long range or handled specifically
            }
        }

        String startDate = start.format(DateTimeFormatter.ISO_DATE);
        String endDate = end.format(DateTimeFormatter.ISO_DATE);

        try {
            java.util.List<com.afklive.streamer.model.SocialChannel> channels = channelService.getChannels(username);

            // Aggregation maps: Date -> Summed Metrics
            java.util.TreeMap<String, Long> viewsByDate = new java.util.TreeMap<>();
            java.util.TreeMap<String, Long> subsByDate = new java.util.TreeMap<>();
            java.util.TreeMap<String, Long> minsByDate = new java.util.TreeMap<>();

            long totalViews = 0;
            long totalSubs = 0;
            long totalMins = 0;

            for (com.afklive.streamer.model.SocialChannel ch : channels) {
                if ("YOUTUBE".equals(ch.getPlatform()) && ch.getCredentialId() != null) {
                    try {
                        QueryResponse response = youTubeService.getChannelAnalytics(ch.getCredentialId(), startDate, endDate);
                        if (response.getRows() != null) {
                            for (List<Object> row : response.getRows()) {
                                String date = row.get(0).toString();
                                long v = ((Number) row.get(1)).longValue();
                                long s = ((Number) row.get(2)).longValue();
                                long m = ((Number) row.get(3)).longValue();

                                viewsByDate.merge(date, v, Long::sum);
                                subsByDate.merge(date, s, Long::sum);
                                minsByDate.merge(date, m, Long::sum);

                                totalViews += v;
                                totalSubs += s;
                                totalMins += m;
                            }
                        }
                    } catch (Exception e) {
                        log.warn("Analytics fetch failed for channel {}", ch.getName(), e);
                    }
                }
            }

            // Transform for Frontend
            List<String> labels = new ArrayList<>(viewsByDate.keySet());
            List<Long> views = new ArrayList<>(viewsByDate.values());
            List<Long> subs = new ArrayList<>(subsByDate.values());
            List<Long> mins = new ArrayList<>(minsByDate.values());

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
