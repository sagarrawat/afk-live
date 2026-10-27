package com.afklive.streamer.e2e;

import com.afklive.streamer.PostgresIntegrationTest;
import com.afklive.streamer.payment.PaymentGateway;
import com.afklive.streamer.util.PublicDnsForTests;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Tracing;
import com.microsoft.playwright.options.AriaRole;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The creator journey in a real browser against the real app: Spring Boot with an embedded PostgreSQL, the
 * stream worker in the same process (role "all") running real FFmpeg, and the built React app. It catches
 * what unit tests can't: screens, API and worker disagreeing with each other.
 *
 * <ol>
 *   <li>Sign up with a password (email is off in tests, so the account is ready at once: OPS-1), sign in,
 *       and land in setup (ON-2).</li>
 *   <li>Upload a generated video through the real resumable upload (ML-1), into the first stream's playlist.</li>
 *   <li>Add a destination: a local RTMP sink on 127.0.0.1 (DS-2). Production refuses local addresses (DS-8);
 *       only this test JVM treats every host as public for the address check, see {@link PublicDnsForTests}.</li>
 *   <li>Go live (GL-1), wait until the stream says Live and the destination "On Test sink" (RE-8) while the sink
 *       receives data, stop (GL-3), and find the run under Recent runs (RE-9).</li>
 *   <li>At 375 px wide: the main screens have no sideways scrolling, and the payment journey reaches the
 *       payment page: choose a plan, see the quote in rupees with GST included, go to PhonePe (BI-1). The
 *       gateway is a test double and the PhonePe page is answered by the browser; nothing calls PhonePe.</li>
 * </ol>
 *
 * Excluded from normal builds; run with {@code afklive-scripts/e2e.sh} (docs/operations.md, "End-to-end test").
 * On failure a screenshot and a Playwright trace land in {@code afklive-web/target/e2e/}.
 */
@Tag("e2e")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// Close the app (and stop its worker and FFmpeg) while the database is still up, not in a JVM shutdown hook
// racing the embedded PostgreSQL's (that leaves the fork hanging until Surefire kills it).
@DirtiesContext
class CreatorJourneyE2ETest {

    private static final String PASSWORD = "E2e-Passw0rd!";
    private static final String STREAM = "My 24/7 stream";
    private static final String SINK_NAME = "Test sink";
    private static final String CHECKOUT_URL = "https://mercury-uat.phonepe.com/transact/uat_v2?token=e2e-test-double";
    private static final Path ARTIFACTS = Paths.get("target", "e2e");
    /** Timeouts for waiting on UI states: generous for a busy CI machine, never a fixed sleep. */
    private static final double LIVE_TIMEOUT_MS = 90_000;
    private static final double STEP_TIMEOUT_MS = 30_000;

    @LocalServerPort int port;
    @MockitoBean PaymentGateway gateway;

    static Path video;
    static Playwright playwright;
    static Browser browser;
    static RtmpSink sink;

