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
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * OPS-4 / OPS-3: afklive-scripts/upload-jar.sh, which the deploy workflows use to send only what changed in
 * app.jar. Runs the real script and real rsync; only ssh is a stand-in that runs the "remote" command on this
 * machine (with a PATH of the test's choosing), so the delta transfer, the rename into place and the checks
 * are the real thing. Also checks both workflows keep the whole-jar copy as an option and as the fallback.
 */
class UploadJarScriptTest {

    @TempDir
    Path dir;
    Path bin;
    Path server;
    Path sshLog;

    private static final Path SCRIPT = locate("afklive-scripts/upload-jar.sh");
    private static final Path WEB_WORKFLOW = locate(".github/workflows/web-build.yml");
    private static final Path WORKER_WORKFLOW = locate(".github/workflows/worker-deploy.yml");

    private static Path locate(String relative) {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve(relative))) {
            p = p.getParent();
        }
        return p == null ? Path.of(relative) : p.resolve(relative);
    }

    private static boolean onPath(String tool) {
        for (String d : System.getenv().getOrDefault("PATH", "/usr/bin:/bin").split(":")) {
            if (Files.isExecutable(Path.of(d, tool))) {
                return true;
            }
        }
        return false;
    }

    @BeforeEach
    void stubs() throws Exception {
        assumeTrue(Files.exists(Path.of("/bin/bash")) && Files.exists(SCRIPT), "bash and the script are needed");
        assumeTrue(onPath("rsync") && onPath("sha256sum"), "rsync and sha256sum are needed");
        bin = Files.createDirectories(dir.resolve("bin"));
        server = Files.createDirectories(dir.resolve("server/incoming"));
        sshLog = dir.resolve("ssh.log");
        // ssh [-i key] [-p port] [-l user] [-o opt]... [user@]host command...: logs the call, runs the command here.
        stub("ssh", """
                key=""; opts=""; user=""
                while [ $# -gt 0 ]; do
                  case "$1" in
                    -i) key=$2; shift 2 ;;
                    -l) user=$2@; shift 2 ;;
                    -p) shift 2 ;;
                    -o) opts="$opts $2"; shift 2 ;;
                    -*) shift ;;
                    *) break ;;
                  esac
                done
                dest=$1; shift
                echo "ssh $user$dest key-mode=$(stat -c %a "$key") key=$(head -c 12 "$key")$opts" >> "$STUB_SSH_LOG"
                exec env PATH="$STUB_REMOTE_PATH" /bin/sh -c "$*"
                """);
    }

    private void stub(String name, String body) throws IOException {
        Path p = bin.resolve(name);
        Files.writeString(p, "#!/bin/bash\n" + body + "\n");
        Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    private record Result(int exit, String output) {
        long sent() {
            Matcher m = Pattern.compile("sent (\\d+) bytes").matcher(output);
            assertThat(m.find()).as(output).isTrue();
            return Long.parseLong(m.group(1));
        }
    }

    private Result run(Map<String, String> extraEnv, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("/bin/bash", SCRIPT.toString()));
        command.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        String path = env.getOrDefault("PATH", "/usr/bin:/bin");
        env.put("PATH", bin + ":" + path);
        env.put("STUB_REMOTE_PATH", path);
        env.put("STUB_SSH_LOG", sshLog.toString());
        env.put("DO_HOST", "droplet.example");
        env.put("DO_USER", "deploy");
        env.put("DO_SSH_KEY", "-----BEGIN OPENSSH PRIVATE KEY-----\nstand-in\n-----END OPENSSH PRIVATE KEY-----");
        env.putAll(extraEnv);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes());
        assertThat(p.waitFor(120, TimeUnit.SECONDS)).isTrue();
        return new Result(p.exitValue(), out);
    }

    private Result run(String... args) throws Exception {
        return run(Map.of(), args);
    }

    /** A stand-in for a fat jar: "PK", then random (incompressible) bytes, like stored nested jars. */
    private static byte[] jarBytes(int size, long seed) {
        byte[] b = new byte[size];
        new Random(seed).nextBytes(b);
        b[0] = 'P';
        b[1] = 'K';
        return b;
    }

    /** The next build: the same "libraries", a changed "class" in the middle and a few bytes more at the end. */
    private static byte[] nextBuild(byte[] previous) {
        byte[] next = new byte[previous.length + 300];
        System.arraycopy(previous, 0, next, 0, previous.length);
        byte[] changed = jarBytes(20_000, 99);
        System.arraycopy(changed, 2, next, previous.length / 2, changed.length - 2);
        System.arraycopy(jarBytes(300, 7), 0, next, previous.length, 300);
        return next;
    }

    @Test
    void theFirstUploadSendsTheWholeJar() throws Exception {
        byte[] jar = jarBytes(2_000_000, 1);
        Path local = Files.write(dir.resolve("app.jar"), jar);
        Path remote = server.resolve("app.jar");

        Result r = run(local.toString(), remote.toString());

        assertThat(r.exit()).as(r.output()).isZero();
        assertThat(r.output()).contains("sending the whole jar this once");
        assertThat(Files.readAllBytes(remote)).isEqualTo(jar);
        assertThat(r.sent()).isGreaterThan(jar.length * 9L / 10);
        // The key reaches ssh as a private file, and both ssh calls and rsync go to DO_USER@DO_HOST.
        List<String> calls = Files.readAllLines(sshLog);
        assertThat(calls).hasSizeGreaterThanOrEqualTo(3)
                .allMatch(c -> c.startsWith("ssh deploy@droplet.example key-mode=600 key=-----BEGIN"))
                .allMatch(c -> c.contains("BatchMode=yes"));
    }

    @Test
    void theNextUploadSendsOnlyWhatChanged() throws Exception {
        byte[] previous = jarBytes(4_000_000, 1);
        Path remote = Files.write(server.resolve("app.jar"), previous);
        byte[] next = nextBuild(previous);
        Path local = Files.write(dir.resolve("app.jar"), next);

        Result r = run(local.toString(), remote.toString());

        assertThat(r.exit()).as(r.output()).isZero();
        assertThat(r.output()).contains("sending what changed");
        assertThat(Files.readAllBytes(remote)).isEqualTo(next);
        // About 20 KB changed of 4 MB: a few percent at most goes over the wire.
        assertThat(r.sent()).as(r.output()).isLessThan(next.length / 20L);
        assertThat(Files.getPosixFilePermissions(remote)).isEqualTo(PosixFilePermissions.fromString("rw-r--r--"));
    }

    @Test
    void aProcessThatHasTheOldJarOpenKeepsReadingTheOldJar() throws Exception {
        byte[] previous = jarBytes(1_000_000, 1);
        Path remote = Files.write(server.resolve("app.jar"), previous);
        // A hard link sees whatever happens to the file itself, as a running JVM's open file would.
        Path running = Files.createLink(dir.resolve("running.jar"), remote);
        byte[] next = nextBuild(previous);
        Path local = Files.write(dir.resolve("app.jar"), next);

        Result r = run(local.toString(), remote.toString());

        assertThat(r.exit()).as(r.output()).isZero();
        assertThat(Files.readAllBytes(remote)).isEqualTo(next);
        assertThat(Files.readAllBytes(running)).isEqualTo(previous);
        try (var files = Files.list(server)) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactly("app.jar"); // no temporary left over
        }
    }

    @Test
    void withoutRsyncOnTheServerItExitsFiveAndChangesNothing() throws Exception {
        byte[] previous = jarBytes(100_000, 1);
        Path remote = Files.write(server.resolve("app.jar"), previous);
        Path local = Files.write(dir.resolve("app.jar"), nextBuild(previous));
        Path remoteBin = Files.createDirectories(dir.resolve("remote-bin")); // the server's PATH: no rsync on it

        Result r = run(Map.of("STUB_REMOTE_PATH", remoteBin.toString()), local.toString(), remote.toString());

        assertThat(r.exit()).as(r.output()).isEqualTo(5);
        assertThat(r.output()).contains("rsync isn't installed on the server");
        assertThat(Files.readAllBytes(remote)).isEqualTo(previous);
    }

    @Test
    void aMissingFolderOnTheServerFailsWithoutCreatingIt() throws Exception {
        Path local = Files.write(dir.resolve("app.jar"), jarBytes(10_000, 1));
        Path remote = dir.resolve("server/nowhere/app.jar");

        Result r = run(local.toString(), remote.toString());

        assertThat(r.exit()).as(r.output()).isEqualTo(1);
        assertThat(r.output()).contains("doesn't exist on the server");
        assertThat(remote.getParent()).doesNotExist();
    }

    @Test
    void refusesSomethingThatIsNotAJarAndOddRemotePathsBeforeConnecting() throws Exception {
        Path notJar = Files.writeString(dir.resolve("app.jar"), "<html>not a jar</html>");
        Result r = run(notJar.toString(), server.resolve("app.jar").toString());
        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.output()).contains("not a jar file");

        Path jar = Files.write(dir.resolve("real.jar"), jarBytes(10_000, 1));
        assertThat(run(jar.toString(), "incoming/app.jar").output()).contains("must be an absolute path");
        assertThat(run(jar.toString(), "/opt/afklive/x'; rm -rf /;'.jar").exit()).isEqualTo(1);
        assertThat(sshLog).doesNotExist();
    }

    @Test
    void bothWorkflowsUploadOnlyWhatChangedAndKeepTheWholeJarCopyAsOptionAndFallback() throws Exception {
        for (Path workflow : List.of(WEB_WORKFLOW, WORKER_WORKFLOW)) {
            String yml = Files.readString(workflow);
            assertThat(yml).as(workflow.toString())
                    .contains("group: production-deploy")
                    .contains("afklive-scripts/upload-jar.sh afklive-web/target/app.jar")
                    .contains("DO_SSH_KEY: ${{ secrets.DO_SSH_KEY }}")
                    .contains("continue-on-error: true")
                    .contains("- full")
                    .contains("appleboy/scp-action")
                    .contains("== 'failure'");
        }
        // Deltas need the previous jar where the new one lands: the paths the deploy scripts are given.
        assertThat(Files.readString(WEB_WORKFLOW)).contains("upload-jar.sh afklive-web/target/app.jar /opt/afklive/incoming/web/app.jar");
        String worker = Files.readString(WORKER_WORKFLOW);
        assertThat(worker).contains("upload-jar.sh afklive-web/target/app.jar /opt/afklive/worker/incoming/app.jar")
                .contains("upload-jar.sh afklive-web/target/app.jar /opt/afklive/worker/app.jar");
    }
}
