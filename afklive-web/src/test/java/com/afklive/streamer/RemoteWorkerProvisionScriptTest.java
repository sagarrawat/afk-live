package com.afklive.streamer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * FO-8: afklive-scripts/provision-remote-worker.sh, run for real against a scratch file system (ROOT) and
 * stand-ins for apt-get, systemctl, wg, nft and the user tools, so what it installs and writes is checked
 * without a server: the files and units on arm64 and amd64, a re-run that keeps keys and secrets, a dry run
 * that changes nothing, and what it refuses.
 */
class RemoteWorkerProvisionScriptTest {

    @TempDir
    Path dir;
    Path root;
    Path state;
    Path bin;

    private static final Path SCRIPT = locate("afklive-scripts/provision-remote-worker.sh");
    private static final String PUBKEY = "x9A3kZq1Yh0bTq2m2v3Yb4p5R6s7T8u9V0w1X2y3Z4A=";

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
        root = Files.createDirectories(dir.resolve("root"));
        state = Files.createDirectories(dir.resolve("state"));
        bin = Files.createDirectories(dir.resolve("bin"));
        Files.createDirectories(root.resolve("etc"));
        Files.writeString(root.resolve("etc/os-release"), "ID=ubuntu\nVERSION_ID=\"24.04\"\n");
        String rec = "echo \"$(basename \"$0\") $*\" >> \"$STUB_STATE/calls\"\n";
        stub("id", rec + "if [ \"$1\" = -u ]; then echo \"${STUB_UID:-0}\"; else exit 1; fi");
        stub("getent", rec + "exit 2");
        for (String tool : List.of("apt-get", "systemctl", "nft", "useradd", "groupadd")) {
            stub(tool, rec + "exit 0");
        }
        stub("ffmpeg", "echo ' V..... libx264              libx264 H.264'");
        stub("wg", rec + """
                case "$1" in genkey) echo 'cHJpdmF0ZS1rZXktZm9yLXRlc3RzLW9ubHktMDAwMDAwMDA=' ;; pubkey) cat >/dev/null; echo 'cHVibGljLWtleS1mb3ItdGVzdHMtb25seS0wMDAwMDAwMDA=' ;; esac
                """);
        stub("openssl", "echo 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
    }