    /**
     * The real app as in production on one server: the worker loop and media checks run by themselves. The
     * database is {@link PostgresIntegrationTest}'s embedded PostgreSQL; its other settings (worker driven by
     * hand, no media processing) are what this test must not have, so they aren't inherited.
     */
    @DynamicPropertySource
    static void realApp(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresIntegrationTest::jdbcUrl);
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("app.encryption-key", () -> "dGVzdC1rZXktdGVzdC1rZXktdGVzdC1rZXktdGVzdCE=");
        registry.add("app.streaming.worker.max-cpu-to-claim", () -> "1.01"); // a busy test machine still streams
        registry.add("app.streaming.role", () -> "all");
        registry.add("app.streaming.worker.auto-start", () -> "true");
        registry.add("app.media.auto-process", () -> "true");
        registry.add("app.first-live.auto", () -> "true");
        registry.add("app.streaming.work-dir", () -> tempDir("worker").toString());
        registry.add("app.storage.type", () -> "local");
    }

    private static Path tempDir(String name) {
        try {
            return Files.createDirectories(Paths.get(System.getProperty("java.io.tmpdir"), "afklive-e2e-" + ProcessHandle.current().pid(), name));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @BeforeAll
    static void setUp() throws Exception {
        video = tempDir("media").resolve("e2e-clip.mp4");
        Process p = new ProcessBuilder("ffmpeg", "-y", "-v", "error", "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=30",
                "-f", "lavfi", "-i", "sine=f=440:r=44100", "-t", "6", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-g", "60",
                "-c:a", "aac", "-ar", "44100", "-shortest", video.toString()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        assertThat(p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0).as("FFmpeg must be on PATH: " + out).isTrue();

        PublicDnsForTests.on(); // the local sink passes the address check in this JVM only
        sink = new RtmpSink();

        playwright = Playwright.create();
        BrowserType.LaunchOptions launch = new BrowserType.LaunchOptions().setHeadless(true);
        String chromium = System.getProperty("e2e.chromium", System.getenv().getOrDefault("E2E_CHROMIUM", ""));
        if (!chromium.isBlank()) {
            launch.setExecutablePath(Paths.get(chromium));
        }
        browser = playwright.chromium().launch(launch);
    }

    @AfterAll
    static void tearDown() {
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
        if (sink != null) sink.close();
        PublicDnsForTests.off();
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    /**
     * Runs one journey in a fresh browser (own cookies) with a trace; on failure saves a screenshot and the
     * trace under target/e2e/&lt;name&gt;/ for the workflow to upload.
     */
    private void journey(TestInfo info, int width, int height, Consumer<Page> steps) {
        String name = info.getTestMethod().map(java.lang.reflect.Method::getName).orElse("journey");
        BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                .setViewportSize(width, height)
                .setLocale("en-IN")
                .setTimezoneId("Asia/Kolkata"));
        context.setDefaultTimeout(STEP_TIMEOUT_MS);
        context.tracing().start(new Tracing.StartOptions().setScreenshots(true).setSnapshots(true).setSources(false));
        Page page = context.newPage();
        boolean ok = false;
        try {
            steps.accept(page);
            ok = true;
        } finally {
            Path dir = ARTIFACTS.resolve(name);
            if (!ok) {
                try {
                    Files.createDirectories(dir);
                    page.screenshot(new Page.ScreenshotOptions().setPath(dir.resolve("failure.png")).setFullPage(true));
                } catch (RuntimeException | java.io.IOException ignored) {
                    // the trace below is the main record
                }
            }
            context.tracing().stop(ok ? new Tracing.StopOptions() : new Tracing.StopOptions().setPath(dir.resolve("trace.zip")));
            context.close();
        }
    }

    /** Sign up with a password and sign in; a new account opens the setup guide. */
    private void signUp(Page page, String email) {
        page.navigate(url("/register"));
        page.locator("#name").fill("E2E Creator");
        page.locator("#email").fill(email);
        page.locator("#password").fill(PASSWORD);
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create account")).click();
        // OPS-1: with email off, a password account is ready straight away.
        assertThat(page.getByText("Your account is ready. Sign in to start.")).isVisible();
        page.locator("#email").fill(email);
        page.locator("#password").fill(PASSWORD);
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Sign in")).click();
        page.waitForURL(Pattern.compile(".*/studio/setup$"));
        assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Set up your first stream"))).isVisible();
    }

    private static Locator button(Locator scope, String name) {
        return scope.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName(name).setExact(true));
    }

    private static Locator button(Page page, String name) {
        return page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(name).setExact(true));
    }

    private static Locator dialog(Page page, String title) {
        return page.getByRole(AriaRole.DIALOG, new Page.GetByRoleOptions().setName(title));
    }

    /** A panel (section) by its title. */
    private static Locator panel(Page page, String title) {
        return page.locator("section").filter(new Locator.FilterOptions().setHas(
                page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName(title).setExact(true))));
    }

    /** Fails when the page scrolls sideways (the layout is wider than the phone). */
    private static void fitsTheScreen(Page page, String screen) {
        Object overflow = page.evaluate("() => document.documentElement.scrollWidth - window.innerWidth");
        assertThat(((Number) overflow).intValue()).as(screen + " scrolls sideways at 375 px (pixels too wide)").isLessThanOrEqualTo(0);
    }

    @Test
    void signUpUploadGoLiveStopAndSeeTheRun(TestInfo info) {
        journey(info, 1280, 900, page -> {
            signUp(page, "creator-" + UUID.randomUUID().toString().substring(0, 8) + "@e2e.test");

            // ON-2 step 1: what you stream.
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(Pattern.compile("^My videos"))).click();

            // Step 2: a real resumable upload (ML-1) of the generated clip.
            assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Add your videos"))).isVisible();
            page.locator("input[type=file]").setInputFiles(video);
            assertThat(page.getByText("Uploaded", new Page.GetByTextOptions().setExact(true))).isVisible();
            Locator next = button(page, "Next: where it goes");
            assertThat(next).isEnabled();
            next.click();

            // Step 3 is YouTube by stream key; this test sends somewhere else.
            assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Send it to YouTube"))).isVisible();
            button(page, "Use another platform").click();
            page.waitForURL(Pattern.compile(".*/studio/streams/\\d+/destinations$"));

            // DS-2: any RTMP server, here the local sink.
            button(page, "Add destination").first().click();
            Locator add = dialog(page, "Add a destination");
            add.getByRole(AriaRole.RADIO, new Locator.GetByRoleOptions().setName("Other")).click();
            add.getByLabel("Stream address").fill(sink.url());
            add.getByLabel("Name").fill(SINK_NAME);
            button(add, "Add destination").click();
            assertThat(add).isHidden();
            Locator sendTo = page.getByRole(AriaRole.SWITCH, new Page.GetByRoleOptions().setName("Send to " + SINK_NAME));
            assertThat(sendTo).hasAttribute("aria-checked", "true");

            // GL-1: Go live from the stream's status strip. A short destination test may run first; on a busy
            // machine the capacity estimate (GL-7) may ask, and the creator's answer is "Go live anyway".
            page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Overview").setExact(true)).click();
            Locator strip = page.locator("header").filter(new Locator.FilterOptions().setHas(
                    page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName(STREAM).setLevel(1))));
            assertThat(strip.getByText("Off air", new Locator.GetByTextOptions().setExact(true))).isVisible();
            button(strip, "Go live").click();
            Locator anyway = button(page, "Go live anyway");
            Locator stop = button(strip, "Stop");
            stop.or(anyway).first().waitFor(new Locator.WaitForOptions().setTimeout(LIVE_TIMEOUT_MS));
            if (anyway.isVisible()) {
                anyway.click();
            }

            // RE-8: "Live" only once the destination is really receiving it, and the sink agrees.
            assertThat(strip.getByText("Live", new Locator.GetByTextOptions().setExact(true)))
                    .isVisible(new com.microsoft.playwright.assertions.LocatorAssertions.IsVisibleOptions().setTimeout(LIVE_TIMEOUT_MS));
            assertThat(panel(page, "Destinations").getByText("On " + SINK_NAME, new Locator.GetByTextOptions().setExact(true))).isVisible();
            long received = sink.bytes();
            assertThat(received).as("bytes the local sink received").isPositive();
            page.waitForCondition(() -> sink.bytes() > received, new Page.WaitForConditionOptions().setTimeout(STEP_TIMEOUT_MS));

            // GL-3: stop now.
            stop.click();
            Locator stopDialog = dialog(page, "Stop " + STREAM);
            button(stopDialog, "Stop now").click();
            assertThat(strip.getByText("Off air", new Locator.GetByTextOptions().setExact(true)))
                    .isVisible(new com.microsoft.playwright.assertions.LocatorAssertions.IsVisibleOptions().setTimeout(LIVE_TIMEOUT_MS));

            // RE-9: the run is listed, ended.
            Locator runs = panel(page, "Recent runs");
            assertThat(runs.getByRole(AriaRole.LISTITEM)).hasCount(1);
            assertThat(runs.getByRole(AriaRole.LISTITEM).first()).not().containsText("On air");
        });
    }

    @Test
    void atPhoneWidthTheMainScreensFitAndAPaidPlanReachesThePaymentPage(TestInfo info) {
        when(gateway.createCheckout(anyString(), anyLong(), anyString())).thenReturn(CHECKOUT_URL);
        journey(info, 375, 812, page -> {
            signUp(page, "payer-" + UUID.randomUUID().toString().substring(0, 8) + "@e2e.test");
            fitsTheScreen(page, "Setup");
            button(page, "Skip setup").click();
            page.waitForURL(Pattern.compile(".*/studio$"));

            for (Map.Entry<String, String> screen : List.of(
                    Map.entry("/studio", "Streams"),
                    Map.entry("/studio/library", "Library"),
                    Map.entry("/studio/destinations", "Destinations"),
                    Map.entry("/studio/schedule", "Schedule"),
                    Map.entry("/studio/settings", "Settings"),
                    Map.entry("/studio/settings/billing", "Billing"))) {
                page.navigate(url(screen.getKey()));
                assertThat(page.locator("main h1").first()).isVisible();
                page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE);
                fitsTheScreen(page, screen.getValue());
            }

            // BI-1: choose a paid plan, see the quote in rupees (GST included), go to the payment page.
            Locator plans = panel(page, "Plans");
            plans.getByRole(AriaRole.RADIO, new Locator.GetByRoleOptions().setName("₹").setExact(true)).click();
            plans.getByRole(AriaRole.RADIO, new Locator.GetByRoleOptions().setName("Monthly").setExact(true)).click();
            assertThat(plans.getByText("Prices include GST.")).isVisible();
            button(plans, "Upgrade to Creator").click();
            Locator quote = dialog(page, "Upgrade to Creator");
            Locator pay = quote.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName(Pattern.compile("^Pay ₹[\\d,]+(\\.\\d\\d)?$")));
            assertThat(pay).isVisible();
            assertThat(quote.getByText("Pay now", new Locator.GetByTextOptions().setExact(true))).isVisible();
            assertThat(quote).containsText("₹");
            fitsTheScreen(page, "The quote");
            String shown = pay.innerText().replaceAll("[^0-9.]", "");
            long paise = Math.round(Double.parseDouble(shown) * 100);

            page.route("https://mercury-uat.phonepe.com/**", route -> route.fulfill(new com.microsoft.playwright.Route.FulfillOptions()
                    .setStatus(200).setContentType("text/html").setBody("<!doctype html><title>PhonePe sandbox</title><h1>PhonePe sandbox (test double)</h1>")));
            pay.click();
            page.waitForURL(CHECKOUT_URL);
            assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("PhonePe sandbox (test double)"))).isVisible();
            // The order is for exactly what the quote showed, and PhonePe sends the creator back to the app.
            verify(gateway).createCheckout(anyString(), eq(paise), org.mockito.ArgumentMatchers.contains("payment_order="));
        });
    }
}
