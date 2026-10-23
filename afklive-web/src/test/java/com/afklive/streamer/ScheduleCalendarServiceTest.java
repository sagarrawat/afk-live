package com.afklive.streamer;

import com.afklive.streamer.dto.StreamDtos.CreateStream;
import com.afklive.streamer.model.LiveStream;
import com.afklive.streamer.model.PlanType;
import com.afklive.streamer.model.StreamSchedule;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.StreamScheduleRepository;
import com.afklive.streamer.repository.UserRepository;
import com.afklive.streamer.service.LiveStreamService;
import com.afklive.streamer.service.PlanService;
import com.afklive.streamer.service.ScheduleCalendarService;
import com.afklive.streamer.service.ScheduleCalendarService.Calendar;
import com.afklive.streamer.service.ScheduleCalendarService.Occurrence;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.*;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** SC-3: the calendar of scheduled runs across streams, with clashes against the plan's limit. */
@SpringBootTest
class ScheduleCalendarServiceTest extends PostgresIntegrationTest {

    @Autowired ScheduleCalendarService calendar;
    @Autowired StreamScheduleRepository schedules;
    @Autowired LiveStreamService liveStreams;
    @Autowired UserRepository users;
    @Autowired PlanService plans;
    @Autowired WebApplicationContext context;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    /** A Monday. */
    private static final LocalDate WEEK = LocalDate.parse("2026-11-02");
    private static final ZonedDateTime NOW = LocalDateTime.parse("2026-10-30T12:00").atZone(IST);

    private String account(PlanType plan, String timeZone) {
        String email = "cal-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        User u = new User(email);
        u.setPlanType(plan);
        u.setTimeZone(timeZone);
        users.save(u);
        return email;
    }

    private LiveStream stream(String email, String name) {
        return liveStreams.create(email, new CreateStream(name, null, null, 720, 30));
    }

    private StreamSchedule daily(LiveStream s, String zone, String start, String end, String skips) {
        StreamSchedule sc = new StreamSchedule();
        sc.setStreamId(s.getId());
        sc.setKind(StreamSchedule.Kind.DAILY);
        sc.setTimeZone(zone);
        sc.setStartTime(LocalTime.parse(start));
        sc.setEndTime(LocalTime.parse(end));
        sc.setSkipDates(skips);
        return schedules.save(sc);
    }

    private StreamSchedule once(LiveStream s, String start, String end) {
        StreamSchedule sc = new StreamSchedule();
        sc.setStreamId(s.getId());
        sc.setKind(StreamSchedule.Kind.ONCE);
        sc.setTimeZone("Asia/Kolkata");
        sc.setStartAt(LocalDateTime.parse(start).atZone(IST).withZoneSameInstant(ZoneId.of("UTC")));
        sc.setEndAt(end == null ? null : LocalDateTime.parse(end).atZone(IST).withZoneSameInstant(ZoneId.of("UTC")));
        return schedules.save(sc);
    }

    @Test
    void overnightRunsAndSkippedDatesInTheCreatorsTimeZone() {
        String email = account(PlanType.PRO, "Asia/Kolkata");
        LiveStream s = stream(email, "Night lofi");
        daily(s, "Asia/Kolkata", "22:00", "02:00", "2026-11-04");

        Calendar c = calendar.calendar(email, WEEK, 7, NOW);
        assertThat(c.timeZone()).isEqualTo("Asia/Kolkata");
        // Nov 1 22:00 runs into Monday; Nov 2..8 start in the week; Nov 4 is skipped.
        assertThat(c.occurrences()).extracting(Occurrence::startLocal).containsExactly(
                "2026-11-01T22:00", "2026-11-02T22:00", "2026-11-03T22:00", "2026-11-05T22:00",
                "2026-11-06T22:00", "2026-11-07T22:00", "2026-11-08T22:00");
        assertThat(c.occurrences().getFirst().endLocal()).isEqualTo("2026-11-02T02:00");
        assertThat(c.occurrences()).allSatisfy(o -> {
            assertThat(o.streamName()).isEqualTo("Night lofi");
            assertThat(o.kind()).isEqualTo("DAILY");
            assertThat(o.movable()).isFalse();
        });
        assertThat(c.clashes()).isEmpty();
    }

    @Test
    void aScheduleInAnotherZoneShowsInTheCreatorsLocalTime() {
        String email = account(PlanType.PRO, "Asia/Kolkata");
        LiveStream s = stream(email, "London mornings");
        daily(s, "Europe/London", "22:00", "02:00", "2026-11-03"); // GMT after Oct 25: IST is 5:30 ahead

        Calendar c = calendar.calendar(email, WEEK, 7, NOW);
        // London Nov 1..7 start inside the IST week (Nov 8's starts at 03:30 on Nov 9); Nov 3 is skipped.
        assertThat(c.occurrences()).extracting(Occurrence::startLocal).containsExactly(
                "2026-11-02T03:30", "2026-11-03T03:30", "2026-11-05T03:30", "2026-11-06T03:30",
                "2026-11-07T03:30", "2026-11-08T03:30");
        assertThat(c.occurrences().getFirst().start()).isEqualTo("2026-11-01T22:00:00Z");
        assertThat(c.occurrences().getFirst().scheduleTimeZone()).isEqualTo("Europe/London");
    }