    private void stub(String name, String body) throws IOException {
        Path p = bin.resolve(name);
        Files.writeString(p, "#!/bin/bash\n" + body + "\n");
        Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    private record Result(int exit, String output, List<String> calls) {
    }

    private Result run(Map<String, String> extraEnv, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("/bin/bash", SCRIPT.toString()));
        command.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.put("PATH", bin + ":" + env.getOrDefault("PATH", "/usr/bin:/bin"));
        env.put("STUB_STATE", state.toString());
        env.put("ROOT", root.toString());
        env.put("ARCH", "arm64");
        env.putAll(extraEnv);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes());
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        Path calls = state.resolve("calls");
        List<String> lines = Files.exists(calls) ? Files.readAllLines(calls) : List.of();
        Files.deleteIfExists(calls);
        return new Result(p.exitValue(), out, lines);
    }

    private static final String[] ARGS = {"--pool", "playout", "--main-wg-endpoint", "203.0.113.10:51820",
            "--main-wg-pubkey", PUBKEY, "--max-streams", "6", "--deploy-key", "ssh-ed25519 AAAAtest afklive-deploy"};

    private List<Path> files() throws IOException {
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(Files::isRegularFile).toList();
        }
    }

    @Test
    void setsUpAnArmWorkerWithItsPoolTheTunnelTheUnitsAndTheFirewall() throws Exception {
        Result r = run(Map.of(), ARGS);
        assertThat(r.exit()).as(r.output()).isZero();
        assertThat(r.calls()).anyMatch(c -> c.startsWith("apt-get install") && c.contains("openjdk-21-jre-headless")
                && c.contains("ffmpeg") && c.contains("wireguard-tools") && c.contains("nftables"));
        assertThat(r.calls()).contains("useradd --system --gid afklive --home /opt/afklive/worker --shell /usr/sbin/nologin afklive-worker",
                "systemctl enable --now wg-quick@wg-afklive", "systemctl daemon-reload", "systemctl enable nftables");
        assertThat(r.calls()).anyMatch(c -> c.startsWith("nft -f ") && c.endsWith("/etc/nftables.d/afklive-worker-egress.nft"));

        String env = Files.readString(root.resolve("opt/afklive/afklive.env"));
        assertThat(env).contains("STREAMING_WORKER_POOL=playout\n", "STREAMING_PHONE_POOLS=main\n", "STREAMING_WORKER_MAX_STREAMS=6\n",
                "POSTGRES_HOST=10.77.0.1\n", "STORAGE_TYPE=s3\n", "WORKER_CONTROL_TOKEN=0123456789abcdef");
        assertThat(env).as("secrets are left for the owner to fill in").contains("POSTGRES_PASSWORD=\n", "APP_ENCRYPTION_KEY=\n", "DO_SPACES_SECRET=\n");

        String wg = Files.readString(root.resolve("etc/wireguard/wg-afklive.conf"));
        assertThat(wg).contains("Address = 10.77.0.2/24", "PublicKey = " + PUBKEY, "Endpoint = 203.0.113.10:51820",
                "AllowedIPs = 10.77.0.1/32", "PrivateKey = cHJpdmF0ZS1rZXk");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(root.resolve("etc/wireguard/wg-afklive.conf")))).isEqualTo("rw-------");
        assertThat(r.output()).as("the peer to add on the main server").contains("PublicKey = cHVibGljLWtleS", "AllowedIPs = 10.77.0.2/32");

        String nft = Files.readString(root.resolve("etc/nftables.d/afklive-worker-egress.nft"));
        assertThat(nft).contains("ip daddr 10.77.0.1 tcp dport 5432 accept", "169.254.0.0/16", "10.0.0.0/8")
                .containsSubsequence("tcp dport 5432 accept", "reject");
        assertThat(Files.readString(root.resolve("etc/nftables.conf"))).contains("include \"/etc/nftables.d/afklive-worker-egress.nft\"");

        assertThat(root.resolve("etc/systemd/system/afklive-worker@.service")).exists();
        assertThat(Files.readString(root.resolve("etc/systemd/system/afklive-worker@.service"))).contains("User=afklive-worker");
        assertThat(Files.isExecutable(root.resolve("opt/afklive/scripts/worker-handover-deploy.sh"))).isTrue();
        assertThat(Files.readString(root.resolve("opt/afklive/worker-slot-a.env"))).isEqualTo("WORKER_HTTP_PORT=8081\n");
        assertThat(Files.readString(root.resolve("opt/afklive/worker-slot-b.env"))).isEqualTo("WORKER_HTTP_PORT=8082\n");
        assertThat(Files.readString(root.resolve("etc/sudoers.d/afklive-worker-deploy")))
                .isEqualTo("deploy ALL=(root) NOPASSWD: /opt/afklive/scripts/worker-handover-deploy.sh\n");
        assertThat(Files.readString(root.resolve("home/deploy/.ssh/authorized_keys"))).contains("ssh-ed25519 AAAAtest");
    }

    @Test
    void aReRunKeepsTheKeysAndTheSecretsTheOwnerFilledIn() throws Exception {
        assertThat(run(Map.of(), ARGS).exit()).isZero();
        Path env = root.resolve("opt/afklive/afklive.env");
        Files.writeString(env, Files.readString(env).replace("POSTGRES_PASSWORD=\n", "POSTGRES_PASSWORD=filled-in\n"));
        String key = Files.readString(root.resolve("etc/wireguard/afklive.key"));

        Result again = run(Map.of(), ARGS);
        assertThat(again.exit()).as(again.output()).isZero();
        assertThat(Files.readString(env)).contains("POSTGRES_PASSWORD=filled-in");
        assertThat(Files.readString(root.resolve("etc/wireguard/afklive.key"))).isEqualTo(key);
        assertThat(again.calls()).noneMatch(c -> c.equals("wg genkey"));
        assertThat(Files.readString(root.resolve("etc/nftables.conf")).split("afklive-worker-egress", -1)).hasSize(2); // included once
    }

    @Test
    void amd64IsSupportedToo() throws Exception {
        Result r = run(Map.of("ARCH", "amd64"), ARGS);
        assertThat(r.exit()).as(r.output()).isZero();
        assertThat(r.output()).contains("Ubuntu 24.04 amd64");
    }

    @Test
    void aDryRunChangesNothingAndNeedsNoRoot() throws Exception {
        Result r = run(Map.of("STUB_UID", "1000"), concat(ARGS, "--dry-run"));
        assertThat(r.exit()).as(r.output()).isZero();
        assertThat(files()).containsExactly(root.resolve("etc/os-release"));
        assertThat(r.calls()).noneMatch(c -> c.startsWith("apt-get") || c.startsWith("systemctl") || c.startsWith("nft")
                || c.startsWith("useradd") || c.startsWith("wg"));
        assertThat(r.output()).contains("[dry-run] apt-get install -y openjdk-21-jre-headless ffmpeg",
                "[dry-run] write /etc/wireguard/wg-afklive.conf", "[dry-run] write /opt/afklive/afklive.env",
                "[dry-run] systemctl enable --now wg-quick@wg-afklive", "[dry-run] nft -f");
    }

    @Test
    void refusesWithoutRootAnotherOsAnotherArchitectureOrMissingSettings() throws Exception {
        assertThat(run(Map.of("STUB_UID", "1000"), ARGS).output()).contains("sudo");
        assertThat(run(Map.of("ARCH", "i386"), ARGS).exit()).isEqualTo(3);
        Files.writeString(root.resolve("etc/os-release"), "ID=debian\nVERSION_ID=\"12\"\n");
        assertThat(run(Map.of(), ARGS).exit()).isEqualTo(3);
        Files.writeString(root.resolve("etc/os-release"), "ID=ubuntu\nVERSION_ID=\"24.04\"\n");
        assertThat(run(Map.of(), "--pool", "playout").exit()).isEqualTo(2);
        assertThat(run(Map.of(), concat(new String[]{"--pool", "main"}, "--main-wg-endpoint", "203.0.113.10:51820",
                "--main-wg-pubkey", PUBKEY)).exit()).as("main is the main server's pool").isEqualTo(2);
        assertThat(run(Map.of(), "--pool", "playout", "--main-wg-endpoint", "203.0.113.10:51820", "--main-wg-pubkey", "nope").exit())
                .isEqualTo(2);
        assertThat(files()).containsExactly(root.resolve("etc/os-release"));
    }

    private static String[] concat(String[] a, String... b) {
        List<String> all = new ArrayList<>(List.of(a));
        all.addAll(List.of(b));
        return all.toArray(String[]::new);
    }
}
