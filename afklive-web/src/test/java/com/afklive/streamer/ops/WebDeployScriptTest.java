package com.afklive.streamer.ops;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * OPS-4: afklive-scripts/web-deploy.sh against stand-ins for sudo, systemctl and curl. Checks the
 * order that makes a deploy safe (new instance ready before the old one stops), that a version
 * that never gets ready leaves the old one serving, dry-run changes nothing, and servers without
 * the blue/green units still get the old restart.
 */
class WebDeployScriptTest {

    private static final Path SCRIPT = Path.of("..", "afklive-scripts", "web-deploy.sh").toAbsolutePath().normalize();

    @TempDir
    Path tmp;
    private Path home;
    private Path stubs;
    private Path state;
    private Path jar;

    record Result(int exit, String output, List<String> calls) {
    }

    @BeforeEach
    void setUp() throws IOException {
        assumeTrue(Files.isExecutable(Path.of("/bin/bash")) || Files.isExecutable(Path.of("/usr/bin/bash")), "needs bash");
        assumeTrue(Files.isExecutable(Path.of("/usr/bin/flock")) || Files.isExecutable(Path.of("/bin/flock")), "needs flock (util-linux)");
        assertThat(SCRIPT).exists();
        home = Files.createDirectories(tmp.resolve("opt-afklive"));
        Files.createDirectories(home.resolve("incoming"));
        stubs = Files.createDirectories(tmp.resolve("bin"));
        state = Files.createDirectories(tmp.resolve("state"));
        Files.createDirectories(state.resolve("active"));
        jar = home.resolve("incoming/app.jar");
        Files.write(jar, "PK\u0003\u0004new-version".getBytes(StandardCharsets.ISO_8859_1));

        stub("sudo", """
                [ "$1" = -n ] && shift
                echo "$*" >> "$STUB_STATE/calls"
                exec "$@"
                """);
        stub("systemctl", """
                case "$1" in
                  is-active) [ "$2" = --quiet ] && shift; [ -f "$STUB_STATE/active/$2" ]; exit $? ;;
                  cat) [ -f "$STUB_STATE/template" ]; exit $? ;;
                  start|restart) touch "$STUB_STATE/active/$2" ;;
                  stop) rm -f "$STUB_STATE/active/$2" ;;
                esac
                exit 0
                """);
        stub("curl", """
                for a in "$@"; do url="$a"; done
                port=$(echo "$url" | sed 's#.*127.0.0.1:\\([0-9]*\\)/.*#\\1#')
                [ -f "$STUB_STATE/never-ready" ] && exit 22
                [ -f "$STUB_STATE/active/afklive@$port" ] && exit 0
                [ "$port" = 8080 ] && [ -f "$STUB_STATE/active/afklive" ] && exit 0
                exit 7
                """);
        stub("sleep", "exit 0\n");
        stub("journalctl", "echo 'stub journal'\n");
    }

