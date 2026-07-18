package io.github.mbackschat.a12.dm.adapter.perf

import io.github.mbackschat.a12.dm.adapter.perf.PerfBounds.ABS_CEILING_MS
import io.github.mbackschat.a12.dm.adapter.perf.PerfBounds.CAVEAT
import io.github.mbackschat.a12.dm.adapter.perf.PerfBounds.RATIO_CEILING
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * The **kernel-ratio benchmark tier** (`scripts/perf.sh bench`) — the against-kernel scenario sweeps split out
 * of [PerfGateTest] (2026-07-10) so the routine gate stays fast: each scenario here pays kernel Groovy
 * codegen + baseline runs, which dominated the old single-tier gate's wall clock. Run this tier at
 * perf-relevant checkpoints (a hot-path change, a release); the routine landing gate is [PerfGateTest]
 * (the sub-quadratic growth guards + the rich-model kernel canary). Same correctness-first discipline
 * ([PerfHarness.runScenario] asserts interp == kernel before timing) and the same [PerfBounds] trips.
 * The fairness caveat on every ratio: [PerfBounds.CAVEAT].
 */
@Tag("perf")
class PerfBenchTest {

    /** (scenario, gate N). Nested scenarios use a smaller Line count (× [PerfCatalog.SUBS_PER_LINE] = sub rows). */
    private val bench = listOf(
        PerfCatalog.b1() to 3_000,
        PerfCatalog.b2() to 3_000,
        PerfCatalog.b3() to 600,   // × 5 subs = 3000 Sub rows
        PerfCatalog.b4() to 600,   // × 5 subs = 3000 Sub rows
        PerfCatalog.b5() to 600,   // × 5 subs = 3000 Sub rows (deep nested-star)
        PerfCatalog.b6() to 600,   // × 5 subs = 3000 Sub rows, 12 rules (per-call re-parse stress)
    )

    @Test
    fun gatesEveryCatalogShape() {
        // Groovy-only for the routine gate (fast — no per-scenario Java codegen+compile). The Groovy tax (~1.0–1.3×)
        // is pinned by KernelEdgeTest + KernelJavaBaselineDiffTest; the fair Java ratio lives in the analysis log.
        // Stream each scenario's result AS it completes (live to build/perf-progress.log) — Gradle otherwise
        // buffers all test stdout until the task ends, so a slow multi-scenario gate looks frozen.
        val rows = bench.map { (scenario, n) ->
            PerfHarness.step("gate ${scenario.name} @N=$n") { PerfHarness.runScenario(scenario, n, withJava = false) }
                .also { PerfHarness.progress(PerfHarness.format(it, CAVEAT)) }
        }

        rows.forEach { row ->
            // (a) absolute backstop: a catastrophic O(N²) blows far past this generous ceiling for the gate N.
            assertTrue(row.interp.medianMs < ABS_CEILING_MS,
                "${row.scenario}: interpreter ${row.interp.medianMs}ms exceeds ${ABS_CEILING_MS}ms ceiling at N=${row.n}")
            // (b) relative backstop vs the kernel (Groovy): loose by design (Groovy is the slow baseline) — the
            // scaling-shape test is the precise O(N²) detector; this only trips on a large relative regression.
            val ratio = row.ratioGroovy
            if (ratio != null) assertTrue(ratio < RATIO_CEILING,
                "${row.scenario}: interp/kernel ratio $ratio exceeds $RATIO_CEILING (relative regression) at N=${row.n}")
        }
    }

    /**
     * **Repeated-eval over slightly-different documents** (form-input / batch) — the reuse pattern the kernel's
     * codegen + per-run caches are tuned for. The engine is built ONCE and M variant instances are looped; the
     * printed PER-EVAL ratio, read against [gatesEveryCatalogShape]'s single-document ratio, says whether the
     * interpreter's per-call rule re-parse (the #31 "prepare once" cost) costs it under reuse. Correctness on
     * the first + last (differing) variant is asserted inside [PerfHarness.runRepeated]; the gate bound is
     * generous (this mode is primarily diagnostic).
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

    /**
     * **Compute at scale (B7)**: the `compute()` path (not validate) — a per-Line nested-aggregate computation
     * `Amount := Sum(Subs* / Cost)`. Asserts the computed-field outcomes agree (interp == kernel) and the same
     * generous bounds. Confirms the compute path scales like validate.
     */
    @Test
    fun computeScalesLikeKernel() {
        val row = PerfHarness.runScenario(PerfCatalog.b7(), n = 600, withJava = false)   // × 5 subs = 3000 Sub operands
        println(PerfHarness.format(row, CAVEAT))
        assertTrue(row.interp.medianMs < ABS_CEILING_MS,
            "${row.scenario}: interpreter ${row.interp.medianMs}ms exceeds ${ABS_CEILING_MS}ms at N=${row.n}")
        val ratio = row.ratioGroovy
        if (ratio != null) assertTrue(ratio < RATIO_CEILING,
            "${row.scenario}: compute interp/kernel ratio $ratio exceeds $RATIO_CEILING at N=${row.n}")
    }