    @Test
    void monthViewWithWeeklyAndOneTimeRunsAcrossStreams() {
        String email = account(PlanType.PRO, "Asia/Kolkata");
        LiveStream a = stream(email, "Aarti");
        LiveStream b = stream(email, "Launch");
        StreamSchedule weekly = new StreamSchedule();
        weekly.setStreamId(a.getId());
        weekly.setKind(StreamSchedule.Kind.WEEKLY);
        weekly.setTimeZone("Asia/Kolkata");
        weekly.setDaysOfWeek("SAT,SUN");
        weekly.setStartTime(LocalTime.of(6, 0));
        weekly.setEndTime(LocalTime.of(7, 0));
        schedules.save(weekly);
        StreamSchedule future = once(b, "2026-11-20T18:00", "2026-11-20T20:00");
        StreamSchedule ran = once(b, "2026-11-01T18:00", "2026-11-01T19:00");
        ran.setEnabled(false);
        ran.setLastStartedOn(LocalDate.parse("2026-11-01"));
        schedules.save(ran);
        StreamSchedule paused = once(b, "2026-11-10T18:00", "2026-11-10T19:00");
        paused.setEnabled(false);
        schedules.save(paused);

        Calendar c = calendar.calendar(email, LocalDate.parse("2026-10-26"), 35, LocalDateTime.parse("2026-11-05T12:00").atZone(IST));
        // Oct 26 .. Nov 29: 5 weekends of the weekly schedule, plus two one-time runs (the paused one isn't shown).
        assertThat(c.occurrences().stream().filter(o -> o.streamId().equals(a.getId()))).hasSize(10);
        List<Occurrence> launch = c.occurrences().stream().filter(o -> o.streamId().equals(b.getId())).toList();
        assertThat(launch).extracting(Occurrence::scheduleId).containsExactly(ran.getId(), future.getId());
        assertThat(launch.get(0).movable()).isFalse(); // already ran
        assertThat(launch.get(1).movable()).isTrue();
        assertThat(launch.get(1).endLocal()).isEqualTo("2026-11-20T20:00");
        assertThat(c.streams()).extracting(ScheduleCalendarService.StreamRef::name).containsExactly("Aarti", "Launch");
    }

    @Test
    void runsOverThePlansLimitAreAClash() {
        String email = account(PlanType.ESSENTIALS, "Asia/Kolkata");
        int limit = plans.getPlanConfig(PlanType.ESSENTIALS).getMaxActiveStreams();
        String planName = plans.getPlanConfig(PlanType.ESSENTIALS).getDisplayName();
        assertThat(limit).isEqualTo(1);
        LiveStream a = stream(email, "Bhajans");
        LiveStream b = stream(email, "Kirtan");
        daily(a, "Asia/Kolkata", "10:00", "12:00", null);
        daily(a, "Asia/Kolkata", "11:00", "11:30", null); // same stream twice: still one stream on air
        once(b, "2026-11-03T11:00", "2026-11-03T13:00");

        Calendar c = calendar.calendar(email, WEEK, 7, NOW);
        assertThat(c.limit()).isEqualTo(1);
        assertThat(c.planName()).isEqualTo(planName);
        assertThat(c.clashes()).hasSize(1);
        ScheduleCalendarService.Clash clash = c.clashes().getFirst();
        assertThat(clash.startLocal()).isEqualTo("2026-11-03T11:00");
        assertThat(clash.endLocal()).isEqualTo("2026-11-03T12:00");
        assertThat(clash.streams()).isEqualTo(2);
        assertThat(clash.streamNames()).containsExactlyInAnyOrder("Bhajans", "Kirtan");
        assertThat(clash.message()).contains("2 streams would be on air at once").contains("the " + planName + " plan runs 1 stream at a time");

        // A run without an end keeps going, so it clashes with every later run of another stream.
        once(b, "2026-11-07T09:00", null);
        Calendar open = calendar.calendar(email, WEEK, 7, NOW);
        assertThat(open.occurrences().stream().filter(o -> o.endLocal() == null)).hasSize(1);
        assertThat(open.clashes()).extracting(ScheduleCalendarService.Clash::startLocal)
                .containsExactly("2026-11-03T11:00", "2026-11-07T10:00", "2026-11-08T10:00");
    }

    @Test
    void aBiggerPlanHasNoClash() {
        String email = account(PlanType.PRO, "Asia/Kolkata");
        LiveStream a = stream(email, "One");
        LiveStream b = stream(email, "Two");
        daily(a, "Asia/Kolkata", "10:00", "12:00", null);
        daily(b, "Asia/Kolkata", "10:00", "12:00", null);
        assertThat(calendar.calendar(email, WEEK, 7, NOW).clashes()).isEmpty();
    }

    @Test
    void onlyTheCreatorsOwnStreamsAndOnlyWeekOrMonth() {
        String mine = account(PlanType.PRO, "Asia/Kolkata");
        String theirs = account(PlanType.PRO, "Asia/Kolkata");
        daily(stream(theirs, "Not mine"), "Asia/Kolkata", "10:00", "12:00", null);
        assertThat(calendar.calendar(mine, WEEK, 7, NOW).occurrences()).isEmpty();
        assertThatThrownBy(() -> calendar.calendar(mine, WEEK, 400, NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void overHttp() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        mvc.perform(get("/api/schedules/calendar")).andExpect(status().is3xxRedirection());
        String email = account(PlanType.PRO, "Asia/Kolkata");
        daily(stream(email, "Chill"), "Asia/Kolkata", "09:00", "10:00", null);
        mvc.perform(get("/api/schedules/calendar?from=2026-11-02&days=7").with(user(email).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days").value(7))
                .andExpect(jsonPath("$.occurrences.length()").value(7))
                .andExpect(jsonPath("$.occurrences[0].startLocal").value("2026-11-02T09:00"))
                .andExpect(jsonPath("$.clashes.length()").value(0));
        mvc.perform(get("/api/schedules/calendar?from=nope").with(user(email).roles("USER")))
                .andExpect(status().isBadRequest());
    }
}
