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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * FO-8: afklive-scripts/deploy-worker-hosts.sh, the opt-in deploy to extra worker hosts, run for real against
 * stand-ins for scp and ssh: hosts in order with pinned host keys, the first failure stops the rest, and
 * nothing happens without hosts, a key and known host keys.
 */
class WorkerHostsDeployScriptTest {

    @TempDir
    Path dir;
    Path state;
    Path bin;
    Path jar;

    private static final Path SCRIPT = locate("afklive-scripts/deploy-worker-hosts.sh");

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
        bin = Files.createDirectories(dir.resolve("bin"));
        // Record the call; fail for a host listed in $STUB_STATE/fail-<host>. Also check the pinned host keys exist.
        String body = """
                echo "$(basename "$0") $*" >> "$STUB_STATE/calls"
                kh=""; for a in "$@"; do case "$a" in UserKnownHostsFile=*) kh=${a#UserKnownHostsFile=} ;; esac; done
                [ -s "$kh" ] || { echo "no known hosts" >&2; exit 255; }
                for a in "$@"; do h=${a%%:*}; h=${h#*@}; [ -e "$STUB_STATE/fail-$h" ] && exit 1; done
                exit 0
                """;
        stub("scp", body);
        stub("ssh", body);
        jar = Files.writeString(dir.resolve("app.jar"), "new version");
    }

    private void stub(String name, String body) throws IOException {
        Path p = bin.resolve(name);
        Files.writeString(p, "#!/bin/bash\n" + body + "\n");
        Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    private record Result(int exit, String output, List<String> calls) {
    }

    private Result run(Map<String, String> env, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("/bin/bash", SCRIPT.toString()));
        command.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
        Map<String, String> e = pb.environment();
        e.put("PATH", bin + ":" + e.getOrDefault("PATH", "/usr/bin:/bin"));
        e.put("STUB_STATE", state.toString());
        e.remove("WORKER_HOSTS");
        e.putAll(env);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes());
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        Path calls = state.resolve("calls");
        return new Result(p.exitValue(), out, Files.exists(calls) ? Files.readAllLines(calls) : List.of());
    }

    private static Map<String, String> settings(String hosts) {
        Map<String, String> m = new HashMap<>();
        m.put("WORKER_HOSTS", hosts);
        m.put("SSH_KEY", "-----BEGIN OPENSSH PRIVATE KEY-----\ntest\n-----END OPENSSH PRIVATE KEY-----");
        m.put("KNOWN_HOSTS", "|1|abc= ssh-ed25519 AAAAhostkey");
        return m;
    }

    @Test
    void deploysEachHostInTurnWithPinnedHostKeys() throws Exception {
        Result r = run(settings("oracle-1.example.net, ops@hetzner-1.example.net:2222\nfra-2.example.net"), jar.toString());
        assertThat(r.exit()).as(r.output()).isZero();
        assertThat(r.calls()).hasSize(6);
        assertThat(r.calls().get(0)).startsWith("scp -q -P 22 ").contains("StrictHostKeyChecking=yes", "BatchMode=yes")
                .endsWith(jar + " deploy@oracle-1.example.net:/opt/afklive/worker/incoming/app.jar");
        assertThat(r.calls().get(1)).startsWith("ssh -p 22 ").contains("deploy@oracle-1.example.net")
                .endsWith("sudo /opt/afklive/scripts/worker-handover-deploy.sh /opt/afklive/worker/incoming/app.jar");
        assertThat(r.calls().get(2)).startsWith("scp -q -P 2222 ").endsWith("ops@hetzner-1.example.net:/opt/afklive/worker/incoming/app.jar");
        assertThat(r.calls().get(3)).startsWith("ssh -p 2222 ").contains("ops@hetzner-1.example.net");
        assertThat(r.calls().get(5)).contains("deploy@fra-2.example.net");
        assertThat(r.output()).contains("all 3 extra hosts deployed");
    }

    @Test
    void theFirstFailureStopsTheRest() throws Exception {
        Files.createFile(state.resolve("fail-hetzner-1.example.net"));
        Result r = run(settings("oracle-1.example.net hetzner-1.example.net fra-2.example.net"), jar.toString());
        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.output()).contains("hetzner-1.example.net", "keep the old version");
        assertThat(r.calls()).noneMatch(c -> c.contains("fra-2"));
    }

    @Test
    void theRestartMethodRestartsEachHost() throws Exception {
        Result r = run(settings("oracle-1.example.net"), "--restart", jar.toString());
        assertThat(r.exit()).as(r.output()).isZero();
        assertThat(r.calls().get(1)).endsWith("sudo /opt/afklive/scripts/worker-handover-deploy.sh --restart /opt/afklive/worker/incoming/app.jar");
    }

    @Test
    void nothingHappensWithoutHostsAKeyOrPinnedHostKeys() throws Exception {
        assertThat(run(settings(""), jar.toString()).exit()).isEqualTo(2);
        Map<String, String> noKnownHosts = settings("oracle-1.example.net");
        noKnownHosts.put("KNOWN_HOSTS", "");
        assertThat(run(noKnownHosts, jar.toString()).exit()).isEqualTo(2);
        Map<String, String> noKey = settings("oracle-1.example.net");
        noKey.put("SSH_KEY", "");
        assertThat(run(noKey, jar.toString()).exit()).isEqualTo(2);
        assertThat(run(settings("oracle-1.example.net"), dir.resolve("missing.jar").toString()).exit()).isEqualTo(2);
        assertThat(state.resolve("calls")).doesNotExist();
    }

    @Test
    void aDryRunOnlyPrints() throws Exception {
        Result r = run(Map.of("WORKER_HOSTS", "oracle-1.example.net"), "--dry-run", jar.toString());
        assertThat(r.exit()).as(r.output()).isZero();
        assertThat(r.calls()).isEmpty();
        assertThat(r.output()).contains("[dry-run] scp -P 22", "[dry-run] ssh -p 22 deploy@oracle-1.example.net");
    }
}