    /**
     * **Tiered compute at scale (IF21)**: the `compute()` path over a per-Line computation selected by a
     * `precondition` (`[Qty] < 100 → [Qty]` else `0`). Asserts the computed-field outcomes agree (interp ==
     * kernel — the kernel compiles the same tiered alternatives) at the gate N, the same generous bounds, and
     * prints the interp/kernel ratio. The twin of [computeScalesLikeKernel] for the tiered selection — the
     * measured-against-the-kernel half of the IF21 fix (the growth guard is
     * [PerfGateTest.tieredComputationStaysSubQuadratic]).
     */
    @Test
    fun tieredComputeScalesLikeKernel() {
        val row = PerfHarness.runScenario(PerfCatalog.tieredComputation(), n = 3_000, withJava = false)
        println(PerfHarness.format(row, CAVEAT))
        assertTrue(row.interp.medianMs < ABS_CEILING_MS,
            "${row.scenario}: interpreter ${row.interp.medianMs}ms exceeds ${ABS_CEILING_MS}ms at N=${row.n}")
        val ratio = row.ratioGroovy
        if (ratio != null) assertTrue(ratio < RATIO_CEILING,
            "${row.scenario}: tiered-compute interp/kernel ratio $ratio exceeds $RATIO_CEILING at N=${row.n}")
    }

    /**
     * **Parallel iteration at scale** — the index-keyed outer join over two sibling repeatable groups. Asserts
     * the join agrees with the kernel (interp == kernel) and stays competitive at the gate N. The kernel's
     * `IndexFieldCache` makes this O(rows); the interpreter mirrors it (a value→row map per group, O(1) row
     * lookups via the prefix index), so it must NOT regress to a nested-loop O(rows²).
     */
    @Test
    fun parallelIterationScalesLikeKernel() {
        val row = PerfHarness.runScenario(PerfCatalog.parallel(), n = 2_000, withJava = false)   // 2000 × 2 groups
        println(PerfHarness.format(row, CAVEAT))
        assertTrue(row.interp.medianMs < ABS_CEILING_MS,
            "${row.scenario}: interpreter ${row.interp.medianMs}ms exceeds ${ABS_CEILING_MS}ms at N=${row.n}")
        val ratio = row.ratioGroovy
        if (ratio != null) assertTrue(ratio < RATIO_CEILING,
            "${row.scenario}: parallel interp/kernel ratio $ratio exceeds $RATIO_CEILING at N=${row.n}")
    }

    /**
     * **Europe/Berlin DATE_TIME at scale (IG80)** — a per-row `DifferenceInHours` over `Europe/Berlin` datetime
     * fields, plus the UTC control. Asserts the datetime arithmetic agrees with the kernel (interp == kernel — the
     * mid-June values are non-straddling, so Berlin and UTC agree on the difference, while the `berlinOffsetSeconds`
     * civil-calendar path still runs per conversion) and records the interp/kernel ratio; the printed Berlin-vs-UTC
     * delta isolates the DST offset cost. Closes the datetime hole in the perf coverage and pins the IF128
     * optimization's win (the growth guard is [PerfGateTest.berlinDateTimeStaysSubQuadratic]).
     */
    @Test
    fun berlinDateTimeScalesLikeKernel() {
        val berlin = PerfHarness.runScenario(PerfCatalog.berlinDateTime(), n = 3_000, withJava = false)
        val utc = PerfHarness.runScenario(PerfCatalog.utcDateTimeControl(), n = 3_000, withJava = false)
        println(PerfHarness.format(berlin, CAVEAT))
        println(PerfHarness.format(utc, CAVEAT))
        println("[perf] Berlin/UTC offset overhead: %.2f×".format(berlin.interp.medianMs / utc.interp.medianMs))
        for (row in listOf(berlin, utc)) {
            assertTrue(row.interp.medianMs < ABS_CEILING_MS,
                "${row.scenario}: interpreter ${row.interp.medianMs}ms exceeds ${ABS_CEILING_MS}ms at N=${row.n}")
            val ratio = row.ratioGroovy
            if (ratio != null) assertTrue(ratio < RATIO_CEILING,
                "${row.scenario}: datetime interp/kernel ratio $ratio exceeds $RATIO_CEILING at N=${row.n}")
        }
    }
}
