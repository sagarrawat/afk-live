package com.afklive.streamer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * OPS-4: afklive-scripts/web-deploy.sh, run for real against stand-ins for systemctl and curl (the
 * readiness probe), so every path is checked without a server: the switch between slots, the move
 * from the single legacy unit, rollback, the old restart, dry runs, and each way a deploy can go wrong
 * or must refuse to start. Also checks the slot unit and the Caddyfile agree with the script.
 */
class WebDeployScriptTest {

    @TempDir
    Path dir;
    Path state;
    Path bin;
    Path jar;

    private static final Path SCRIPT = locate("afklive-scripts/web-deploy.sh");
    private static final Path UNIT = locate("afklive-scripts/systemd/afklive-web@.service");
    private static final Path CADDYFILE = locate("afklive-scripts/caddy/Caddyfile");

    private static Path locate(String relative) {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve(relative))) {
            p = p.getParent();
        }
        return p == null ? Path.of(relative) : p.resolve(relative);
    }

    @BeforeEach
    void stubs() throws Exception {
        assumeTrue(Files.exists(Path.of("/bin/bash")) && Files.exists(SCRIPT), "bash and the script are needed");
        state = Files.createDirectories(dir.resolve("state"));
        for (String d : List.of("active", "enabled", "port", "not-ready", "fail-start")) {
            Files.createDirectories(state.resolve(d));
        }
        bin = Files.createDirectories(dir.resolve("bin"));
        String realSleep = Files.exists(Path.of("/usr/bin/sleep")) ? "/usr/bin/sleep" : "/bin/sleep";
        stub("id", "echo \"${STUB_UID:-0}\"");
        stub("journalctl", "exit 0");
        stub("sleep", "exec " + realSleep + " 0.02");
        stub("systemctl", """
                S="$STUB_STATE"; echo "systemctl $*" >> "$S/calls"
                case "$1" in
                  is-active) [ -e "$S/active/$3" ] ;;
                  start|restart) [ -e "$S/fail-start/$2" ] || touch "$S/active/$2" ;;
                  stop) rm -f "$S/active/$2" ;;
                  enable) touch "$S/enabled/$3" ;;
                  disable) rm -f "$S/enabled/$3" ;;
                  cat) [ ! -e "$S/no-template" ] ;;
                esac
                """);
        // curl -fsS --max-time 3 -o /dev/null http://127.0.0.1:PORT/actuator/health/readiness
        stub("curl", """
                S="$STUB_STATE"; url=""
                for a in "$@"; do case "$a" in http*) url=$a ;; esac; done
                port=$(echo "$url" | sed 's#http://127.0.0.1:\\([0-9]*\\)/.*#\\1#')
                unit=$(cat "$S/port/$port" 2>/dev/null) || exit 7
                echo "curl $unit" >> "$S/calls"
                [ -e "$S/active/$unit" ] || exit 7
                [ -e "$S/not-ready/$unit" ] && exit 22
                exit 0
                """);
        Files.writeString(dir.resolve("web-slot-a.env"), "WEB_HTTP_PORT=8080\n");
        Files.writeString(dir.resolve("web-slot-b.env"), "WEB_HTTP_PORT=8090\n");
        Files.writeString(state.resolve("port/8080"), "afklive-web@a.service");
        Files.writeString(state.resolve("port/8090"), "afklive-web@b.service");
        memAvailableMb(4000);
        jar = Files.writeString(dir.resolve("new-app.jar"), "PK new version");
    }

    private void stub(String name, String body) throws IOException {
        Path p = bin.resolve(name);
        Files.writeString(p, "#!/bin/bash\n" + body + "\n");
        Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    private void memAvailableMb(int mb) throws IOException {
        Files.writeString(dir.resolve("meminfo"), "MemTotal:        8000000 kB\nMemAvailable:    " + (mb * 1024L) + " kB\n");
    }

    private void running(String unit) throws IOException {
        Files.createFile(state.resolve("active/" + unit));
        Files.createFile(state.resolve("enabled/" + unit));
    }

    private void installed(String slot, String content) throws IOException {
        Path p = dir.resolve("web/slots/" + slot + "/app.jar");
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    private record Result(int exit, String output, List<String> calls) {
        List<String> changes() {
            return calls.stream().filter(c -> c.matches("systemctl (start|stop|restart|enable|disable).*")).toList();
        }
    }

    private Result run(Map<String, String> extraEnv, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("/bin/bash", SCRIPT.toString()));
        command.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.put("PATH", bin + ":" + env.getOrDefault("PATH", "/usr/bin:/bin"));
        env.put("STUB_STATE", state.toString());
        env.put("ENV_DIR", dir.toString());
        env.put("WEB_DIR", dir.resolve("web").toString());
        env.put("LEGACY_JAR", dir.resolve("app.jar").toString());
        env.put("MEMINFO", dir.resolve("meminfo").toString());
        env.put("LOCK_FILE", dir.resolve("deploy.lock").toString());
        env.put("READY_TIMEOUT", "5");
        env.put("SETTLE_SECONDS", "0");
        env.putAll(extraEnv);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes());
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        Path calls = state.resolve("calls");
        return new Result(p.exitValue(), out, Files.exists(calls) ? Files.readAllLines(calls) : List.of());
    }

    private Result run(String... args) throws Exception {
        return run(Map.of(), args);
    }

    @Test
    void startsTheIdleSlotWaitsUntilItIsReadyThenStopsTheOldOne() throws Exception {
        running("afklive-web@a.service");
        Result r = run(jar.toString());

        assertThat(r.exit()).as(r.output()).isZero();
        assertThat(Files.readString(dir.resolve("web/slots/b/app.jar"))).isEqualTo("PK new version");
        assertThat(r.calls()).containsSubsequence(
                "systemctl start afklive-web@b.service",
                "curl afklive-web@b.service",
                "systemctl enable --quiet afklive-web@b.service",
                "systemctl stop afklive-web@a.service",
                "systemctl disable --quiet afklive-web@a.service");
        // The old slot is stopped only once the new one answered.
        assertThat(r.calls().indexOf("curl afklive-web@b.service")).isLessThan(r.calls().indexOf("systemctl stop afklive-web@a.service"));
        assertThat(state.resolve("active/afklive-web@b.service")).exists();
        assertThat(state.resolve("active/afklive-web@a.service")).doesNotExist();
        assertThat(state.resolve("enabled/afklive-web@a.service")).doesNotExist();
        assertThat(state.resolve("enabled/afklive-web@b.service")).exists();
    }

    @Test
    void theNextDeployGoesBackToSlotAAndKeepsThePreviousJarForRollback() throws Exception {
        running("afklive-web@b.service");
        installed("a", "PK older version");
        Result r = run(jar.toString());
        assertThat(r.exit()).as(r.output()).isZero();
        assertThat(r.changes()).containsSubsequence("systemctl start afklive-web@a.service", "systemctl stop afklive-web@b.service");
        assertThat(Files.readString(dir.resolve("web/slots/a/app.jar"))).isEqualTo("PK new version");
    }

    @Test
    void movesFromTheSingleLegacyUnitToSlotBWithoutDowntime() throws Exception {
        running("afklive.service");
        Result r = run(jar.toString());
        assertThat(r.exit()).as(r.output()).isZero();
        // The legacy unit listens on 8080 (slot a's port), so the new version must go to b.
        assertThat(r.changes()).containsSubsequence(
                "systemctl start afklive-web@b.service",
                "systemctl enable --quiet afklive-web@b.service",
                "systemctl stop afklive.service",
                "systemctl disable --quiet afklive.service");
        assertThat(state.resolve("enabled/afklive.service")).doesNotExist();
    }

    @Test
    void aNewVersionThatNeverGetsReadyIsStoppedAndTheOldOneIsNeverTouched() throws Exception {
        running("afklive-web@a.service");
        Files.createFile(state.resolve("not-ready/afklive-web@b.service"));
        Result r = run(Map.of("READY_TIMEOUT", "1"), jar.toString());
        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.output()).contains("was never touched");
        assertThat(r.changes()).containsExactly("systemctl start afklive-web@b.service", "systemctl stop afklive-web@b.service");
        assertThat(state.resolve("active/afklive-web@a.service")).exists();
        assertThat(state.resolve("enabled/afklive-web@a.service")).exists();
    }

    @Test
    void aNewVersionThatExitsWhileStartingFailsFastAndTheOldOneKeepsServing() throws Exception {
        running("afklive-web@a.service");
        Files.createFile(state.resolve("fail-start/afklive-web@b.service"));
        Result r = run(Map.of("READY_TIMEOUT", "300"), jar.toString());
        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.calls()).doesNotContain("systemctl stop afklive-web@a.service");
        assertThat(state.resolve("active/afklive-web@a.service")).exists();
    }

    @Test
    void refusesToStartASecondWebAppWithTooLittleMemory() throws Exception {
        running("afklive-web@a.service");
        memAvailableMb(1200);
        Result r = run(jar.toString());
        assertThat(r.exit()).isEqualTo(3);
        assertThat(r.output()).contains("1200 MB", "Nothing was changed");
        assertThat(r.changes()).isEmpty();
        assertThat(dir.resolve("web/slots/b/app.jar")).doesNotExist();
    }

    @Test
    void refusesWhileAWorkerDeployIsRunning() throws Exception {
        running("afklive-web@a.service");
        running("afklive-worker@a.service");
        running("afklive-worker@b.service");
        Result r = run(jar.toString());
        assertThat(r.exit()).isEqualTo(3);
        assertThat(r.output()).contains("Never run web and worker deploys at the same time");
        assertThat(r.changes()).isEmpty();
    }

    @Test
    void oneWorkerSlotRunningIsNormal() throws Exception {
        running("afklive-web@a.service");
        running("afklive-worker@b.service");
        assertThat(run(jar.toString()).exit()).isZero();
    }

    @Test
    void bothWebSlotsRunningIsRefused() throws Exception {
        running("afklive-web@a.service");
        running("afklive-web@b.service");
        Result r = run(jar.toString());
        assertThat(r.exit()).isEqualTo(3);
        assertThat(r.changes()).isEmpty();
    }

    @Test
    void withoutTheOneTimeSetupNothingHappens() throws Exception {
        running("afklive.service");
        Files.createFile(state.resolve("no-template"));
        Result r = run(jar.toString());
        assertThat(r.exit()).isEqualTo(4);
        assertThat(r.output()).contains("one-time setup");
        assertThat(r.changes()).isEmpty();

        Files.delete(state.resolve("no-template"));
        Files.delete(dir.resolve("web-slot-b.env"));
        assertThat(run(jar.toString()).exit()).isEqualTo(4);
    }

    @Test
    void aDryRunChangesNothingAndNeedsNoRoot() throws Exception {
        running("afklive-web@a.service");
        Result r = run(Map.of("STUB_UID", "1000"), "--dry-run", jar.toString());
        assertThat(r.exit()).as(r.output()).isZero();
        assertThat(r.changes()).isEmpty();
        assertThat(dir.resolve("web/slots/b/app.jar")).doesNotExist();
        assertThat(r.output()).contains(
                "[dry-run] systemctl start afklive-web@b.service",
                "[dry-run] wait until http://127.0.0.1:8090/actuator/health/readiness",
                "[dry-run] systemctl stop afklive-web@a.service");
    }

    @Test
    void withoutADryRunItNeedsRoot() throws Exception {
        running("afklive-web@a.service");
        Result r = run(Map.of("STUB_UID", "1000"), jar.toString());
        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.output()).contains("sudo");
        assertThat(r.changes()).isEmpty();
    }

    @Test
    void somethingThatIsNotAJarIsRefused() throws Exception {
        running("afklive-web@a.service");
        Files.writeString(jar, "<html>404</html>");
        Result r = run(jar.toString());
        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.changes()).isEmpty();
    }

    @Test
    void rollbackStartsThePreviousVersionInTheIdleSlotWithoutInstallingAnything() throws Exception {
        running("afklive-web@b.service");
        installed("a", "PK previous version");
        Result r = run("--rollback");
        assertThat(r.exit()).as(r.output()).isZero();
        assertThat(r.changes()).containsSubsequence("systemctl start afklive-web@a.service", "systemctl stop afklive-web@b.service");
        assertThat(Files.readString(dir.resolve("web/slots/a/app.jar"))).isEqualTo("PK previous version");
    }

    @Test
    void rollbackRightAfterTheLegacyUnitIsRefused() throws Exception {
        running("afklive.service");
        Result r = run("--rollback");
        assertThat(r.exit()).isEqualTo(3);
        assertThat(r.changes()).isEmpty();
    }

    @Test
    void theRestartPathRestartsTheRunningSlotInPlace() throws Exception {
        running("afklive-web@b.service");
        Result r = run("--restart", jar.toString());
        assertThat(r.exit()).as(r.output()).isZero();
        assertThat(r.changes()).containsExactly("systemctl restart afklive-web@b.service", "systemctl enable --quiet afklive-web@b.service");
        assertThat(Files.readString(dir.resolve("web/slots/b/app.jar"))).isEqualTo("PK new version");
    }

    @Test
    void theRestartPathStillWorksForTheLegacyUnit() throws Exception {
        running("afklive.service");
        Files.writeString(state.resolve("port/8080"), "afklive.service");
        Result r = run("--restart", jar.toString());
        assertThat(r.exit()).as(r.output()).isZero();
        assertThat(r.changes()).containsExactly("systemctl restart afklive.service");
        assertThat(Files.readString(dir.resolve("app.jar"))).isEqualTo("PK new version");
    }

    @Test
    void statusSaysWhichSlotServes() throws Exception {
        running("afklive-web@b.service");
        Result r = run(Map.of("STUB_UID", "1000"), "--status");
        assertThat(r.exit()).isZero();
        assertThat(r.output()).contains("running: b", "ready: yes (port 8090)");
    }

    @Test
    void shellcheckFindsNothing() throws Exception {
        Process which = new ProcessBuilder("/bin/sh", "-c", "command -v shellcheck").start();
        assumeTrue(which.waitFor() == 0, "shellcheck not installed");
        Process p = new ProcessBuilder("shellcheck", SCRIPT.toString()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        assertThat(p.waitFor()).as(out).isZero();
    }

    @Test
    void theSlotUnitTheCaddyfileAndTheScriptAgree() throws Exception {
        String unit = Files.readString(UNIT);
        assertThat(unit).contains("-Dserver.address=127.0.0.1", "-Dserver.port=${WEB_HTTP_PORT}", "-Dapp.streaming.role=web",
                "EnvironmentFile=/opt/afklive/web-slot-%i.env", "/opt/afklive/web/slots/%i/app.jar", "-DAFKLIVE_LOG_DIR=logs/web-%i");
        Matcher drain = Pattern.compile("-Dapp\\.web\\.shutdown-drain=(\\d+)s").matcher(unit);
        Matcher stop = Pattern.compile("(?m)^TimeoutStopSec=(\\d+)$").matcher(unit);
        assertThat(drain.find()).isTrue();
        assertThat(stop.find()).isTrue();
        // systemd must not kill a slot that is still draining or finishing requests (60 s, application.yml).
        assertThat(Integer.parseInt(stop.group(1))).isGreaterThanOrEqualTo(Integer.parseInt(drain.group(1)) + 60 + 10);

        String caddy = Files.readString(CADDYFILE);
        assertThat(caddy).contains("reverse_proxy 127.0.0.1:8080 127.0.0.1:8090", "health_uri /actuator/health/readiness",
                "respond @actuator 404", "bind 127.0.0.1");
        // The web slot ports never clash with the worker slots' (8081 for a, 8082 for b) or each other.
        Map<String, String> ports = new HashMap<>(Map.of("worker a", "8081", "worker b", "8082"));
        ports.put("web a", "8080");
        ports.put("web b", "8090");
        assertThat(ports.values()).doesNotHaveDuplicates();
    }
}
