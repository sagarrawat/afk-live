package com.afklive.streamer;

import com.afklive.streamer.dto.StreamDtos.CreateStream;
import com.afklive.streamer.dto.StreamDtos.TargetRequest;
import com.afklive.streamer.model.LiveStream;
import com.afklive.streamer.model.MediaAsset;
import com.afklive.streamer.model.PlanConfig;
import com.afklive.streamer.model.PlanType;
import com.afklive.streamer.model.StreamCommand;
import com.afklive.streamer.model.StreamDestination;
import com.afklive.streamer.model.StreamItem;
import com.afklive.streamer.model.StreamJob;
import com.afklive.streamer.model.StreamJobDestination;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.MediaAssetRepository;
import com.afklive.streamer.repository.StreamCommandRepository;
import com.afklive.streamer.repository.StreamDestinationRepository;
import com.afklive.streamer.repository.StreamJobDestinationRepository;
import com.afklive.streamer.repository.StreamJobRepository;
import com.afklive.streamer.repository.UserRepository;
import com.afklive.streamer.service.CostMeter;
import com.afklive.streamer.service.FairUse;
import com.afklive.streamer.service.FairUseModel;
import com.afklive.streamer.service.FairUseSweep;
import com.afklive.streamer.service.LiveStreamService;
import com.afklive.streamer.service.NotificationService;
import com.afklive.streamer.service.PlanService;
import com.afklive.streamer.service.StreamService;
import com.afklive.streamer.service.VideoBitrates;
import com.afklive.streamer.streaming.StreamSpec;
import com.afklive.streamer.streaming.StudioSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FO-5 fair use: an account's streaming data this month from the destinations' byte counters (handovers and
 * takeovers counted once), alerts at 80/100/110% once per month across web instances, and what happens past the
 * allowance: extra destinations pause without touching the first, new runs start at 720p 30, running streams keep
 * their quality, and an admin grant brings everything back.
 */
@SpringBootTest(properties = "app.streaming.bitrate.agency.720=3000")
class FairUseTest extends PostgresIntegrationTest {

    static final long GIB = 1L << 30;

    @Autowired WebApplicationContext context;
    @Autowired LiveStreamService service;
    @Autowired UserRepository users;
    @Autowired MediaAssetRepository media;
    @Autowired StreamDestinationRepository destinations;
    @Autowired StreamJobRepository jobs;
    @Autowired StreamJobDestinationRepository jobDestinations;
    @Autowired StreamCommandRepository commands;
    @Autowired StreamService streamService;
    @Autowired PlanService plans;
    @Autowired FairUse fairUse;
    @Autowired FairUseSweep sweep;
    @Autowired CostMeter meter;
    @Autowired NotificationService notifications;
    @Autowired VideoBitrates bitrates;
    @Autowired JdbcTemplate jdbc;
    @Autowired NamedParameterJdbcTemplate named;

