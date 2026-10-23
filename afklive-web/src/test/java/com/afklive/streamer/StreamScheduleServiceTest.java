package com.afklive.streamer;

import com.afklive.streamer.dto.StreamDtos.*;
import com.afklive.streamer.model.*;
import com.afklive.streamer.repository.*;
import com.afklive.streamer.service.LiveStreamService;
import com.afklive.streamer.service.StreamScheduleService;
import com.afklive.streamer.service.StreamService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.*;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class StreamScheduleServiceTest extends PostgresIntegrationTest {

    @Autowired StreamScheduleService schedules;
    @Autowired StreamScheduleRepository scheduleRepository;
    @Autowired LiveStreamService liveStreams;
    @Autowired StreamService streamService;
    @Autowired UserRepository users;
    @Autowired MediaAssetRepository media;
    @Autowired StreamDestinationRepository destinations;
    @Autowired StreamJobRepository jobs;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @AfterEach
    void endRuns() {
        for (StreamJob j : jobs.findAll()) {
            if (j.isLive() && j.getUser().getEmail().startsWith("sch-")) {
                streamService.finalizeJob(j.getId(), "test");
            }
        }
        scheduleRepository.deleteAll();
    }

    private record Fixture(String email, LiveStream stream) {
    }

    private Fixture readyStream() {
        String email = "sch-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        User u = new User(email);
        u.setPlanType(PlanType.ESSENTIALS);
        users.save(u);
        LiveStream s = liveStreams.create(email, new CreateStream("Aarti", null, null, 720, 30));
        MediaAsset v = new MediaAsset(u, "aarti.mp4", "key/" + UUID.randomUUID() + ".mp4", 1L, MediaAsset.ContentType.VIDEO);
        v = media.save(v);
        liveStreams.setItems(email, s.getId(), StreamItem.Kind.VIDEO, List.of(v.getId()));
        StreamDestination d = new StreamDestination();
        d.setName("YouTube");
        d.setStreamKey("abcd-efgh-ijkl-mnop");
        d.setUser(u);
        d = destinations.save(d);
        liveStreams.setTargets(email, s.getId(), List.of(new TargetRequest(d.getId(), true)));
        return new Fixture(email, s);
    }

    private static ZonedDateTime ist(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(IST).withZoneSameInstant(ZoneId.of("UTC"));
    }

    @Test
    void dailyWindowsIncludingOvernightAndSkippedDates() {
        StreamSchedule daily = new StreamSchedule();
        daily.setKind(StreamSchedule.Kind.DAILY);
        daily.setTimeZone("Asia/Kolkata");
        daily.setStartTime(LocalTime.of(22, 0));
        daily.setEndTime(LocalTime.of(2, 0)); // overnight
        daily.setSkipDates("2026-10-24");

        assertThat(callWindow(daily, ist("2026-10-23T23:30"))).isNotNull();
        assertThat(callWindow(daily, ist("2026-10-24T01:30"))).isNotNull(); // started yesterday, still running
        assertThat(callWindow(daily, ist("2026-10-24T03:00"))).isNull();
        assertThat(callWindow(daily, ist("2026-10-24T23:00"))).isNull(); // skipped date
        assertThat(callWindow(daily, ist("2026-10-25T22:00"))).isNotNull(); // start is inclusive
    }

    @Test
    void weeklyRunsOnlyOnItsDays() {
        StreamSchedule weekly = new StreamSchedule();
        weekly.setKind(StreamSchedule.Kind.WEEKLY);
        weekly.setTimeZone("Asia/Kolkata");
        weekly.setDaysOfWeek("SAT,SUN");
        weekly.setStartTime(LocalTime.of(6, 0));
        weekly.setEndTime(LocalTime.of(7, 0));
        weekly.setEnabled(true);
        assertThat(callWindow(weekly, ist("2026-10-24T06:30"))).isNotNull(); // Saturday
        assertThat(callWindow(weekly, ist("2026-10-23T06:30"))).isNull(); // Friday
        ZonedDateTime next = callNext(weekly, ist("2026-10-23T12:00"));
        assertThat(next.withZoneSameInstant(IST).toLocalDateTime()).isEqualTo(LocalDateTime.parse("2026-10-24T06:00"));
    }

    @Test
    void aDueScheduleStartsTheStreamOnceWithItsEndAsTheStopTime() {
        Fixture f = readyStream();
        LocalTime start = LocalTime.now(IST).minusMinutes(1).withSecond(0).withNano(0);
        LocalTime end = start.plusHours(1);
        ScheduleView v = schedules.create(f.email(), f.stream().getId(), new ScheduleRequest("DAILY", null, null, null,
                start.toString(), end.toString(), "Asia/Kolkata", null, true));
        assertThat(v.nextStart()).isNotNull();

        schedules.runDue(ZonedDateTime.now(ZoneId.of("UTC")));
        List<StreamJob> live = jobs.findAllByStreamIdAndIsLiveTrue(f.stream().getId());
        assertThat(live).hasSize(1);
        assertThat(live.getFirst().getStopAt()).isNotNull();

        // Stopped by hand: the same occurrence isn't started again.
        streamService.finalizeJob(live.getFirst().getId(), "Stopped by user");
        schedules.runDue(ZonedDateTime.now(ZoneId.of("UTC")));
        assertThat(jobs.findAllByStreamIdAndIsLiveTrue(f.stream().getId())).isEmpty();
    }

    @Test
    void aFailedStartIsRecordedAndCanBeRetried() {
        Fixture f = readyStream();
        // Break it: no destinations.
        liveStreams.setTargets(f.email(), f.stream().getId(), List.of());
        String at = LocalDateTime.now(IST).plusSeconds(30).withNano(0).toString();
        ScheduleView v = schedules.create(f.email(), f.stream().getId(), new ScheduleRequest("ONCE", at, null, null, null, null,
                "Asia/Kolkata", null, true));

        schedules.runDue(ZonedDateTime.now(ZoneId.of("UTC")).plusMinutes(1));
        StreamSchedule after = scheduleRepository.findById(v.id()).orElseThrow();
        assertThat(after.getLastError()).contains("destination");
        assertThat(after.isEnabled()).isFalse(); // one-time: done either way
        assertThatThrownBy(() -> schedules.retry(f.email(), f.stream().getId(), v.id()))
                .isInstanceOf(LiveStreamService.PreflightFailedException.class);
    }

    @Test
    void schedulesAreValidated() {
        Fixture f = readyStream();
        Long id = f.stream().getId();
        assertThatThrownBy(() -> schedules.create(f.email(), id, new ScheduleRequest("HOURLY", null, null, null, null, null, null, null, null)))
                .hasMessageContaining("ONCE, DAILY or WEEKLY");
        assertThatThrownBy(() -> schedules.create(f.email(), id, new ScheduleRequest("WEEKLY", null, null, List.of(), "06:00", "07:00", null, null, null)))
                .hasMessageContaining("at least one day");
        assertThatThrownBy(() -> schedules.create(f.email(), id, new ScheduleRequest("DAILY", null, null, null, "06:00", null, null, null, null)))
                .hasMessageContaining("start and an end");
        assertThatThrownBy(() -> schedules.create(f.email(), id, new ScheduleRequest("ONCE", "2020-01-01T10:00", null, null, null, null, null, null, null)))
                .hasMessageContaining("already passed");
        ScheduleView ok = schedules.create(f.email(), id, new ScheduleRequest("WEEKLY", null, null, List.of("sunday", "Mon"), "06:00", "07:00",
                "Asia/Kolkata", List.of("2026-12-25"), true));
        assertThat(ok.daysOfWeek()).containsExactly("MON", "SUN");
    }

    private static Object callWindow(StreamSchedule s, ZonedDateTime now) {
        return StreamScheduleService.currentWindow(s, now).orElse(null);
    }

    private static ZonedDateTime callNext(StreamSchedule s, ZonedDateTime now) {
        return StreamScheduleService.nextStart(s, now);
    }

    @Test
    void aScheduleDoesntCutShortAStreamStartedByHand() {
        Fixture f = readyStream();
        StreamJob manual = liveStreams.goLive(f.email(), f.stream().getId(), null);
        LocalTime start = LocalTime.now(IST).minusMinutes(1).withSecond(0).withNano(0);
        ScheduleView v = schedules.create(f.email(), f.stream().getId(), new ScheduleRequest("DAILY", null, null, null,
                start.toString(), start.plusHours(1).toString(), "Asia/Kolkata", null, true));
        schedules.runDue(ZonedDateTime.now(ZoneId.of("UTC")));
        assertThat(jobs.findById(manual.getId()).orElseThrow().getStopAt()).isNull();
        assertThat(scheduleRepository.findById(v.id()).orElseThrow().getLastError()).contains("already live");
    }
}
