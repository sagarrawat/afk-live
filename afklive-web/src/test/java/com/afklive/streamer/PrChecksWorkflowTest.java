package com.afklive.streamer;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * CI minutes: which PR checks a change runs (.github/scripts/changed-areas.sh), and that the workflow
 * keeps one always-run check to require in branch protection and cancels runs for older commits.
 */
class PrChecksWorkflowTest {

    private static final Path SCRIPT = locate(".github/scripts/changed-areas.sh");
    private static final Path WORKFLOW = locate(".github/workflows/pr-checks.yml");

    private static Path locate(String relative) {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve(relative))) {
            p = p.getParent();
        }
        return p == null ? Path.of(relative) : p.resolve(relative);
    }

    private String areas(String... changed) throws Exception {
        assumeTrue(Files.exists(Path.of("/bin/bash")) && Files.exists(SCRIPT), "bash and the script are needed");
        Process p = new ProcessBuilder("/bin/bash", SCRIPT.toString()).redirectErrorStream(true).start();
        p.getOutputStream().write(String.join("\n", changed).getBytes());
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes()).trim();
        assertThat(p.waitFor(10, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).as(out).isZero();
        return out.replace("\n", " ");
    }

    @Test
    void docsOnlyRunsNothing() throws Exception {
        assertThat(areas("docs/operations.md", "README.md", "afklive-scripts/README.md", "docs/research/notes.txt"))
                .isEqualTo("java=false ui=false");
        assertThat(areas()).isEqualTo("java=false ui=false");
    }

    @Test
    void appOrSiteOnlyRunsJustTheUiTests() throws Exception {
        assertThat(areas("afklive-ui/src/pages/Library.tsx", "afklive-ui/site/index.html", "docs/brand.md"))
                .isEqualTo("java=false ui=true");
    }

    @Test
    void anythingElseRunsTheFullBuild() throws Exception {
        assertThat(areas("afklive-web/src/main/java/com/afklive/streamer/service/MediaService.java")).isEqualTo("java=true ui=false");
        assertThat(areas("afklive-ui/src/App.tsx", "pom.xml")).isEqualTo("java=true ui=false");
        assertThat(areas("afklive-scripts/db-backup.sh")).isEqualTo("java=true ui=false");
        assertThat(areas(".github/workflows/pr-checks.yml")).isEqualTo("java=true ui=false");
        assertThat(areas("afklive-optimizer/pom.xml")).isEqualTo("java=true ui=false");
        assertThat(areas("some-new-folder/thing.txt")).isEqualTo("java=true ui=false");
    }

    @Test
    void theWorkflowHasOneAlwaysRunCheckAndCancelsOldRuns() throws Exception {
        String wf = Files.readString(WORKFLOW);
        assertThat(wf).contains("cancel-in-progress: true", "group: pr-checks-${{ github.event.pull_request.number || github.ref }}");
        assertThat(wf).contains("name: PR checks passed", "needs: [changes, build-and-test, ui-tests]", "if: always()");
        assertThat(wf).contains("if: needs.changes.outputs.java == 'true'", "if: needs.changes.outputs.ui == 'true'");
        assertThat(wf).contains(".github/scripts/changed-areas.sh", "run: mvn -B verify");
        // The workflow is not filtered by path at the trigger: a skipped workflow never reports the required check.
        assertThat(wf).doesNotContain("paths-ignore").doesNotContain("    paths:");
    }

    @Test
    void shellcheckFindsNothing() throws Exception {
        Process which = new ProcessBuilder("/bin/sh", "-c", "command -v shellcheck").start();
        assumeTrue(which.waitFor() == 0, "shellcheck not installed");
        Process p = new ProcessBuilder("shellcheck", SCRIPT.toString()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        assertThat(p.waitFor()).as(out).isZero();
    }
}