    MockMvc mvc;
    String tag;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        tag = UUID.randomUUID().toString().substring(0, 8);
        allowance(PlanType.AGENCY, 10); // 10 GB a month for these tests
    }

    @AfterEach
    void tearDown() {
        allowance(PlanType.AGENCY, null);
        for (StreamJob j : jobs.findAll()) {
            if (j.isLive() && j.getUser().getEmail().startsWith("fair-")) {
                streamService.finalizeJob(j.getId(), "test");
            }
        }
    }

    private void allowance(PlanType plan, Integer gb) {
        PlanConfig c = plans.getPlanConfig(plan);
        c.setMonthlyTrafficGb(gb);
        plans.updatePlan(c);
    }

    private User account(PlanType plan) {
        User u = new User("fair-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com");
        u.setPlanType(plan);
        return users.save(u);
    }

    private StreamDestination destination(User u, String name) {
        StreamDestination d = new StreamDestination();
        d.setName(name);
        d.setStreamKey("key-" + UUID.randomUUID().toString().substring(0, 12));
        d.setType("RTMP");
        d.setUser(u);
        return destinations.save(d);
    }

    /** A 1080p30 playlist stream sending to two destinations. */
    private LiveStream twoDestinationStream(User u) {
        LiveStream s = service.create(u.getEmail(), new CreateStream("Lofi " + tag, null, null, 1080, 30));
        MediaAsset m = new MediaAsset(u, "rain.mp4", "key/" + UUID.randomUUID() + ".mp4", 100L, MediaAsset.ContentType.VIDEO);
        m.setDurationSeconds(60.0);
        m.setHasAudio(true);
        m = media.save(m);
        service.setItems(u.getEmail(), s.getId(), StreamItem.Kind.VIDEO, List.of(m.getId()));
        StreamDestination yt = destination(u, "YouTube");
        StreamDestination tw = destination(u, "Twitch");
        service.setTargets(u.getEmail(), s.getId(), List.of(new TargetRequest(yt.getId(), true), new TargetRequest(tw.getId(), true)));
        return s;
    }

    private StreamSpec spec(long jobId) {
        return StreamSpec.fromJson(jobs.findById(jobId).orElseThrow().getSpec());
    }

    private List<StreamCommand> commandsFor(long jobId) {
        return commands.findAll().stream().filter(c -> c.getJobId().equals(jobId)).sorted(Comparator.comparing(StreamCommand::getId)).toList();
    }

    private String rowStatus(long jobId, int position) {
        return jobs.findById(jobId).orElseThrow().getDestinations().stream().filter(d -> d.getPosition() == position)
                .map(StreamJobDestination::getStatus).findFirst().orElseThrow();
    }

    private long notices(User u, int step) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id = ? AND dedupe_key = ?", Long.class,
                u.getId(), "traffic:" + com.afklive.streamer.service.BillingService.month(now()) + ":" + step);
        return n == null ? 0 : n;
    }

    private static ZonedDateTime now() {
        return ZonedDateTime.now(ZoneOffset.UTC);
    }

    private StreamJob endedRun(User u, ZonedDateTime from, ZonedDateTime to, long... bytes) {
        StreamJob job = new StreamJob(u, "key", "video.mp4", null, null, false, 0, "Run " + tag, "d", "private", from, "YouTube");
        job.setStatus("ENDED");
        job.setDesiredState("STOP");
        job.setEndTime(to);
        for (int i = 0; i < bytes.length; i++) {
            StreamJobDestination d = new StreamJobDestination(job, i, "Destination " + i);
            d.setBytesSent(bytes[i]);
            job.getDestinations().add(d);
        }
        return jobs.save(job);
    }

    // ------------------------------------------------------------------ usage

    @Test
    void usageIsWhatTheAccountsDestinationsSentThisMonthCountingAMovedRunOnce() {
        User u = account(PlanType.AGENCY);
        User other = account(PlanType.AGENCY);
        ZonedDateTime now = now();
        ZonedDateTime monthStart = now.withDayOfMonth(1).toLocalDate().atStartOfDay(ZoneOffset.UTC);
        // Two destinations, 3 + 1 GiB, inside this month (or, in its first hour, as much of it as fits).
        ZonedDateTime from = now.minusMinutes(50).isBefore(monthStart) ? monthStart : now.minusMinutes(50);
        endedRun(u, from, now.minusMinutes(1).isBefore(from) ? from.plusSeconds(1) : now.minusMinutes(1), 3 * GIB, GIB);
        // Half of a 4-hour run was last month: half its 2 GiB counts.
        endedRun(u, monthStart.minusHours(2), monthStart.plusHours(2), 2 * GIB);
        // Last month only: nothing.
        endedRun(u, monthStart.minusHours(5), monthStart.minusHours(4), 50 * GIB);
        // Someone else's run isn't ours.
        endedRun(other, from, from.plusSeconds(30), 7 * GIB);

        assertThat(fairUse.status(u, now).usedGb()).isCloseTo(5.0, within(0.001));

        // A run that moves to another worker (OPS-3 handover or a takeover): the old owner records 5 GiB just
        // before handing over; the new owner carries on from it (5 + its own 1); a late write from the old owner
        // with its own, smaller, count never lowers or doubles it.
        StreamJob moved = endedRun(u, from, from.plusSeconds(30), 0);
        assertThat(jobDestinations.updateBytesSent(moved.getId(), 0, 5 * GIB)).isEqualTo(1);
        assertThat(jobDestinations.updateBytesSent(moved.getId(), 0, 6 * GIB)).isEqualTo(1);
        assertThat(jobDestinations.updateBytesSent(moved.getId(), 0, 5 * GIB + GIB / 2)).isZero();
        FairUse.Status s = fairUse.status(u, now);
        assertThat(s.usedGb()).isCloseTo(11.0, within(0.001));
        assertThat(s.planGb()).isEqualTo(10);
        assertThat(s.allowanceGb()).isEqualTo(10.0);
        assertThat(s.percent()).isCloseTo(110, within(0.01));
        assertThat(s.step()).isEqualTo(FairUseModel.Step.REDUCED);
        assertThat(s.month()).isEqualTo(com.afklive.streamer.service.BillingService.month(now));
        assertThat(s.resetsAt()).isEqualTo(monthStart.plusMonths(1));
        assertThat(meter.gbByAccount(now, null).get(other.getId())).isCloseTo(7.0, within(0.001));

        // A plan without an allowance is never limited, whatever it sent.
        User free = account(PlanType.ESSENTIALS);
        endedRun(free, from, from.plusSeconds(30), 500 * GIB);
        FairUse.Status unlimited = fairUse.status(free, now);
        assertThat(unlimited.usedGb()).isCloseTo(500, within(0.01));
        assertThat(unlimited.allowanceGb()).isNull();
        assertThat(unlimited.step()).isEqualTo(FairUseModel.Step.NONE);
    }

    // ------------------------------------------------------------------ alerts

    @Test
    void eachStepAlertsTheCreatorOncePerMonthEvenWithTwoWebInstances() throws Exception {
        User u = account(PlanType.AGENCY);
        FairUseSweep other = new FairUseSweep(named, fairUse, meter, plans, users, notifications, service, "web", false);
        PlanConfig plan = plans.getPlanConfig(PlanType.AGENCY);
        String month = "f" + tag.substring(0, 6); // unique to this run, 7 characters like "2026-10"
        ZonedDateTime now = now();

        FairUse.Status at70 = FairUse.statusOf(plan, month, 7, 0, now);
        assertThat(sweep.notify(u, at70)).isFalse();

        FairUse.Status at85 = FairUse.statusOf(plan, month, 8.5, 0, now);
        assertThat(race(() -> sweep.notify(u, at85), () -> other.notify(u, at85))).containsExactlyInAnyOrder(true, false);
        assertThat(sweep.notify(u, at85)).isFalse();
        assertThat(other.notify(u, at85)).isFalse();
        assertThat(count(u, "traffic:" + month + ":80")).isEqualTo(1);

        // Straight past 100% and 110% between two checks: one alert, the 110% one; 100% is claimed too.
        FairUse.Status at115 = FairUse.statusOf(plan, month, 11.5, 0, now);
        assertThat(race(() -> sweep.notify(u, at115), () -> other.notify(u, at115))).containsExactlyInAnyOrder(true, false);
        assertThat(count(u, "traffic:" + month + ":100")).isZero();
        assertThat(count(u, "traffic:" + month + ":110")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM traffic_notices WHERE user_id = ? AND month = ?", Integer.class,
                u.getId(), month)).isEqualTo(3);
        String body = jdbc.queryForObject("SELECT body FROM notifications WHERE user_id = ? AND dedupe_key = ?", String.class,
                u.getId(), "traffic:" + month + ":110");
        assertThat(body).contains("11.5 of 10.0 GB").contains("720p").contains("Upgrade");
        assertThat(jdbc.queryForObject("SELECT link FROM notifications WHERE user_id = ? AND dedupe_key = ?", String.class,
                u.getId(), "traffic:" + month + ":110")).isEqualTo("/studio/settings/billing");

        // A new month starts over.
        String next = "g" + tag.substring(0, 6);
        assertThat(sweep.notify(u, FairUse.statusOf(plan, next, 8.5, 0, now))).isTrue();
    }

    private long count(User u, String key) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id = ? AND dedupe_key = ?", Long.class, u.getId(), key);
        return n == null ? 0 : n;
    }

    @SafeVarargs
    private static List<Boolean> race(Callable<Boolean>... calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.length);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Boolean>> fs = new ArrayList<>();
            for (Callable<Boolean> c : calls) {
                fs.add(pool.submit(() -> {
                    go.await();
                    return c.call();
                }));
            }
            go.countDown();
            List<Boolean> out = new ArrayList<>();
            for (Future<Boolean> f : fs) {
                out.add(f.get());
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ degradation

    @Test
    void pastTheAllowanceExtraDestinationsPauseWithoutTouchingTheFirstAndQualityDropsOnlyForNewRuns() throws Exception {
        User u = account(PlanType.AGENCY);
        LiveStream s = twoDestinationStream(u);
        StreamJob job = service.goLive(u.getEmail(), s.getId(), null);
        StreamSpec before = spec(job.getId());
        assertThat(before.destinations()).extracting(StreamSpec.Destination::off).containsExactly(false, false);
        assertThat(before.studio().height()).isEqualTo(1080);

        // 85%: a heads-up only.
        jobDestinations.updateBytesSent(job.getId(), 0, 5 * GIB);
        jobDestinations.updateBytesSent(job.getId(), 1, 3 * GIB + GIB / 2);
        FairUseSweep.Result r = sweep.sweepAccount(u, now());
        assertThat(r.paused()).isZero();
        assertThat(notices(u, 80)).isEqualTo(1);
        assertThat(spec(job.getId()).destinations()).isEqualTo(before.destinations());
        assertThat(commandsFor(job.getId())).isEmpty();

        // 105%: the second destination pauses through the live toggle; the first stays exactly as it was.
        jobDestinations.updateBytesSent(job.getId(), 0, 6 * GIB);
        jobDestinations.updateBytesSent(job.getId(), 1, 4 * GIB + GIB / 2);
        assertThat(sweep.sweepAccount(u, now()).paused()).isEqualTo(1);
        StreamSpec paused = spec(job.getId());
        assertThat(paused.destinations().get(0)).isEqualTo(before.destinations().get(0));
        assertThat(paused.destinations().get(1).off()).isTrue();
        assertThat(paused.destinations().get(1).url()).isEqualTo(before.destinations().get(1).url());
        List<StreamCommand> cmds = commandsFor(job.getId());
        assertThat(cmds).extracting(StreamCommand::getType).containsExactly(StreamCommand.Type.TARGETS);
        assertThat(StreamSpec.destinationsFromJson(cmds.getFirst().getPayload())).isEqualTo(paused.destinations());
        assertThat(rowStatus(job.getId(), 1)).isEqualTo("OFF");
        assertThat(rowStatus(job.getId(), 0)).isNotEqualTo("OFF");
        assertThat(notices(u, 100)).isEqualTo(1);
        assertThat(paused.studio().height()).isEqualTo(1080);

        // Checking again changes nothing; turning the second destination on by hand keeps it waiting.
        assertThat(sweep.sweepAccount(u, now()).paused()).isZero();
        assertThat(commandsFor(job.getId())).hasSize(1);
        List<Long> dests = destinations.findByUser(u).stream().map(StreamDestination::getId).toList();
        service.setTargets(u.getEmail(), s.getId(), dests.stream().map(id -> new TargetRequest(id, true)).toList());
        assertThat(spec(job.getId()).destinations()).extracting(StreamSpec.Destination::off).containsExactly(false, true);

        // 115%: the running stream keeps its quality (no reload, no reconnect)...
        jobDestinations.updateBytesSent(job.getId(), 0, 7 * GIB);
        assertThat(sweep.sweepAccount(u, now()).paused()).isZero();
        assertThat(notices(u, 110)).isEqualTo(1);
        assertThat(spec(job.getId()).studio().height()).isEqualTo(1080);
        assertThat(commandsFor(job.getId())).extracting(StreamCommand::getType)
                .doesNotContain(StreamCommand.Type.RELOAD, StreamCommand.Type.RECONNECT);
        // ... and an edit while live doesn't change it either.
        service.update(u.getEmail(), s.getId(), new com.afklive.streamer.dto.StreamDtos.UpdateStream("Renamed " + tag, null, null,
                null, null, null, null, null, null, null, null, null, null, null));
        assertThat(spec(job.getId()).studio().height()).isEqualTo(1080);

        // A new run starts at 720p 30 at the plan's 720p bitrate, with its first destination only, and says so first.
        LiveStream second = twoDestinationStream(u);
        var preflight = service.preflight(u.getEmail(), second.getId());
        assertThat(preflight.ok()).isTrue();
        assertThat(preflight.checks()).anySatisfy(c -> {
            assertThat(c.id()).isEqualTo("streaming-data");
            assertThat(c.message()).contains("first destination only").contains("720p").contains("Upgrade");
        });
        StreamJob reduced = service.goLive(u.getEmail(), second.getId(), null);
        StudioSpec studio = spec(reduced.getId()).studio();
        assertThat(studio.height()).isEqualTo(720);
        assertThat(studio.fps()).isEqualTo(30);
        assertThat(studio.videoKbps()).isEqualTo(bitrates.videoKbps(PlanType.AGENCY, 720, 30)).isEqualTo(3000);
        assertThat(spec(reduced.getId()).maxHeight()).isEqualTo(720);
        assertThat(spec(reduced.getId()).destinations()).extracting(StreamSpec.Destination::off).containsExactly(false, true);
        assertThat(rowStatus(reduced.getId(), 1)).isEqualTo("OFF");
        // The stream's own setting is unchanged: next month it goes live in 1080p again.
        assertThat(service.get(u.getEmail(), second.getId()).getQualityHeight()).isEqualTo(1080);

        // An admin grants 10 GB for the month: 11 of 20 GB. Both runs send to both destinations again, at once.
        mvc.perform(postJson("/api/admin/users/" + u.getId() + "/traffic-grant", "{'gb':10,'reason':'Goodwill after outage'}")
                        .with(user(u.getEmail())))
                .andExpect(status().isForbidden());
        mvc.perform(postJson("/api/admin/users/" + u.getId() + "/traffic-grant", "{'gb':10,'reason':'Goodwill after outage'}")
                        .with(user("ops@example.com").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.traffic.grantedGb").value(10))
                .andExpect(jsonPath("$.traffic.allowanceGb").value(20.0))
                .andExpect(jsonPath("$.traffic.step").value("NONE"));
        assertThat(spec(job.getId()).destinations()).extracting(StreamSpec.Destination::off).containsExactly(false, false);
        assertThat(spec(job.getId()).destinations().get(0)).isEqualTo(before.destinations().get(0));
        assertThat(spec(reduced.getId()).destinations()).extracting(StreamSpec.Destination::off).containsExactly(false, false);
        assertThat(commandsFor(job.getId()).getLast().getType()).isEqualTo(StreamCommand.Type.TARGETS);
        assertThat(rowStatus(job.getId(), 1)).isEqualTo("CONNECTING");
        assertThat(spec(reduced.getId()).studio().height()).isEqualTo(720); // a running stream keeps its quality
        assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_actions WHERE action = 'TRAFFIC_GRANT' AND target_id = ? "
                + "AND detail LIKE '+10 GB for % Goodwill after outage'", Integer.class, String.valueOf(u.getId()))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT admin_email FROM traffic_grants WHERE user_id = ?", String.class, u.getId()))
                .isEqualTo("ops@example.com");
        mvc.perform(postJson("/api/admin/users/" + u.getId() + "/traffic-grant", "{'gb':0,'reason':'oops'}")
                .with(user("ops@example.com").roles("ADMIN"))).andExpect(status().isBadRequest());
        mvc.perform(postJson("/api/admin/users/" + u.getId() + "/traffic-grant", "{'gb':5}")
                .with(user("ops@example.com").roles("ADMIN"))).andExpect(status().isBadRequest());

        // The creator sees it on /api/me.
        mvc.perform(get("/api/me").with(user(u.getEmail())))
                .andExpect(jsonPath("$.traffic.allowanceGb").value(20.0))
                .andExpect(jsonPath("$.traffic.planGb").value(10))
                .andExpect(jsonPath("$.traffic.usedGb").isNumber());
    }

    @Test
    void theSweepFindsAccountsOnPlansWithAnAllowanceAndRestoresThemWhenTheAllowanceGoes() {
        User u = account(PlanType.AGENCY);
        LiveStream s = twoDestinationStream(u);
        StreamJob job = service.goLive(u.getEmail(), s.getId(), null);
        jobDestinations.updateBytesSent(job.getId(), 0, 12 * GIB);
        FairUseSweep.Result r = sweep.sweep(now());
        assertThat(r.accounts()).isGreaterThanOrEqualTo(1);
        assertThat(spec(job.getId()).destinations()).extracting(StreamSpec.Destination::off).containsExactly(false, true);

        // The admin removes the plan's allowance: the next check turns the destination back on.
        allowance(PlanType.AGENCY, null);
        r = sweep.sweep(now());
        assertThat(r.restored()).isGreaterThanOrEqualTo(1);
        assertThat(spec(job.getId()).destinations()).extracting(StreamSpec.Destination::off).containsExactly(false, false);
    }

    // ------------------------------------------------------------------ admin

    @Test
    void anAdminSetsAndClearsAPlansAllowanceWithAReasonAndSeesTheWorstCase() throws Exception {
        String edit = "{'plan':{'displayName':'Agency','maxStorageBytes':536870912000,'maxActiveStreams':10,'maxDestinations':5,"
                + "'maxResolution':2160,'maxFps':60,'monthlyHours':0,'watermark':false,'priceInrMonth':399900,'priceInrYear':3999000,"
                + "'priceUsdMonth':5900,'priceUsdYear':59000%s},'reason':'Fair use'}";
        mvc.perform(postJson("/api/admin/plans/AGENCY", String.format(edit, ",'monthlyTrafficGb':0"))
                .with(user("ops@example.com").roles("ADMIN"))).andExpect(status().isBadRequest());
        mvc.perform(postJson("/api/admin/plans/AGENCY", String.format(edit, ",'monthlyTrafficGb':15000"))
                        .with(user("ops@example.com").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monthlyTrafficGb").value(15000));
        assertThat(plans.getPlanConfig(PlanType.AGENCY).getMonthlyTrafficGb()).isEqualTo(15000);
        assertThat(jdbc.queryForObject("SELECT detail FROM admin_actions WHERE action = 'PLAN_CONFIG' AND target_id = 'AGENCY' "
                + "ORDER BY id DESC LIMIT 1", String.class)).contains("trafficGb 10 → 15000").endsWith(": Fair use");
        mvc.perform(postJson("/api/admin/plans/AGENCY", String.format(edit, ""))
                        .with(user("ops@example.com").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monthlyTrafficGb").doesNotExist());
        assertThat(plans.getPlanConfig(PlanType.AGENCY).getMonthlyTrafficGb()).isNull();

        mvc.perform(get("/api/admin/costs/worst-case").with(user("someone@example.com"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/costs/worst-case").with(user("ops@example.com").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxCostShare").value(0.6))
                .andExpect(jsonPath("$.inputs.pricePerGb").value(0.85))
                .andExpect(jsonPath("$.inputs.gatewayFeePercent").value(2.0))
                .andExpect(jsonPath("$.plans.length()").value(PlanType.values().length))
                .andExpect(jsonPath("$.plans[?(@.plan == 'FREE')].streamHours").value(10.0))
                .andExpect(jsonPath("$.plans[?(@.plan == 'ESSENTIALS')].meetsRule").value(false));
    }

    private static MockHttpServletRequestBuilder postJson(String url, String json) {
        return post(url).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.replace('\'', '"'));
    }
}
