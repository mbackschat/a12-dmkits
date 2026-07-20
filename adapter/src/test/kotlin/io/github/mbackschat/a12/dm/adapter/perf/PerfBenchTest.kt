package io.github.mbackschat.a12.dm.adapter.perf

import io.github.mbackschat.a12.dm.adapter.perf.PerfBounds.ABS_CEILING_MS
import io.github.mbackschat.a12.dm.adapter.perf.PerfBounds.CAVEAT
import io.github.mbackschat.a12.dm.adapter.perf.PerfBounds.RATIO_CEILING
import io.github.mbackschat.a12.dm.interpreter.model.ModelTimeZone
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * The **kernel-ratio benchmark tier** (`scripts/perf.sh bench`) — the against-kernel scenario sweeps split out
 * of [PerfGateTest] (2026-07-10) so the routine gate stays fast: each scenario here pays kernel Groovy codegen +
 * baseline runs, which dominated the old single-tier gate's wall clock. Its sweep is **derived from
 * [PerfInventory]** (IG77 — the closed, versioned default B-scenario inventory), so a fair-Java-capable scenario
 * is benchmarked against generated Java *iff* its exhaustive inventory disposition includes it. Run this tier at
 * perf-relevant checkpoints (a hot-path change, a release); the routine landing gate is [PerfGateTest]. Same
 * correctness-first discipline ([PerfHarness.runScenario] preflights + asserts interp == Groovy == generated Java
 * before timing) and the same [PerfBounds] trips. Set `perf.scenario` to one exact inventory id for a targeted
 * drill; an unmatched id fails. The fairness caveat: [PerfBounds.CAVEAT].
 */
@Tag("perf")
class PerfBenchTest {

    @Test
    fun gatesEveryInventoryScenario() {
        val selectedCases = PerfInventory.select(System.getProperty("perf.scenario")?.takeIf(String::isNotBlank))

        // Stream each scenario's result AS it completes (live to build/perf-progress.log) — Gradle otherwise buffers
        // all test stdout until the task ends, so a slow multi-scenario sweep looks frozen.
        val rows = selectedCases.associateWith { case ->
            val scenario = case.scenario()
            val identity = scenario.identity
            PerfHarness.step("gate ${identity.id.id}@${identity.id.version} fingerprint=${identity.fingerprint} @N=${case.n}") {
                PerfHarness.runScenario(scenario, case.n, withJava = case.id.fairJavaCapable)
            }.also { PerfHarness.progress(PerfHarness.format(it, CAVEAT)) }
        }

        rows.values.forEach { row ->
            // (a) absolute backstop: a catastrophic O(N²) blows far past this generous ceiling for the gate N.
            assertTrue(row.interp.medianMs < ABS_CEILING_MS,
                "${row.scenario}: interpreter ${row.interp.medianMs}ms exceeds ${ABS_CEILING_MS}ms ceiling at N=${row.n}")
            // (b) relative backstop vs the kernel (Groovy): loose by design (Groovy is the slow baseline) — the
            // scaling-shape test is the precise O(N²) detector; this only trips on a large relative regression.
            val ratio = row.ratioGroovy
            if (ratio != null) assertTrue(ratio < RATIO_CEILING,
                "${row.scenario}: interp/kernel ratio $ratio exceeds $RATIO_CEILING (relative regression) at N=${row.n}")
        }

        // Diagnostic (IF132): the Europe/Berlin DST offset overhead over the UTC control, read off the swept rows.
        val berlin = rows.entries.firstOrNull { it.key.id.zone == ModelTimeZone.EUROPE_BERLIN }?.value
        val utc = rows.entries.firstOrNull { it.key.id.id == "B9-utc-control" }?.value
        if (berlin != null && utc != null)
            println("[perf] Berlin/UTC offset overhead: %.2f×".format(berlin.interp.medianMs / utc.interp.medianMs))
    }

    /**
     * **Repeated-eval over slightly-different documents** (form-input / batch) — the reuse pattern the kernel's
     * codegen + per-run caches are tuned for. A different MODE from [gatesEveryInventoryScenario]'s single-document
     * sweep (the engine is built ONCE and M variant instances are looped), so it is kept explicit rather than
     * inventory-derived; the printed PER-EVAL ratio, read against the single-document ratio, says whether the
     * interpreter's per-call tree-walk costs it under reuse. Correctness on the first + last (differing) variant is
     * asserted inside [PerfHarness.runRepeated]; the gate bound is generous (this mode is primarily diagnostic).
     */
    @Test
    fun repeatedEvalOverVaryingDocuments() {
        val rows = listOf(
            PerfHarness.runRepeated(PerfCatalog.b1(), n = 1_500, variants = 20, withJava = false),
            PerfHarness.runRepeated(PerfCatalog.b3(), n = 300, variants = 20, withJava = false),   // × 5 subs = 1500 Sub rows
        )
        rows.forEach { println(PerfHarness.formatRepeated(it, CAVEAT)) }
        rows.forEach { row ->
            assertTrue(row.interp.medianMs < ABS_CEILING_MS,
                "${row.scenario}: repeated per-eval ${row.interp.medianMs}ms exceeds ${ABS_CEILING_MS}ms at N=${row.n}")
        }
    }
}