    private void stub(String name, String body) throws IOException {
        Path p = stubs.resolve(name);
        Files.writeString(p, "#!/bin/sh\n" + body);
        Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    private void blueGreenServer(String running) throws IOException {
        Files.createFile(state.resolve("template"));
        if (running != null) {
            Files.createFile(state.resolve("active/afklive@" + running));
        }
    }

    private Result run(String... args) throws Exception {
        Files.deleteIfExists(state.resolve("calls"));
        List<String> cmd = new java.util.ArrayList<>(List.of("bash", SCRIPT.toString()));
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.put("PATH", stubs + ":" + env.getOrDefault("PATH", "/usr/bin:/bin"));
        env.put("AFKLIVE_HOME", home.toString());
        env.put("STUB_STATE", state.toString());
        env.put("READY_TIMEOUT", "6");
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        Path calls = state.resolve("calls");
        List<String> lines = Files.exists(calls) ? Files.readAllLines(calls) : List.of();
        return new Result(p.exitValue(), out, lines);
    }

    private boolean active(String unit) {
        return Files.exists(state.resolve("active/" + unit));
    }

    @Test
    void startsTheIdleInstanceBeforeStoppingTheLiveOne() throws Exception {
        blueGreenServer("8080");

        Result r = run(jar.toString());

        assertThat(r.exit()).as(r.output()).isZero();
        assertThat(r.calls()).containsExactly(
                "systemctl start afklive@8082",
                "systemctl enable --quiet afklive@8082",
                "systemctl disable --quiet afklive@8080",
                "systemctl stop afklive@8080");
        assertThat(home.resolve("app-8082.jar")).hasSameBinaryContentAs(jar);
        assertThat(home.resolve("app-8080.jar")).doesNotExist();
        assertThat(Files.readString(home.resolve("web-slot")).trim()).isEqualTo("8082");
        assertThat(active("afklive@8082")).isTrue();
        assertThat(active("afklive@8080")).isFalse();

        // The next deploy goes back to the first slot.
        Result next = run(jar.toString());
        assertThat(next.exit()).as(next.output()).isZero();
        assertThat(next.calls()).containsExactly(
                "systemctl start afklive@8080",
                "systemctl enable --quiet afklive@8080",
                "systemctl disable --quiet afklive@8082",
                "systemctl stop afklive@8082");
    }

    @Test
    void aVersionThatNeverGetsReadyLeavesTheOldOneServing() throws Exception {
        blueGreenServer("8080");
        Files.createFile(state.resolve("never-ready"));

        Result r = run(jar.toString());

        assertThat(r.exit()).isNotZero();
        assertThat(r.output()).contains("didn't get ready").contains("afklive@8080 keeps serving");
        assertThat(r.calls()).containsExactly("systemctl start afklive@8082", "systemctl stop afklive@8082");
        assertThat(active("afklive@8080")).isTrue();
        assertThat(home.resolve("web-slot")).doesNotExist();
    }

    @Test
    void dryRunChangesNothing() throws Exception {
        blueGreenServer("8080");

        Result r = run("--dry-run", jar.toString());

        assertThat(r.exit()).as(r.output()).isZero();
        assertThat(r.calls()).isEmpty();
        assertThat(r.output())
                .contains("[dry-run] sudo -n systemctl start afklive@8082")
                .contains("[dry-run] sudo -n systemctl stop afklive@8080")
                .contains("/actuator/health/readiness");
        assertThat(home.resolve("app-8082.jar")).doesNotExist();
        assertThat(home.resolve("web-slot")).doesNotExist();
        assertThat(active("afklive@8080")).isTrue();
    }

    @Test
    void serversWithoutTheBlueGreenUnitsGetTheOldRestart() throws Exception {
        Files.createFile(state.resolve("active/afklive"));

        Result r = run(jar.toString());

        assertThat(r.exit()).as(r.output()).isZero();
        assertThat(r.calls()).containsExactly("systemctl restart afklive");
        assertThat(home.resolve("app.jar")).hasSameBinaryContentAs(jar);
    }

    @Test
    void restartModeReplacesTheLiveInstanceInPlace() throws Exception {
        blueGreenServer("8082");

        Result r = run("--restart", jar.toString());

        assertThat(r.exit()).as(r.output()).isZero();
        assertThat(r.calls()).containsExactly("systemctl restart afklive@8082", "systemctl enable --quiet afklive@8082");
        assertThat(home.resolve("app-8082.jar")).hasSameBinaryContentAs(jar);
    }

    @Test
    void rollbackStartsThePreviousJarWithoutInstallingOne() throws Exception {
        blueGreenServer("8082");
        Files.writeString(home.resolve("app-8080.jar"), "PKprevious");

        Result r = run("--rollback");

        assertThat(r.exit()).as(r.output()).isZero();
        assertThat(r.calls()).containsExactly(
                "systemctl start afklive@8080",
                "systemctl enable --quiet afklive@8080",
                "systemctl disable --quiet afklive@8082",
                "systemctl stop afklive@8082");
        assertThat(Files.readString(home.resolve("app-8080.jar"))).isEqualTo("PKprevious");
    }

    @Test
    void refusesAFileThatIsNotAJar() throws Exception {
        blueGreenServer("8080");
        Files.writeString(jar, "<html>404</html>");

        Result r = run(jar.toString());

        assertThat(r.exit()).isNotZero();
        assertThat(r.output()).contains("not a jar");
        assertThat(r.calls()).isEmpty();
    }

    @Test
    void interruptedDeployWithBothRunningKeepsTheRecordedOne() throws Exception {
        blueGreenServer("8080");
        Files.createFile(state.resolve("active/afklive@8082"));
        Files.writeString(home.resolve("web-slot"), "8082\n");

        Result r = run(jar.toString());

        assertThat(r.exit()).as(r.output()).isZero();
        assertThat(r.calls()).containsExactly(
                "systemctl stop afklive@8080",
                "systemctl start afklive@8080",
                "systemctl enable --quiet afklive@8080",
                "systemctl disable --quiet afklive@8082",
                "systemctl stop afklive@8082");
    }

    @Test
    void helpPrintsUsage() throws Exception {
        Result r = run("--help");
        assertThat(r.exit()).isZero();
        assertThat(r.output()).contains("Usage: web-deploy.sh").doesNotContain("set -euo");
    }
}
