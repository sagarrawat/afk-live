package com.afklive.streamer.endpoint;

import com.afklive.streamer.service.ScheduleCalendarService;
import com.afklive.streamer.util.SecurityUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;

/** SC-3: the calendar of scheduled runs across all of the signed-in creator's streams. */
@RestController
@RequestMapping("/api/schedules")
public class ScheduleCalendarController {

    private final ScheduleCalendarService calendar;

    public ScheduleCalendarController(ScheduleCalendarService calendar) {
        this.calendar = calendar;
    }

    /** {@code from} is a date in the creator's time zone (today when left out); {@code days} is 7, or 35 or 42 for a month grid (five or six weeks). */
    @GetMapping("/calendar")
    public ScheduleCalendarService.Calendar calendar(@RequestParam(required = false) String from,
                                                     @RequestParam(defaultValue = "7") int days, Principal principal) {
        if (principal == null) {
            throw new org.springframework.security.access.AccessDeniedException("Sign in first.");
        }
        LocalDate start;
        try {
            start = from == null || from.isBlank() ? null : LocalDate.parse(from.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("The date must look like 2026-10-23.");
        }
        return calendar.calendar(SecurityUtils.getEmail(principal), start, days, ZonedDateTime.now(ZoneId.of("UTC")));
    }
}
