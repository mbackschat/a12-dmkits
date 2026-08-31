package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * Runs {@code scripts/selftest-test-evidence.sh}, so the evidence reporter's own controls execute in the build
 * rather than only when someone remembers them.
 *
 * <p><b>Why this class exists.</b> {@code test-evidence.sh} is the tool a session reaches for when a build
 * result is about to be used as evidence, which makes a defect in it strictly worse than not having it: the
 * output is formatted as a check, so it is believed. Two silent defects have shipped — a
 * first version whose cutoff marked every file stale, and a cutoff read from the log's birth time, which
 * {@code >} does not reset when it truncates an existing path. The second credited a six-second run with 13
 * result directories and 2016 tests from earlier runs on 2026-08-31. A third regression made the reporter
 * abort under stock macOS Bash, so this wiring test deliberately selects {@code /bin/bash} rather than the
 * newer Bash that may appear first on {@code PATH}.
 *
 * <p><b>What is asserted, and what deliberately is not.</b> This is a <i>wiring</i> test: it executes the
 * selftest and requires every case to pass. The cases live in the script, next to the behaviour they check,
 * and the count below is only a vacuity floor — a selftest that stopped running its cases would otherwise
 * exit 0 and read as green, which is the same failure mode as the tool it guards.
 */
class TestEvidenceScriptTest {

    /** Vacuity floor: the script carried 10 cases on 2026-08-31, over the does-count and does-NOT-count halves. */
    private static final int MIN_CASES = 8;

    @Test
    void everyEvidenceReporterSelftestCasePasses() throws Exception {
        Path root = repositoryRoot();
        var builder = new ProcessBuilder("/bin/bash", root.resolve("scripts/selftest-test-evidence.sh").toString());
        builder.directory(root.toFile());
        builder.redirectErrorStream(true);

        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();

        long cases = output.lines().filter(l -> l.startsWith("  ok    ") || l.startsWith("  FAIL  ")).count();
        assertTrue(cases >= MIN_CASES, () -> "the selftest reported only " + cases + " case results (floor "
                + MIN_CASES + "), so it exited without running its cases and its exit code proves nothing:\n"
                + output);

        assertEquals(0, exitCode, () -> "scripts/selftest-test-evidence.sh failed. That script is what stops a "
                + "build verdict being believed on the strength of result files an earlier run wrote:\n" + output);
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
