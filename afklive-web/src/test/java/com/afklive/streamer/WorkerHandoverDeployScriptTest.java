package com.afklive.streamer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * OPS-3: afklive-scripts/worker-handover-deploy.sh, run for real against stand-ins for systemctl and the
 * workers' control endpoint (curl), so every exit path is checked without a server: the happy path, the
 * migration from the single legacy unit, and each way a deploy can go wrong. The stand-in old worker
 * "hands over" one stream each time its status is read while it is draining.
 */
class WorkerHandoverDeployScriptTest {

    @TempDir
    Path dir;
    Path state;
    Path bin;
    Path jar;

    private static final Path SCRIPT = locate("afklive-scripts/worker-handover-deploy.sh");

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
        for (String d : List.of("active", "enabled", "owned", "draining", "port")) {
            Files.createDirectories(state.resolve(d));
        }
        bin = Files.createDirectories(dir.resolve("bin"));
        String realSleep = Files.exists(Path.of("/usr/bin/sleep")) ? "/usr/bin/sleep" : "/bin/sleep";
        stub("id", "echo 0");
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
                  cat) exit 0 ;;
                esac
                """);
        // curl -fsS --max-time 10 -X METHOD -H "X-Worker-Token: T" http://127.0.0.1:PORT/internal/worker/WHAT
        stub("curl", """
                S="$STUB_STATE"; method=GET; url=""
                while [ $# -gt 0 ]; do case "$1" in -X) method=$2; shift 2 ;; -H|--max-time) shift 2 ;; http*) url=$1; shift ;; *) shift ;; esac; done
                port=$(echo "$url" | sed 's#http://127.0.0.1:\\([0-9]*\\)/.*#\\1#'); what=${url##*/}
                slot=$(cat "$S/port/$port" 2>/dev/null) || exit 7
                echo "curl $method $slot $what" >> "$S/calls"
                if [ "$slot" = legacy ]; then unit=afklive-worker.service; else unit="afklive-worker@$slot.service"; fi
                [ -e "$S/active/$unit" ] || exit 7
                [ -e "$S/no-handover-$slot" ] && exit 22
                case "$method $what" in
                  "POST drain") touch "$S/draining/$slot" ;;
                  "POST undrain") rm -f "$S/draining/$slot" ;;
                esac
                owned=$(cat "$S/owned/$slot" 2>/dev/null || echo 0)
                if [ "$method $what" = "GET status" ] && [ -e "$S/draining/$slot" ] && [ "$owned" -gt 0 ] && [ ! -e "$S/stuck" ]; then
                  owned=$((owned - 1)); echo "$owned" > "$S/owned/$slot"
                  [ -e "$S/crash-new-after-first" ] && rm -f "$S/active/afklive-worker@b.service"
                fi
                ready=true; [ -e "$S/not-ready-$slot" ] && ready=false
                d=false; [ -e "$S/draining/$slot" ] && d=true
                printf '{"workerId":"host-%s","ready":%s,"draining":%s,"owned":%s,"running":%s,"incoming":0}' "$slot" "$ready" "$d" "$owned" "$owned"
                """);
        Files.writeString(dir.resolve("afklive.env"), "SPRING_PROFILES_ACTIVE=worker\nWORKER_CONTROL_TOKEN=secret-token\n");
        Files.writeString(dir.resolve("worker-slot-a.env"), "WORKER_HTTP_PORT=8081\n");
        Files.writeString(dir.resolve("worker-slot-b.env"), "WORKER_HTTP_PORT=8082\n");
        Files.writeString(state.resolve("port/8081"), "a");
        Files.writeString(state.resolve("port/8082"), "b");
        jar = Files.writeString(dir.resolve("new-app.jar"), "new version");
    }

    private void stub(String name, String body) throws IOException {
        Path p = bin.resolve(name);
        Files.writeString(p, "#!/bin/bash\n" + body + "\n");
        Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    private void running(String unit, int owned, String slot) throws IOException {
        Files.createFile(state.resolve("active/" + unit));
        Files.createFile(state.resolve("enabled/" + unit));
        Files.writeString(state.resolve("owned/" + slot), String.valueOf(owned));
    }

    private record Result(int exit, String output, List<String> calls) {
    }

    private Result run(Map<String, String> extraEnv, String... args) throws Exception {
        List<String> command = new java.util.ArrayList<>(List.of("/bin/bash", SCRIPT.toString()));
        command.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.put("PATH", bin + ":" + env.getOrDefault("PATH", "/usr/bin:/bin"));
        env.put("STUB_STATE", state.toString());
        env.put("ENV_FILE", dir.resolve("afklive.env").toString());
        env.put("WORKER_DIR", dir.resolve("worker").toString());
        env.put("SLOT_ENV_DIR", dir.toString());
        env.put("MIN_FREE_MB", "0");
        env.put("READY_TIMEOUT", "5");
        env.putAll(extraEnv);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes());
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        Path calls = state.resolve("calls");
        return new Result(p.exitValue(), out, Files.exists(calls) ? Files.readAllLines(calls) : List.of());
    }

    @Test
    void handsEveryStreamToTheIdleSlotThenStopsTheOldOne() throws Exception {
        running("afklive-worker@a.service", 3, "a");
        Result r = run(Map.of(), jar.toString());

        assertThat(r.exit()).as(r.output()).isZero();
        assertThat(Files.readString(dir.resolve("worker/slots/b/app.jar"))).isEqualTo("new version");
        assertThat(r.calls()).containsSubsequence(
                "curl GET a status",
                "systemctl start afklive-worker@b.service",
                "curl GET b status",
                "curl POST a drain",
                "systemctl stop afklive-worker@a.service",
                "systemctl disable --quiet afklive-worker@a.service",
                "systemctl enable --quiet afklive-worker@b.service");
        assertThat(state.resolve("active/afklive-worker@b.service")).exists();
        assertThat(state.resolve("active/afklive-worker@a.service")).doesNotExist();
        assertThat(state.resolve("enabled/afklive-worker@a.service")).doesNotExist();
        assertThat(r.output()).contains("old worker still owns 0");
    }

    @Test
    void theNextDeployGoesBackToSlotA() throws Exception {
        running("afklive-worker@b.service", 1, "b");
        Result r = run(Map.of(), jar.toString());
        assertThat(r.exit()).as(r.output()).isZero();
        assertThat(r.calls()).contains("systemctl start afklive-worker@a.service", "curl POST b drain", "systemctl stop afklive-worker@b.service");
        assertThat(dir.resolve("worker/slots/a/app.jar")).exists();
    }

    @Test
    void migratesFromTheSingleLegacyUnitWithoutInterruption() throws Exception {
        running("afklive-worker.service", 2, "legacy");
        Files.writeString(state.resolve("port/8081"), "legacy"); // the legacy worker listens on the default port
        Result r = run(Map.of(), jar.toString());
        assertThat(r.exit()).as(r.output()).isZero();
        assertThat(r.calls()).containsSubsequence("systemctl start afklive-worker@b.service", "curl POST legacy drain",
                "systemctl stop afklive-worker.service", "systemctl disable --quiet afklive-worker.service",
                "systemctl enable --quiet afklive-worker@b.service");
    }

    @Test
    void aRunningWorkerWithoutTheHandoverIsLeftAloneAndNothingStarts() throws Exception {
        running("afklive-worker@a.service", 2, "a");
        Files.createFile(state.resolve("no-handover-a"));
        Result r = run(Map.of(), jar.toString());
        assertThat(r.exit()).isEqualTo(4);
        assertThat(r.output()).contains("--restart");
        assertThat(r.calls()).noneMatch(c -> c.startsWith("systemctl start") || c.startsWith("systemctl stop"));
    }

    @Test
    void aNewVersionThatNeverGetsReadyIsStoppedBeforeTheOldOneIsTouched() throws Exception {
        running("afklive-worker@a.service", 2, "a");
        Files.createFile(state.resolve("not-ready-b"));
        Result r = run(Map.of("READY_TIMEOUT", "1"), jar.toString());
        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.calls()).contains("systemctl stop afklive-worker@b.service").doesNotContain("curl POST a drain");
        assertThat(state.resolve("active/afklive-worker@a.service")).exists();
    }

    @Test
    void ifTheNewVersionDiesMidwayTheOldOneStopsDrainingAndKeepsItsStreams() throws Exception {
        running("afklive-worker@a.service", 3, "a");
        Files.createFile(state.resolve("crash-new-after-first"));
        Result r = run(Map.of(), jar.toString());
        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.calls()).containsSubsequence("curl POST a drain", "curl POST a undrain")
                .doesNotContain("systemctl stop afklive-worker@a.service");
        assertThat(state.resolve("active/afklive-worker@a.service")).exists();
        assertThat(state.resolve("draining/a")).doesNotExist();
    }

    @Test
    void streamsThatDontMoveInTimeAreReleasedByStoppingTheOldWorker() throws Exception {
        running("afklive-worker@a.service", 2, "a");
        Files.createFile(state.resolve("stuck"));
        Result r = run(Map.of("MIN_DRAIN_SECONDS", "1", "PER_STREAM_SECONDS", "0"), jar.toString());
        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.output()).contains("not handed over");
        assertThat(r.calls()).contains("systemctl stop afklive-worker@a.service", "systemctl enable --quiet afklive-worker@b.service");
    }

    @Test
    void bothSlotsRunningIsRefused() throws Exception {
        running("afklive-worker@a.service", 1, "a");
        running("afklive-worker@b.service", 1, "b");
        Result r = run(Map.of(), jar.toString());
        assertThat(r.exit()).isEqualTo(3);
        assertThat(r.calls()).noneMatch(c -> c.startsWith("systemctl start") || c.startsWith("systemctl stop"));
    }

    @Test
    void withoutATokenNothingHappens() throws Exception {
        running("afklive-worker@a.service", 1, "a");
        Files.writeString(dir.resolve("afklive.env"), "SPRING_PROFILES_ACTIVE=worker\n");
        Result r = run(Map.of(), jar.toString());
        assertThat(r.exit()).isEqualTo(4);
        assertThat(r.output()).contains("WORKER_CONTROL_TOKEN");
        assertThat(r.calls()).noneMatch(c -> c.startsWith("systemctl start"));
    }

    @Test
    void theRestartPathRestartsTheRunningSlot() throws Exception {
        running("afklive-worker@b.service", 1, "b");
        Result r = run(Map.of(), "--restart", jar.toString());
        assertThat(r.exit()).as(r.output()).isZero();
        assertThat(r.calls()).contains("systemctl restart afklive-worker@b.service");
        assertThat(Files.readString(dir.resolve("worker/slots/b/app.jar"))).isEqualTo("new version");
    }

    @Test
    void theRestartPathStillWorksForTheLegacyUnit() throws Exception {
        running("afklive-worker.service", 1, "legacy");
        Result r = run(Map.of(), "--restart", jar.toString());
        assertThat(r.exit()).as(r.output()).isZero();
        assertThat(r.calls()).contains("systemctl restart afklive-worker.service");
        assertThat(Files.readString(dir.resolve("worker/app.jar"))).isEqualTo("new version");
    }
}
