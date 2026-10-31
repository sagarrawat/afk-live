package com.afklive.streamer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * OPS-7: afklive-scripts/sync-secrets.sh, run for real on a temporary env file, and the web deploy workflow
 * that feeds it. Secrets come from GitHub; the script must only touch allowed keys, never wipe a value with an
 * empty one, keep every other line, keep the file's mode, and never print a value.
 */
class SyncSecretsScriptTest {

    private static final Path SCRIPT = locate("afklive-scripts/sync-secrets.sh");
    private static final Path WORKFLOW = locate(".github/workflows/web-build.yml");

    @TempDir
    Path dir;
    Path env;

    private static Path locate(String relative) {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve(relative))) {
            p = p.getParent();
        }
        return p == null ? Path.of(relative) : p.resolve(relative);
    }

    record Run(int exit, String out) {
    }

    @BeforeEach
    void envFile() throws Exception {
        assumeTrue(Files.exists(Path.of("/bin/bash")) && Files.exists(SCRIPT), "bash and the script are needed");
        env = dir.resolve("afklive.env");
        Files.writeString(env, """
                # AFK Live settings
                POSTGRES_PASSWORD=db-secret
                GOOGLE_CLIENT_ID=old-id
                APP_ENCRYPTION_KEY=keep-me
                GEMINI_API_KEY=gemini-on-server
                """);
        Files.setPosixFilePermissions(env, PosixFilePermissions.fromString("rw-------"));
    }

    private Run run(String stdin) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("/bin/bash", SCRIPT.toString()).redirectErrorStream(true);
        pb.environment().put("SYNC_SECRETS_ENV_FILE", env.toString());
        pb.environment().remove("SUDO_USER");
        Process p = pb.start();
        p.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
        p.getOutputStream().close();
        assertThat(p.waitFor(30, TimeUnit.SECONDS)).isTrue();
        return new Run(p.exitValue(), new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    private List<String> lines() throws Exception {
        return Files.readAllLines(env);
    }

    @Test
    void replacesAndAddsAllowedKeysKeepsEveryOtherLineAndNeverPrintsValues() throws Exception {
        Run r = run("GOOGLE_CLIENT_ID=new-id.apps.googleusercontent.com\nGOOGLE_CLIENT_SECRET=GOCSPX-very-secret\n");

        assertThat(r.exit()).isZero();
        assertThat(r.out()).contains("Updated: GOOGLE_CLIENT_ID GOOGLE_CLIENT_SECRET")
                .doesNotContain("GOCSPX-very-secret").doesNotContain("new-id");
        assertThat(lines()).containsExactly(
                "# AFK Live settings",
                "POSTGRES_PASSWORD=db-secret",
                "GOOGLE_CLIENT_ID='new-id.apps.googleusercontent.com'",
                "APP_ENCRYPTION_KEY=keep-me",
                "GEMINI_API_KEY=gemini-on-server",
                "GOOGLE_CLIENT_SECRET='GOCSPX-very-secret'");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(env))).isEqualTo("rw-------");
        Path bak = dir.resolve("afklive.env.bak");
        assertThat(Files.readString(bak)).contains("GOOGLE_CLIENT_ID=old-id");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(bak))).isEqualTo("rw-------");
    }

    @Test
    void anEmptyValueNeverWipesTheServersValue() throws Exception {
        Run r = run("ANTHROPIC_API_KEY=\nGOOGLE_CLIENT_ID=\n");

        assertThat(r.exit()).isZero();
        assertThat(r.out()).contains("No secrets to sync");
        assertThat(lines()).contains("GEMINI_API_KEY=gemini-on-server", "GOOGLE_CLIENT_ID=old-id");
    }

    @Test
    void runningTwiceWithTheSameValuesChangesNothing() throws Exception {
        run("GOOGLE_CLIENT_ID=new-id\n");
        String after = Files.readString(env);

        Run again = run("GOOGLE_CLIENT_ID=new-id\n");

        assertThat(again.out()).contains("already up to date");
        assertThat(Files.readString(env)).isEqualTo(after);
    }

    @Test
    void refusesKeysNotOnTheListAndWritesNothing() throws Exception {
        String before = Files.readString(env);

        Run r = run("GOOGLE_CLIENT_ID=new-id\nAPP_ENCRYPTION_KEY=attacker\nPOSTGRES_PASSWORD=x\n");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.out()).contains("Refused APP_ENCRYPTION_KEY", "Refused POSTGRES_PASSWORD", "Nothing written")
                .doesNotContain("attacker");
        assertThat(Files.readString(env)).isEqualTo(before);
    }

    @Test
    void refusesAValueWithASingleQuote() throws Exception {
        String before = Files.readString(env);

        Run r = run("ANTHROPIC_API_KEY=abc'; rm -rf /\n");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.out()).contains("Refused ANTHROPIC_API_KEY").doesNotContain("rm -rf");
        assertThat(Files.readString(env)).isEqualTo(before);
    }

    @Test
    void valuesWithSpacesAndDollarSignsAreReadBackExactlyByBash() throws Exception {
        run("STORAGE_SECRET_KEY=p@ss $HOME word\n");

        Process p = new ProcessBuilder("/bin/bash", "-c", "set -a; . '" + env + "'; set +a; printf '%s' \"$STORAGE_SECRET_KEY\"").start();
        assertThat(p.waitFor(10, TimeUnit.SECONDS)).isTrue();
        assertThat(new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("p@ss $HOME word");
    }

    @Test
    void theWorkflowSyncsBeforeDeployingPassesEveryAllowedSecretAndWarnsWithoutTheSetup() throws Exception {
        String wf = Files.readString(WORKFLOW);
        String script = Files.readString(SCRIPT);

        assertThat(wf.indexOf("name: Sync secrets")).isPositive().isLessThan(wf.indexOf("name: Deploy\n"));
        assertThat(wf).contains("| sudo /opt/afklive/scripts/sync-secrets.sh", "::warning::Secrets from GitHub aren't set up");
        String allowed = script.substring(script.indexOf("ALLOWED=("), script.indexOf(")", script.indexOf("ALLOWED=(")));
        for (String key : allowed.replace("ALLOWED=(", "").trim().split("\\s+")) {
            assertThat(wf).as(key).contains(key + ": ${{ secrets." + key + " }}");
        }
        assertThat(wf).doesNotContain("secrets.APP_ENCRYPTION_KEY").doesNotContain("secrets.POSTGRES_PASSWORD");
    }
}
