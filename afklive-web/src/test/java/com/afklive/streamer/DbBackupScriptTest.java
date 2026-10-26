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
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Nightly database backups: afklive-scripts/db-backup.sh and db-restore.sh, run for real against
 * stand-ins for pg_dump, pg_restore, psql, rclone (a folder plays the bucket) and curl (the ping), so
 * upload, weekly copies, retention, every failure path and secret handling are checked without a server.
 */
class DbBackupScriptTest {

    private static final String DB_PASSWORD = "db-pass-should-never-print";
    private static final String SECRET = "r2-secret-should-never-print";

    @TempDir
    Path dir;
    Path bin;
    Path bucket;
    Path calls;

    private static final Path BACKUP = locate("afklive-scripts/db-backup.sh");
    private static final Path RESTORE = locate("afklive-scripts/db-restore.sh");
    private static final Path LEGACY = locate("afklive-scripts/backup_db.sh");
    private static final Path SERVICE = locate("afklive-scripts/systemd/afklive-db-backup.service");
    private static final Path TIMER = locate("afklive-scripts/systemd/afklive-db-backup.timer");

    private static Path locate(String relative) {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve(relative))) {
            p = p.getParent();
        }
        return p == null ? Path.of(relative) : p.resolve(relative);
    }

    @BeforeEach
    void stubs() throws Exception {
        assumeTrue(Files.exists(Path.of("/bin/bash")) && Files.exists(BACKUP), "bash and the script are needed");
        bin = Files.createDirectories(dir.resolve("bin"));
        bucket = Files.createDirectories(dir.resolve("bucket"));
        calls = dir.resolve("calls");
        Files.createDirectories(dir.resolve("tmp"));
        // pg_dump ... --file=PATH : writes a fake archive; records its args and whether it got the password.
        stub("pg_dump", """
                echo "pg_dump $* PGPASSWORD_SET=${PGPASSWORD:+yes}" >> "$STUB_CALLS"
                [ -e "$STUB_DIR/fail-pg_dump" ] && { echo "connection refused" >&2; exit 1; }
                for a in "$@"; do case "$a" in --file=*) f=${a#--file=} ;; esac; done
                if [ -e "$STUB_DIR/empty-dump" ]; then : > "$f"; else printf 'PGDMP fake archive' > "$f"; fi
                """);
        stub("pg_restore", """
                echo "pg_restore $*" >> "$STUB_CALLS"
                last="${*: -1}"
                grep -q PGDMP "$last" || { echo "not a custom archive" >&2; exit 1; }
                [ -e "$STUB_DIR/corrupt-dump" ] && [ "$1" = "--list" ] && exit 1
                exit 0
                """);
        // psql ... -d DB -c SQL : "tables" answers from $STUB_DIR/tables-<db> (default 0).
        stub("psql", """
                for a in "$@"; do case "$prev" in -d) db=$a ;; -c) sql=$a ;; esac; prev=$a; done
                echo "psql $db $sql" >> "$STUB_CALLS"
                [ -e "$STUB_DIR/no-db-$db" ] && exit 2
                case "$sql" in
                  *pg_class*) cat "$STUB_DIR/tables-$db" 2>/dev/null || echo 0 ;;
                  *) echo 1 ;;
                esac
                """);
        // rclone with remote "afkbackup:BUCKET/PATH" mapped to $STUB_DIR/bucket/BUCKET/PATH.
        stub("rclone", """
                echo "rclone $*" >> "$STUB_CALLS"
                env | grep '^RCLONE_CONFIG_AFKBACKUP_' | sort > "$STUB_DIR/rclone-env"
                map() { case "$1" in afkbackup:*) echo "$STUB_DIR/bucket/${1#afkbackup:}" ;; *) echo "$1" ;; esac; }
                case "$1" in
                  copyto)
                    [ -e "$STUB_DIR/fail-upload" ] && case "$3" in afkbackup:*) exit 5 ;; esac
                    dst=$(map "$3"); mkdir -p "$(dirname "$dst")"; cp "$(map "$2")" "$dst" ;;
                  lsf) d=$(map "$3"); [ -d "$d" ] && ls -1 "$d"; exit 0 ;;
                  lsl) d=$(map "$2"); [ -d "$d" ] && ls -l "$d"; exit 0 ;;
                  deletefile) rm "$(map "$2")" ;;
                  *) exit 9 ;;
                esac
                """);
        stub("curl", "for a in \"$@\"; do case \"$a\" in http*) echo \"curl $a\" >> \"$STUB_CALLS\" ;; esac; done");
    }

    private void stub(String name, String body) throws IOException {
        Path p = bin.resolve(name);
        Files.writeString(p, "#!/bin/bash\n" + body + "\n");
        Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    private void flag(String name) throws IOException {
        Files.writeString(dir.resolve(name), "x");
    }

    private Path daily(String name) {
        return bucket.resolve("afklive-backups/db/daily/" + name);
    }

    private Path weekly(String name) {
        return bucket.resolve("afklive-backups/db/weekly/" + name);
    }

    private void existing(Path p) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, "PGDMP old");
    }

    private List<String> names(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(folder)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    private record Result(int exit, String output, List<String> calls) {
        boolean called(String prefix) {
            return calls.stream().anyMatch(c -> c.startsWith(prefix));
        }
    }

    private Map<String, String> baseEnv() {
        Map<String, String> env = new HashMap<>();
        env.put("POSTGRES_HOST", "127.0.0.1");
        env.put("POSTGRES_PORT", "5432");
        env.put("POSTGRES_DB", "afklive");
        env.put("POSTGRES_USER", "afklive");
        env.put("POSTGRES_PASSWORD", DB_PASSWORD);
        env.put("BACKUP_S3_ENDPOINT", "https://0123456789abcdef.r2.cloudflarestorage.com");
        env.put("BACKUP_S3_BUCKET", "afklive-backups");
        env.put("BACKUP_S3_ACCESS_KEY", "ak-backup");
        env.put("BACKUP_S3_SECRET_KEY", SECRET);
        env.put("BACKUP_PING_URL", "https://hc-ping.example/uuid");
        env.put("BACKUP_TMPDIR", dir.resolve("tmp").toString());
        env.put("BACKUP_DATE", "2026-10-22"); // a Thursday
        return env;
    }

    private Result run(Path script, Map<String, String> overrides, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("/bin/bash", script.toString()));
        command.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.keySet().removeIf(k -> k.startsWith("POSTGRES_") || k.startsWith("BACKUP_") || k.startsWith("STORAGE_")
                || k.startsWith("DO_SPACES_") || k.startsWith("RCLONE_") || k.startsWith("PG"));
        env.put("PATH", bin + ":" + env.getOrDefault("PATH", "/usr/bin:/bin"));
        env.put("STUB_DIR", dir.toString());
        env.put("STUB_CALLS", calls.toString());
        env.putAll(baseEnv());
        overrides.forEach((k, v) -> {
            if (v == null) {
                env.remove(k);
            } else {
                env.put(k, v);
            }
        });
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes());
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        return new Result(p.exitValue(), out, Files.exists(calls) ? Files.readAllLines(calls) : List.of());
    }

    private Result backup(Map<String, String> overrides) throws Exception {
        return run(BACKUP, overrides);
    }

    // ------------------------------------------------------------------ backup

    @Test
    void dumpsChecksAndUploadsTonightsBackupThenPingsAndCleansUp() throws Exception {
        Result r = backup(Map.of());

        assertThat(r.exit()).as(r.output()).isZero();
        assertThat(Files.readString(daily("afklive-2026-10-22.dump"))).startsWith("PGDMP");
        assertThat(names(bucket.resolve("afklive-backups/db/weekly"))).isEmpty();
        assertThat(r.calls()).anyMatch(c -> c.startsWith("pg_dump") && c.contains("--format=custom")
                && c.contains("-d afklive") && c.endsWith("PGPASSWORD_SET=yes"));
        // The archive is read back before it is uploaded.
        assertThat(r.calls().indexOf(r.calls().stream().filter(c -> c.startsWith("pg_restore --list")).findFirst().orElseThrow()))
                .isLessThan(r.calls().indexOf(r.calls().stream().filter(c -> c.startsWith("rclone copyto")).findFirst().orElseThrow()));
        assertThat(r.calls()).contains("curl https://hc-ping.example/uuid");
        assertThat(names(dir.resolve("tmp"))).isEmpty();
        assertThat(Files.readString(dir.resolve("rclone-env")))
                .contains("RCLONE_CONFIG_AFKBACKUP_PROVIDER=Cloudflare", "RCLONE_CONFIG_AFKBACKUP_REGION=auto",
                        "RCLONE_CONFIG_AFKBACKUP_NO_CHECK_BUCKET=true", "RCLONE_CONFIG_AFKBACKUP_SECRET_ACCESS_KEY=" + SECRET);
    }

    @Test
    void secretsNeverReachTheOutputOrAnyCommandLine() throws Exception {
        Result ok = backup(Map.of());
        flag("fail-upload");
        Result failed = backup(Map.of());
        for (Result r : List.of(ok, failed)) {
            assertThat(r.output()).doesNotContain(DB_PASSWORD).doesNotContain(SECRET);
            assertThat(String.join("\n", r.calls())).doesNotContain(DB_PASSWORD).doesNotContain(SECRET);
        }
    }

    @Test
    void onSundayTheBackupIsAlsoKeptAsAWeekly() throws Exception {
        Result r = backup(Map.of("BACKUP_DATE", "2026-10-25"));
        assertThat(r.exit()).as(r.output()).isZero();
        assertThat(daily("afklive-2026-10-25.dump")).exists();
        assertThat(weekly("afklive-2026-10-25.dump")).exists();
    }

    @Test
    void keepsSevenDailyAndFourWeeklyAndNeverTouchesOtherFiles() throws Exception {
        for (int d = 10; d <= 24; d++) {
            existing(daily("afklive-2026-10-" + d + ".dump"));
        }
        existing(daily("notes.txt"));
        existing(daily("otherdb-2026-01-01.dump"));
        for (String w : List.of("2026-09-20", "2026-09-27", "2026-10-04", "2026-10-11", "2026-10-18")) {
            existing(weekly("afklive-" + w + ".dump"));
        }

        Result r = backup(Map.of("BACKUP_DATE", "2026-10-25"));

        assertThat(r.exit()).as(r.output()).isZero();
        assertThat(names(bucket.resolve("afklive-backups/db/daily"))).containsExactly(
                "afklive-2026-10-19.dump", "afklive-2026-10-20.dump", "afklive-2026-10-21.dump", "afklive-2026-10-22.dump",
                "afklive-2026-10-23.dump", "afklive-2026-10-24.dump", "afklive-2026-10-25.dump",
                "notes.txt", "otherdb-2026-01-01.dump");
        assertThat(names(bucket.resolve("afklive-backups/db/weekly"))).containsExactly(
                "afklive-2026-10-04.dump", "afklive-2026-10-11.dump", "afklive-2026-10-18.dump", "afklive-2026-10-25.dump");
    }

    @Test
    void aFailedDumpExitsNonZeroUploadsNothingPrunesNothingAndPingsFail() throws Exception {
        for (int d = 1; d <= 9; d++) {
            existing(daily("afklive-2026-10-0" + d + ".dump"));
        }
        flag("fail-pg_dump");
        Result r = backup(Map.of());

        assertThat(r.exit()).isNotZero();
        assertThat(r.output()).contains("db-backup: FAILED: pg_dump failed");
        assertThat(r.called("rclone")).isFalse();
        assertThat(names(bucket.resolve("afklive-backups/db/daily"))).hasSize(9);
        assertThat(r.calls()).contains("curl https://hc-ping.example/uuid/fail");
        assertThat(names(dir.resolve("tmp"))).isEmpty();
    }

    @Test
    void aFailedUploadPrunesNothing() throws Exception {
        for (int d = 1; d <= 9; d++) {
            existing(daily("afklive-2026-10-0" + d + ".dump"));
        }
        flag("fail-upload");
        Result r = backup(Map.of());
        assertThat(r.exit()).isNotZero();
        assertThat(r.output()).contains("FAILED: upload failed");
        assertThat(r.called("rclone deletefile")).isFalse();
        assertThat(names(bucket.resolve("afklive-backups/db/daily"))).hasSize(9);
    }

    @Test
    void anEmptyOrUnreadableDumpIsNeverUploaded() throws Exception {
        flag("empty-dump");
        Result empty = backup(Map.of());
        assertThat(empty.exit()).isNotZero();
        assertThat(empty.output()).contains("FAILED: the dump is empty");

        Files.delete(dir.resolve("empty-dump"));
        flag("corrupt-dump");
        Result corrupt = backup(Map.of());
        assertThat(corrupt.exit()).isNotZero();
        assertThat(corrupt.output()).contains("FAILED: pg_restore can't read the dump");
        assertThat(corrupt.called("rclone")).isFalse();
    }

    @Test
    void missingSettingsFailWithTheirName() throws Exception {
        Map<String, String> noBucket = new HashMap<>();
        noBucket.put("BACKUP_S3_BUCKET", null);
        Result r = backup(noBucket);
        assertThat(r.exit()).isNotZero();
        assertThat(r.output()).contains("BACKUP_S3_BUCKET is not set");

        Map<String, String> noPassword = new HashMap<>();
        noPassword.put("POSTGRES_PASSWORD", null);
        assertThat(backup(noPassword).output()).contains("POSTGRES_PASSWORD is not set");
    }

    @Test
    void storageAndOldSpacesNamesWorkAsFallbacks() throws Exception {
        Map<String, String> env = new HashMap<>();
        for (String k : List.of("BACKUP_S3_ENDPOINT", "BACKUP_S3_BUCKET", "BACKUP_S3_ACCESS_KEY", "BACKUP_S3_SECRET_KEY")) {
            env.put(k, null);
        }
        env.put("DO_SPACES_ENDPOINT", "https://blr1.digitaloceanspaces.com");
        env.put("DO_SPACES_BUCKET", "afklive-backups");
        env.put("DO_SPACES_KEY", "ak");
        env.put("DO_SPACES_SECRET", SECRET);
        Result r = backup(env);
        assertThat(r.exit()).as(r.output()).isZero();
        assertThat(daily("afklive-2026-10-22.dump")).exists();
        assertThat(Files.readString(dir.resolve("rclone-env"))).contains("RCLONE_CONFIG_AFKBACKUP_PROVIDER=Other");
    }

    @Test
    void theOldScriptNameStillMakesABackup() throws Exception {
        Result r = run(LEGACY, Map.of());
        assertThat(r.exit()).as(r.output()).isZero();
        assertThat(r.output()).contains("deprecated");
        assertThat(daily("afklive-2026-10-22.dump")).exists();
    }

    // ------------------------------------------------------------------ restore

    @Test
    void restoreRefusesTheLiveDatabaseWithoutTheFlag() throws Exception {
        existing(daily("afklive-2026-10-21.dump"));
        Result r = run(RESTORE, Map.of(), "latest", "afklive");
        assertThat(r.exit()).isNotZero();
        assertThat(r.output()).contains("is the live database");
        assertThat(r.called("pg_restore -h")).isFalse();
    }

    @Test
    void restoreOfLatestPutsTheNewestDailyIntoAnEmptyScratchDatabase() throws Exception {
        existing(daily("afklive-2026-10-20.dump"));
        existing(daily("afklive-2026-10-21.dump"));
        Result r = run(RESTORE, Map.of(), "latest", "afklive_drill");
        assertThat(r.exit()).as(r.output()).isZero();
        assertThat(r.calls()).anyMatch(c -> c.startsWith("rclone copyto") && c.contains("daily/afklive-2026-10-21.dump"));
        assertThat(r.calls()).anyMatch(c -> c.startsWith("pg_restore -h") && c.contains("-d afklive_drill") && c.contains("--no-owner"));
        assertThat(r.output()).contains("restored:").doesNotContain(DB_PASSWORD).doesNotContain(SECRET);
    }

    @Test
    void restoreFindsAWeeklyByNameAndRefusesANonEmptyTarget() throws Exception {
        existing(weekly("afklive-2026-10-18.dump"));
        Result ok = run(RESTORE, Map.of(), "afklive-2026-10-18.dump", "afklive_drill");
        assertThat(ok.exit()).as(ok.output()).isZero();
        assertThat(ok.calls()).anyMatch(c -> c.startsWith("rclone copyto") && c.contains("weekly/afklive-2026-10-18.dump"));

        Files.writeString(dir.resolve("tables-afklive_full"), "42\n");
        Result full = run(RESTORE, Map.of(), "afklive-2026-10-18.dump", "afklive_full");
        assertThat(full.exit()).isNotZero();
        assertThat(full.output()).contains("is not empty");
    }

    // ------------------------------------------------------------------ units and lint

    @Test
    void theUnitAndTimerRunTheScriptNightlyWithTheAppsEnvironment() throws Exception {
        String unit = Files.readString(SERVICE);
        assertThat(unit).contains("Type=oneshot", "ExecStart=/opt/afklive/bin/db-backup.sh",
                "EnvironmentFile=/opt/afklive/afklive.env", "EnvironmentFile=/opt/afklive/backup.env", "ReadWritePaths=/var/tmp");
        String timer = Files.readString(TIMER);
        assertThat(timer).contains("OnCalendar=", "Persistent=true", "WantedBy=timers.target");
    }

    @Test
    void shellcheckFindsNothing() throws Exception {
        Process which = new ProcessBuilder("/bin/sh", "-c", "command -v shellcheck").start();
        assumeTrue(which.waitFor() == 0, "shellcheck not installed");
        Process p = new ProcessBuilder("shellcheck", BACKUP.toString(), RESTORE.toString(), LEGACY.toString())
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        assertThat(p.waitFor()).as(out).isZero();
    }
}
