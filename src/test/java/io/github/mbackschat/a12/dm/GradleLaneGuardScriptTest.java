package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Runs {@code scripts/selftest-gradle-lane-guard.sh}, so check (3) of the shared {@code PreToolUse} hook is
 * exercised by the build rather than only when someone remembers to.
 *
 * <p><b>Why the pairing exists.</b> Every other {@code selftest-*.sh} in this repository has a driver like
 * this one, and the one that did not was this one, for about an hour on 2026-09-07 — long enough for the
 * maintainer to ask whether the tests were "skipped by ignorance", which they would have been: CI runs
 * {@code scripts/typescript/*.test.ts} by wildcard, so the typed half was covered, and nothing at all ran the
 * end-to-end half.
 *
 * <p><b>What is asserted, and why the floor is not decoration.</b> This is a <i>wiring</i> test: the
 * behavioural claims live in the script beside the shapes they cover. The count below is a vacuity floor,
 * because a selftest that stopped running its cases would exit 0 and read as green — which is precisely the
 * failure mode this pairing refuses. The floor sits well under the live count on purpose; it answers "did the
 * cases run", not "how many are there", so growth never forces an edit here.
 *
 * <p><b>Node is a hard requirement, deliberately not an assumption.</b> Skipping when Node is absent would
 * reintroduce the silent-green problem this class exists to close, and Node is already a declared build
 * prerequisite ({@code NodeRuntimePinGuardTest}, and CI's own TypeScript lane). So a missing runtime FAILS
 * here, and the failure message names it rather than leaving a reader to decode thirty passing fail-open
 * cases followed by a block of behavioural ones that could not fire.
 */
class GradleLaneGuardScriptTest {

    private static final Checkout CHECKOUT = Checkout.locate();

    /** Vacuity floor: the script carried 32 cases on 2026-09-07, across four labelled blocks. */
    private static final int MIN_CHECKS = 20;

    @Test
    void everyGradleLaneGuardSelftestCasePasses() throws Exception {
        var builder = CHECKOUT.command(List.of("/bin/sh",
                CHECKOUT.resolve("scripts/selftest-gradle-lane-guard.sh").toString()));
        builder.redirectErrorStream(true);

        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();

        long cases = output.lines().filter(l -> l.startsWith("  ok    ") || l.startsWith("  FAIL  ")).count();
        assertTrue(cases >= MIN_CHECKS, () -> "the selftest reported only " + cases + " case results (floor "
                + MIN_CHECKS + "), so it exited without running its cases and its exit code proves nothing:\n"
                + output);

        assertEquals(0, exitCode, () -> "scripts/selftest-gradle-lane-guard.sh failed. This guard sits on a "
                + "PreToolUse hook, so its two directions have asymmetric costs: a check that stops FIRING "
                + "lets back the repeat-lane waste it was built for, while one that wrongly BLOCKS makes the "
                + "tool unusable and gets switched off — which is why scripts/lane-guard.sh is still unwired. "
                + "If the fail-open cases pass and the behavioural ones do not, suspect a missing Node "
                + "runtime first: the guard is designed to allow everything when it cannot run.\n" + output);
    }
}
