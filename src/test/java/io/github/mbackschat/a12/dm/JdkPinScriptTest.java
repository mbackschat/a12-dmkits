package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * Runs {@code scripts/selftest-jdk-pin.sh}, so the build's OpenJDK-21 selection policy is executed by the build
 * rather than only by whoever remembers to run it.
 *
 * <p><b>Why this class exists.</b> {@code a12_pinned_jdk21_home} was {@code java_home -v 21}, which returns the
 * HIGHEST-versioned JDK 21 installed. Installing Homebrew's {@code openjdk@21} 21.0.12.1 therefore displaced
 * GraalVM 21.0.12 as the build JDK on 2026-08-19 with no commit in this repository, and moved 45 of 91 perf
 * lanes (median 1.15x, worst 1.60x) — which read at first as a code regression in the interpreter. The policy
 * that replaced it is a shell function, and a shell function has no compiler: a quoting slip or a {@code set -e}
 * interaction breaks the skip silently, and the next reader sees a build that picks a JDK nobody chose.
 *
 * <p><b>What is asserted, and what deliberately is not.</b> This is a <i>wiring</i> test: it executes the
 * selftest and requires every check to pass. The checks live in the script, next to the policy they cover, and
 * the count below is only a vacuity floor — a selftest that stopped running its checks would otherwise exit 0
 * and read as green, which is the exact failure mode this pairing exists to refuse.
 */
class JdkPinScriptTest {

    /**
     * Vacuity floor: the script carried 7 checks on 2026-08-23. Asserted rather than pinned exactly, so adding
     * a check does not fail this class, but a script that stops emitting results does.
     */
    private static final int MIN_CHECKS = 5;

    @Test
    void everyJdkPinSelftestCheckPasses() throws Exception {
        Path root = repositoryRoot();
        var builder = new ProcessBuilder("/bin/bash", root.resolve("scripts/selftest-jdk-pin.sh").toString());
        builder.directory(root.toFile());
        builder.redirectErrorStream(true);

        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();

        long checks = output.lines().filter(l -> l.startsWith("  ok   ") || l.startsWith("  FAIL ")).count();
        assertTrue(checks >= MIN_CHECKS, () -> "the selftest reported only " + checks + " check results (floor "
                + MIN_CHECKS + "), so it exited without running its checks and its exit code proves nothing:\n"
                + output);

        assertEquals(0, exitCode, () -> "scripts/selftest-jdk-pin.sh failed. That policy is what keeps an "
                + "unrelated JDK install from silently becoming the build JDK — the failure it replaced was "
                + "invisible until a perf lane breached and was read as a code regression:\n" + output);
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
