package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Runs {@code scripts/selftest-long-run-guard.sh}, so the shared {@code PreToolUse} hook is exercised by the
 * build rather than only when someone remembers to.
 *
 * <p><b>Why the pairing exists.</b> The hook's two checks shipped with no test at all, and writing the selftest
 * immediately found G80: the {@code while} loop gate — the canonical spelling of the very anti-pattern check
 * (2) is named for — had never matched, because the anchor list held only punctuation. A guard nothing executes
 * is a guard nobody can trust.
 *
 * <p><b>What is asserted, and why the floor is not decoration.</b> This is a <i>wiring</i> test: the
 * behavioural claims live in the script beside the shapes they cover. The count below is a vacuity floor,
 * because a selftest that stopped running its cases would exit 0 and read as green — which is precisely the
 * failure mode this pairing refuses. The floor sits well under the live count on purpose; it answers "did the
 * cases run", not "how many are there", so growth never forces an edit here.
 */
class LongRunGuardScriptTest {

    private static final Checkout CHECKOUT = Checkout.locate();

    /** Vacuity floor: the script carried 29 cases on 2026-09-07, across four labelled blocks. */
    private static final int MIN_CHECKS = 20;

    @Test
    void everyLongRunGuardSelftestCasePasses() throws Exception {
        var builder = CHECKOUT.command(List.of("/bin/sh",
                CHECKOUT.resolve("scripts/selftest-long-run-guard.sh").toString()));
        builder.redirectErrorStream(true);

        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();

        long cases = output.lines().filter(l -> l.startsWith("  ok    ") || l.startsWith("  FAIL  ")).count();
        assertTrue(cases >= MIN_CHECKS, () -> "the selftest reported only " + cases + " case results (floor "
                + MIN_CHECKS + "), so it exited without running its cases and its exit code proves nothing:\n"
                + output);

        assertEquals(0, exitCode, () -> "scripts/selftest-long-run-guard.sh failed. This guard sits on a "
                + "PreToolUse hook, so its two directions have asymmetric costs: a check that stops FIRING "
                + "lets back the orphaned-build and self-matching-poll waste it was built for, while one that "
                + "wrongly BLOCKS makes the tool unusable and gets switched off — which is what happened to "
                + "the lane guard, unwired the night it landed and deleted nineteen days later.\n" + output);
    }
}
