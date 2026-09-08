package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * Runs {@code scripts/selftest-lane-guard.sh}, so the lane guard's own controls execute in the build rather
 * than only when someone remembers them.
 *
 * <p><b>Why the guard exists, and why it is a script.</b> CLAUDE.md already carried "treat more testing as a
 * budget smell, not a safe default", naming the asymmetry that makes it fail: the broader run is never
 * criticised on thoroughness and its cost lands on the maintainer. On 2026-08-24 the session that QUOTED that
 * paragraph then ran {@code :cli:check} — the estate's heaviest lane, spawning packaged launchers four at a
 * time and driving load to 23 on 8 cores — against a diff touching only {@code scripts/}, whose cli-side
 * guards had gone green in an evidence table printed minutes earlier. A discipline that fails while being
 * quoted is not a discipline; {@code scripts/mutant.sh} became the rule for reviewer-side filtering for the
 * same reason, and {@code scripts/lane-guard.sh} is that move here.
 *
 * <p><b>What is asserted.</b> This is a <i>wiring</i> test: it executes the selftest and requires every check
 * to pass. The checks live in the script beside the mapping they cover, and the count below is a vacuity floor
 * — a selftest that stopped running its checks would otherwise exit 0 and read as green, which is the precise
 * failure this pairing refuses.
 */
class LaneGuardScriptTest {

    private static final Checkout CHECKOUT = Checkout.locate();

    /** Vacuity floor: the script carried 15 checks on 2026-08-24, split across refuse and do-not-refuse. */
    private static final int MIN_CHECKS = 10;

    @Test
    void everyLaneGuardSelftestCheckPasses() throws Exception {
        var builder = CHECKOUT.command(List.of("/bin/bash", CHECKOUT.resolve("scripts/selftest-lane-guard.sh").toString()));
        builder.redirectErrorStream(true);

        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();

        long checks = output.lines().filter(l -> l.startsWith("  ok   ") || l.startsWith("  FAIL ")).count();
        assertTrue(checks >= MIN_CHECKS, () -> "the selftest reported only " + checks + " check results (floor "
                + MIN_CHECKS + "), so it exited without running its checks and its exit code proves nothing:\n"
                + output);

        assertEquals(0, exitCode, () -> "scripts/selftest-lane-guard.sh failed. A guard that wrongly REFUSES a "
                + "justified lane is worse than none — it gets switched off — and one that stops refusing lets "
                + "back the run that cost an hour and 23 load average:\n" + output);
    }

}
