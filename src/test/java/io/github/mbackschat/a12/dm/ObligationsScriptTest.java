package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * Runs {@code scripts/selftest-obligations.sh}, so the obligation trigger's own controls are executed by the build
 * rather than only by whoever remembers to run them.
 *
 * <p><b>Why this class exists.</b> The selftest was written with eight cases and wired to nothing. That is the
 * failure mode the estate's own rule names — an advertised invariant with no enforcement locus — and it is
 * sharper for a shell script than for Java, because a shell script has no compiler: a rename, a quoting slip, or
 * a {@code set -e} interaction breaks it silently and the next reader sees a tool that reports a clean tree. Both
 * defects the selftest has caught so far were of exactly that kind, and neither produced an error message.
 *
 * <p><b>What is asserted, and what deliberately is not.</b> This is a <i>wiring</i> test: it executes the selftest
 * and requires every case to pass. The cases themselves live in the script, where they are readable next to the
 * rules they check, and a case count is asserted below only as a vacuity floor — a selftest that silently stopped
 * running its cases would otherwise exit 0 and read as green.
 */
class ObligationsScriptTest {

    /**
     * Vacuity floor: the script carried 9 cases on 2026-08-16. Asserted rather than pinned exactly, so adding a
     * case does not fail this class, but a script that stops emitting results does.
     */
    private static final int MIN_CASES = 7;

    @Test
    void everyObligationSelftestCasePasses() throws Exception {
        Path root = repositoryRoot();
        var builder = new ProcessBuilder("bash", root.resolve("scripts/selftest-obligations.sh").toString());
        builder.directory(root.toFile());
        builder.redirectErrorStream(true);

        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();

        long cases = output.lines().filter(l -> l.startsWith("  ok    ") || l.startsWith("  FAIL  ")).count();
        assertTrue(cases >= MIN_CASES, () -> "the selftest reported only " + cases + " case results (floor "
                + MIN_CASES + "), so it exited without running its cases and its exit code proves nothing:\n"
                + output);

        assertEquals(0, exitCode, () -> "scripts/selftest-obligations.sh failed. The obligation trigger is what "
                + "tells a session which docs a change obliges it to re-read, so a broken one reports a clean "
                + "tree and reads as 'nothing to do':\n" + output);
    }

    private static Path repositoryRoot() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            if (Files.isRegularFile(directory.resolve("settings.gradle"))
                    && Files.isRegularFile(directory.resolve("build.sh"))) {
                return directory;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException("could not locate the repository root from " + Path.of("").toAbsolutePath());
    }
}
