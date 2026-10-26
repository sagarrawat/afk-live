package com.afklive.streamer.endpoint;

import com.afklive.streamer.exception.NotFoundException;
import com.afklive.streamer.model.PlanConfig;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.StreamJobRepository;
import com.afklive.streamer.repository.UserRepository;
import com.afklive.streamer.service.PlanService;
import com.afklive.streamer.util.SecurityUtils;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** The signed-in account for the app (ON-2, ON-3): who they are, their plan, and their preferences. */
@RestController
@RequestMapping("/api/me")
public class MeController {

    /** GL-6: tips a creator can dismiss for good (on every device). */
    public static final Set<String> HINTS = Set.of("youtube-side");

    private final UserRepository users;
    private final PlanService plans;
    private final StreamJobRepository jobs;
    private final com.afklive.streamer.repository.UsageRepository usage;

    public MeController(UserRepository users, PlanService plans, StreamJobRepository jobs,
                        com.afklive.streamer.repository.UsageRepository usage) {
        this.users = users;
        this.plans = plans;
        this.jobs = jobs;
        this.usage = usage;
    }

    public record Plan(String id, String name, int streams, int maxHeight, int maxFps, long storageBytes, int monthlyHours,
                       int destinations, boolean watermark) {
    }

    /** {@code hoursUsed}: hours streamed this calendar month (UTC), against {@code plan.monthlyHours} (0 = no limit). */
    public record Me(Long id, String email, String name, String picture, boolean admin, Plan plan, String timeZone,
                     String currency, boolean onboarded, boolean emailAlerts, List<String> dismissedHints, long goLives,
                     double hoursUsed) {
    }

    /**
     * Fields are optional; {@code onboarded: true} marks the setup wizard done; {@code dismissHint} adds one
     * of {@link #HINTS} to the dismissed tips.
     */
    public record Update(String name, String timeZone, String currency, Boolean onboarded, Boolean emailAlerts,
                         String dismissHint) {
    }

    @GetMapping
    public Me me(Principal principal, @AuthenticationPrincipal Object auth) {
        return view(user(principal), auth);
    }

    @PatchMapping
    @Transactional
    public Me update(@RequestBody Update body, Principal principal, @AuthenticationPrincipal Object auth) {
        User u = user(principal);
        if (body.name() != null) {
            String n = body.name().trim();
            if (n.isEmpty() || n.length() > 100) {
                throw new IllegalArgumentException("Your name needs 1 to 100 characters.");
            }
            u.setFullName(n);
        }
        if (body.timeZone() != null) {
            try {
                u.setTimeZone(ZoneId.of(body.timeZone().trim()).getId());
            } catch (Exception e) {
                throw new IllegalArgumentException("Unknown time zone: " + body.timeZone());
            }
        }
        if (body.currency() != null) {
            String c = body.currency().trim().toUpperCase(Locale.ROOT);
            if (!c.equals("INR") && !c.equals("USD")) {
                throw new IllegalArgumentException("Currency must be INR or USD.");
            }
            u.setCurrency(c);
        }
        if (Boolean.TRUE.equals(body.onboarded()) && u.getOnboardedAt() == null) {
            u.setOnboardedAt(ZonedDateTime.now(ZoneId.of("UTC")));
        }
        if (body.emailAlerts() != null) {
            u.setEmailAlerts(body.emailAlerts());
        }
        if (body.dismissHint() != null) {
            String hint = body.dismissHint().trim();
            if (!HINTS.contains(hint)) {
                throw new IllegalArgumentException("Unknown tip: " + hint);
            }
            Set<String> dismissed = new LinkedHashSet<>(dismissed(u));
            dismissed.add(hint);
            u.setDismissedHints(String.join(",", dismissed));
        }
        users.save(u);
        return view(u, auth);
    }

    private User user(Principal principal) {
        if (principal == null) {
            throw new org.springframework.security.access.AccessDeniedException("Sign in first.");
        }
        return users.findByEmail(SecurityUtils.getEmail(principal)).orElseThrow(() -> new NotFoundException("Account not found."));
    }

    private Me view(User u, Object auth) {
        PlanConfig c = plans.getPlanConfig(u.getPlanType());
        String picture = u.getPictureUrl();
        if (picture == null && auth instanceof OAuth2User o && o.getAttribute("picture") != null) {
            picture = o.getAttribute("picture").toString();
        }
        return new Me(u.getId(), u.getEmail(), u.getFullName() == null ? u.getEmail() : u.getFullName(), picture,
                "ROLE_ADMIN".equals(u.getRole()),
                new Plan(u.getPlanType().name(), c.getDisplayName(), c.getMaxActiveStreams(), c.getMaxResolution(), c.getMaxFps(),
                        c.getMaxStorageBytes(), c.getMonthlyHours(), c.getMaxDestinations(), c.isWatermark()),
                u.getTimeZone(), u.getCurrency(), u.getOnboardedAt() != null, u.isEmailAlerts(), dismissed(u),
                jobs.countGoLives(u.getId()),
                Math.round(usage.secondsIn(u.getId(), com.afklive.streamer.service.BillingService.month(ZonedDateTime.now(ZoneId.of("UTC")))) / 36.0) / 100.0);
    }

    private static List<String> dismissed(User u) {
        String d = u.getDismissedHints();
        return d == null || d.isBlank() ? List.of() : Arrays.stream(d.split(",")).map(String::trim).filter(HINTS::contains).toList();
    }
}
