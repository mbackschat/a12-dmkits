package io.github.mbackschat.a12.dm.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Checkout;

/**
 * Executes {@code scripts/typescript/gate-mutant-sweep.ts}, which derives the duplicate gate's decision sites
 * from source and refuses a denominator that moved.
 *
 * <p><b>Why a derived denominator, and why it is a build lane rather than a command.</b> The laws tier proves
 * that a check we wrote AGREES with the kernel; nothing in it can say whether a decision exists that no test is
 * asking about. That gap shipped twice in one slice, both times invisibly: one gate call ran over a list its own
 * constructor had just forced empty, so it could not fire and a call-site reading still reported the carrier as
 * guarded; and a refactor moved three decisions onto a new type while their controls stayed behind, so emptying
 * any of the three passed the whole lane. Neither was a wrong assertion — both were sites nobody had named, so
 * a hand-maintained list was the one instrument that could not report them.
 *
 * <p><b>What this asserts, and what it does not.</b> The sweep enumerates the sites, pins their count per kind,
 * derives each one's narrowest control class, and fails when a site has no control at all. It does NOT run the
 * mutants — that is the slow half, and the sweep prints the per-site {@code scripts/mutant.sh} command for it.
 * So a green run here means every decision is named and has something asking about it, never that every mutant
 * dies. The three sites whose mutation is expected to survive carry the gate that pre-empts them, measured.
 */
class DuplicateGateSiteDenominatorGuardTest {

    private static final Checkout CHECKOUT = Checkout.locate();

    /**
     * Vacuity floor. The sweep's own value is the count it prints, so a run that enumerated nothing and exited 0
     * would read as green — the false-clean-sweep shape a zero-hit search has here every time.
     */
    private static final int MIN_SITES = 20;

    @Test
    void everyDuplicateGateDecisionSiteIsNamedAndControlled() throws Exception {
        var builder = CHECKOUT.command(List.of(
                CHECKOUT.resolve("scripts/run-typescript.sh").toString(),
                "duplicate-gate site sweep",
                "scripts/typescript/gate-mutant-sweep.ts"));
        builder.redirectErrorStream(true);

        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();

        assertEquals(0, exitCode, () -> "the duplicate gate's site denominator moved, or a site has no control. "
                + "Name the new site's expected mutation outcome, then update the pin:\n" + output);

        long sites = output.lines().filter(line -> line.contains("expected: killed") || line.contains("survives"))
                .count();
        assertTrue(sites >= MIN_SITES, () -> "the sweep reported only " + sites + " sites (floor " + MIN_SITES
                + "), so its enumeration lost the tree and its exit code proves nothing:\n" + output);
    }

}
