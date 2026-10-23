package com.afklive.streamer;

import com.afklive.streamer.model.PlanConfig;
import com.afklive.streamer.model.PlanType;
import com.afklive.streamer.model.SupportTicket;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.AdminActionRepository;
import com.afklive.streamer.repository.PlanConfigRepository;
import com.afklive.streamer.repository.SupportTicketRepository;
import com.afklive.streamer.repository.UserRepository;
import com.afklive.streamer.service.AppConfigService;
import com.afklive.streamer.service.FileStorageService;
import com.afklive.streamer.service.PlanService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** What moved from the old /admin page into /api/admin: overview, free capacity, support tickets, plan settings. */
@SpringBootTest
class AdminParityTest extends PostgresIntegrationTest {

    static final String ADMIN = "admin-parity@example.com";

    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired SupportTicketRepository tickets;
    @Autowired AdminActionRepository audit;
    @Autowired FileStorageService storage;
    @Autowired AppConfigService appConfig;
    @Autowired PlanConfigRepository planConfigs;
    @Autowired PlanService planService;

    MockMvc mvc;
    User customer;
    int limitBefore;
    PlanConfig proBefore;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        customer = new User("par-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com");
        customer.setPlanType(PlanType.FREE);
        customer = users.save(customer);
        limitBefore = appConfig.getGlobalStreamLimit();
        proBefore = planConfigs.findById(PlanType.PRO).orElseThrow();
    }

    @AfterEach
    void restore() {
        appConfig.setGlobalStreamLimit(limitBefore);
        planService.updatePlan(proBefore); // other tests rely on the default Pro plan
    }

    private static RequestPostProcessor admin() {
        return user(ADMIN).roles("ADMIN");
    }

    private static MockHttpServletRequestBuilder postJson(String url, String json) {
        return post(url).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.replace('\'', '"'));
    }

    private SupportTicket ticket(String attachmentName, byte[] bytes) {
        String key = bytes == null ? null : storage.uploadFile(new ByteArrayInputStream(bytes), "support_" + attachmentName, bytes.length);
        return tickets.save(SupportTicket.builder().user(customer).category("Billing").message("My payment didn't go through")
                .attachmentKey(key).attachmentName(bytes == null ? null : attachmentName).status("OPEN").build());
    }

    @Test
    void everythingNewIsAdminOnly() throws Exception {
        RequestPostProcessor someone = user(customer.getEmail());
        SupportTicket t = ticket("shot.png", new byte[] {1, 2, 3});
        for (String url : new String[] {"/api/admin/overview", "/api/admin/tickets", "/api/admin/tickets/" + t.getId(),
                "/api/admin/tickets/" + t.getId() + "/attachment", "/api/admin/plans"}) {
            mvc.perform(get(url).with(someone)).andExpect(status().isForbidden());
        }
        mvc.perform(postJson("/api/admin/settings/free-stream-limit", "{'limit':5,'reason':'try'}").with(someone)).andExpect(status().isForbidden());
        mvc.perform(postJson("/api/admin/tickets/" + t.getId() + "/status", "{'status':'CLOSED','reason':'try'}").with(someone)).andExpect(status().isForbidden());
        mvc.perform(postJson("/api/admin/plans/PRO", "{'reason':'try'}").with(someone)).andExpect(status().isForbidden());
        assertThat(appConfig.getGlobalStreamLimit()).isEqualTo(limitBefore);
        assertThat(tickets.findById(t.getId()).orElseThrow().getStatus()).isEqualTo("OPEN");
    }

    @Test
    void overviewCountsThePlatform() throws Exception {
        ticket("x.png", null);
        mvc.perform(get("/api/admin/overview").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users").isNumber())
                .andExpect(jsonPath("$.liveStreams").isNumber())
                .andExpect(jsonPath("$.storageBytes").isNumber())
                .andExpect(jsonPath("$.openTickets").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.freeStreamLimit").value(limitBefore))
                .andExpect(jsonPath("$.revenue").isArray());
    }

    @Test
    void freeStreamLimitNeedsAReasonAndASensibleNumberAndIsAudited() throws Exception {
        mvc.perform(postJson("/api/admin/settings/free-stream-limit", "{'limit':40}").with(admin())).andExpect(status().isBadRequest());
        mvc.perform(postJson("/api/admin/settings/free-stream-limit", "{'limit':-1,'reason':'Capacity'}").with(admin())).andExpect(status().isBadRequest());
        mvc.perform(postJson("/api/admin/settings/free-stream-limit", "{'limit':100000,'reason':'Capacity'}").with(admin())).andExpect(status().isBadRequest());
        mvc.perform(postJson("/api/admin/settings/free-stream-limit", "{'reason':'Capacity'}").with(admin())).andExpect(status().isBadRequest());
        assertThat(appConfig.getGlobalStreamLimit()).isEqualTo(limitBefore);

        mvc.perform(postJson("/api/admin/settings/free-stream-limit", "{'limit':40,'reason':'Worker pool shrank'}").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.freeStreamLimit").value(40));
        assertThat(appConfig.getGlobalStreamLimit()).isEqualTo(40);
        assertThat(audit.findTop50ByTargetTypeAndTargetIdOrderByCreatedAtDesc("setting", "global_max_streams"))
                .anyMatch(a -> a.getAction().equals("SETTING_FREE_LIMIT") && a.getAdminEmail().equals(ADMIN)
                        && a.getDetail().contains("→ 40") && a.getDetail().contains("Worker pool shrank"));
    }

    @Test
    void ticketsCanBeListedFilteredAndViewed() throws Exception {
        SupportTicket t = ticket("screen shot.png", new byte[] {1, 2, 3});
        mvc.perform(get("/api/admin/tickets").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == " + t.getId() + ")].userEmail").value(customer.getEmail()))
                .andExpect(jsonPath("$.content[?(@.id == " + t.getId() + ")].hasAttachment").value(true));
        mvc.perform(get("/api/admin/tickets").param("status", "CLOSED").with(admin()))
                .andExpect(jsonPath("$.content[?(@.id == " + t.getId() + ")]").isEmpty());
        mvc.perform(get("/api/admin/tickets").param("status", "nonsense").with(admin())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/tickets/" + t.getId()).with(admin()))
                .andExpect(jsonPath("$.message").value("My payment didn't go through"))
                .andExpect(jsonPath("$.userId").value(customer.getId()))
                .andExpect(jsonPath("$.attachmentName").value("screen shot.png"));
        mvc.perform(get("/api/admin/tickets/999999999").with(admin())).andExpect(status().isNotFound());
    }

    @Test
    void ticketStatusChangesNeedAReasonAndAreAudited() throws Exception {
        SupportTicket t = ticket("x.png", null);
        String url = "/api/admin/tickets/" + t.getId() + "/status";
        mvc.perform(postJson(url, "{'status':'RESOLVED'}").with(admin())).andExpect(status().isBadRequest());
        mvc.perform(postJson(url, "{'status':'DELETED','reason':'Answered by email'}").with(admin())).andExpect(status().isBadRequest());
        mvc.perform(postJson(url, "{'status':'OPEN','reason':'Answered by email'}").with(admin())).andExpect(status().isConflict());
        assertThat(tickets.findById(t.getId()).orElseThrow().getStatus()).isEqualTo("OPEN");

        mvc.perform(postJson(url, "{'status':'resolved','reason':'Answered by email'}").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"));
        assertThat(tickets.findById(t.getId()).orElseThrow().getStatus()).isEqualTo("RESOLVED");
        assertThat(audit.findTop50ByTargetTypeAndTargetIdOrderByCreatedAtDesc("ticket", String.valueOf(t.getId())))
                .anyMatch(a -> a.getAction().equals("TICKET_STATUS") && a.getDetail().equals("OPEN → RESOLVED: Answered by email"));
    }

    @Test
    void attachmentsDownloadAndNeverRenderInline() throws Exception {
        SupportTicket png = ticket("shot.png", new byte[] {7, 8, 9});
        mvc.perform(get("/api/admin/tickets/" + png.getId() + "/attachment").with(admin()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment;")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().bytes(new byte[] {7, 8, 9}));
        SupportTicket html = ticket("page.html", "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8));
        mvc.perform(get("/api/admin/tickets/" + html.getId() + "/attachment").with(admin()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/octet-stream"));
        SupportTicket none = ticket("x.png", null);
        mvc.perform(get("/api/admin/tickets/" + none.getId() + "/attachment").with(admin())).andExpect(status().isNotFound());
    }

    private static String proEdit(String overrides) {
        String base = "'displayName':'Pro','maxStorageBytes':107374182400,'maxActiveStreams':3,'maxDestinations':5,"
                + "'maxResolution':1080,'maxFps':60,'monthlyHours':0,'watermark':false,"
                + "'priceInrMonth':149900,'priceInrYear':1499000,'priceUsdMonth':2400,'priceUsdYear':24000";
        return "{'plan':{" + base + (overrides.isEmpty() ? "" : "," + overrides) + "},'reason':'Price review'}";
    }

    @Test
    void plansAreListedWithTheirLimitsAndPrices() throws Exception {
        mvc.perform(get("/api/admin/plans").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(PlanType.values().length))
                .andExpect(jsonPath("$[0].plan").value("FREE"))
                .andExpect(jsonPath("$[?(@.plan == 'PRO')].priceInrMonth").value(149900));
    }

    @Test
    void planEditsAreValidated() throws Exception {
        String url = "/api/admin/plans/PRO";
        mvc.perform(postJson(url, proEdit("'priceInrMonth':-1")).with(admin())).andExpect(status().isBadRequest());
        mvc.perform(postJson(url, proEdit("'priceUsdYear':99999")).with(admin())).andExpect(status().isBadRequest()); // > 12 months
        mvc.perform(postJson(url, proEdit("'priceInrMonth':0")).with(admin())).andExpect(status().isBadRequest());
        mvc.perform(postJson(url, proEdit("'maxActiveStreams':0")).with(admin())).andExpect(status().isBadRequest());
        mvc.perform(postJson(url, proEdit("'maxFps':45")).with(admin())).andExpect(status().isBadRequest());
        mvc.perform(postJson(url, proEdit("'maxResolution':999")).with(admin())).andExpect(status().isBadRequest());
        mvc.perform(postJson(url, proEdit("'maxStorageBytes':0")).with(admin())).andExpect(status().isBadRequest());
        mvc.perform(postJson(url, proEdit("'displayName':' '")).with(admin())).andExpect(status().isBadRequest());
        mvc.perform(postJson(url, proEdit("")).with(admin())).andExpect(status().isBadRequest()); // nothing changed
        mvc.perform(postJson(url, proEdit("'maxActiveStreams':4").replace(",'reason':'Price review'", "")).with(admin()))
                .andExpect(status().isBadRequest()); // no reason
        mvc.perform(postJson("/api/admin/plans/FREE", "{'plan':{'displayName':'Free','maxStorageBytes':2147483648,'maxActiveStreams':1,"
                + "'maxDestinations':1,'maxResolution':720,'maxFps':30,'monthlyHours':10,'watermark':true,'priceInrMonth':100,"
                + "'priceInrYear':0,'priceUsdMonth':0,'priceUsdYear':0},'reason':'Price review'}").with(admin()))
                .andExpect(status().isBadRequest()); // Free stays free
        mvc.perform(postJson("/api/admin/plans/GOLD", proEdit("")).with(admin())).andExpect(status().isNotFound());
        assertThat(planService.getPlanConfig(PlanType.PRO).getMaxActiveStreams()).isEqualTo(proBefore.getMaxActiveStreams());
    }

    @Test
    void planEditsApplyAtOnceAndAreAudited() throws Exception {
        planService.getPlanConfig(PlanType.PRO); // warm the cache: the edit must evict it
        mvc.perform(postJson("/api/admin/plans/pro", proEdit("'maxActiveStreams':4,'priceInrMonth':159900,'maxScheduledPosts':500")).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxActiveStreams").value(4))
                .andExpect(jsonPath("$.priceInrMonth").value(159900));
        PlanConfig now = planService.getPlanConfig(PlanType.PRO);
        assertThat(now.getMaxActiveStreams()).isEqualTo(4);
        assertThat(now.price("INR", false)).isEqualTo(159900);
        assertThat(now.getMaxScheduledPosts()).isEqualTo(500);
        assertThat(now.getMaxChannels()).isEqualTo(proBefore.getMaxChannels()); // not sent: kept
        assertThat(now.getPrice()).isEqualTo("₹1,599");
        mvc.perform(get("/api/billing/plans").with(user(customer.getEmail())))
                .andExpect(jsonPath("$[?(@.id == 'PRO')].concurrentStreams").value(4))
                .andExpect(jsonPath("$[?(@.id == 'PRO')].monthly").value(159900));
        assertThat(audit.findTop50ByTargetTypeAndTargetIdOrderByCreatedAtDesc("plan", "PRO"))
                .anyMatch(a -> a.getAction().equals("PLAN_CONFIG") && a.getAdminEmail().equals(ADMIN)
                        && a.getDetail().contains("streams 3 → 4") && a.getDetail().contains("INR/month 149900 → 159900")
                        && a.getDetail().endsWith(": Price review"));
    }

    @Test
    void oldAdminAddressesRedirectToTheAppsConsole() throws Exception {
        mvc.perform(get("/admin").with(admin())).andExpect(status().isFound()).andExpect(redirectedUrl("/studio/admin"));
        mvc.perform(get("/admin").param("tab", "support").with(admin())).andExpect(redirectedUrl("/studio/admin"));
        mvc.perform(get("/admin/support/attachment/1").with(admin())).andExpect(status().isFound()).andExpect(redirectedUrl("/studio/admin"));
        mvc.perform(get("/admin")).andExpect(redirectedUrl("/login"));
        mvc.perform(get("/admin").with(user(customer.getEmail()))).andExpect(status().isForbidden());
        // The old form posts are gone.
        mvc.perform(post("/admin/settings/stream-limit").with(admin()).with(csrf()).param("limit", "1"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(404, 405));
        assertThat(appConfig.getGlobalStreamLimit()).isEqualTo(limitBefore);
    }
}
