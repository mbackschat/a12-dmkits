package io.github.mbackschat.a12.dm.adapter.perf

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The **against-kernel performance gate** (INTERPRETER-PERFORMANCE.md). Sweeps the [PerfCatalog] shapes at a
 * CI-friendly scale; each scenario asserts **correctness first** (interp == kernel fired-code multiset, via
 * [PerfHarness.runScenario]) then times both engines, prints the `interp × kernel × ratio` row, and trips two
 * generous backstops. The precise O(N²) detector is the separate [scalingIsSubQuadratic] shape check — a
 * single-scale absolute bound can't tell linear from quadratic, but a growth-factor across scales can.
 *
 * **Code generation is NOT measured** (see [PerfHarness] — the kernel generates code once ahead of validation,
 * cached; warmup primes it out, so the timed region is steady-state validation against already-generated code).
 *
 * **Fairness caveat — read the ratio with care.** Still NOT fully apples-to-apples: the interpreter matches
 * the kernel on requiredness, formal-checking, and rich messages (the catalog fills the index fields for
 * parity), but the kernel's `validateFull` still validates dimensions the interpreter does not yet model
 * (uniqueness/`RepetitionNotUnique`, enum/date-range value checks). So "interp faster" partly means "interp
 * does less" — the honest read is **competitive-to-faster on these scenarios**, not "Nx faster" as a speed
 * verdict. The bounds are GATES (catch a regression, tolerate CI variance), not SLAs. Pure engine, no CLI.
 */
class PerfGateTest {

    /** (scenario, gate N). Nested scenarios use a smaller Line count (× [PerfCatalog.SUBS_PER_LINE] = sub rows). */
    private val gate = listOf(
        PerfCatalog.b1() to 3_000,
        PerfCatalog.b2() to 3_000,
        PerfCatalog.b3() to 600,   // × 5 subs = 3000 Sub rows
        PerfCatalog.b4() to 600,   // × 5 subs = 3000 Sub rows
    )

    @Test
    fun gatesEveryCatalogShape() {
        val rows = gate.map { (scenario, n) -> PerfHarness.runScenario(scenario, n) }
        rows.forEach { println(PerfHarness.format(it, CAVEAT)) }

        rows.forEach { row ->
            // (a) absolute backstop: a catastrophic O(N²) blows far past this generous ceiling for the gate N.
            assertTrue(row.interp.medianMs < ABS_CEILING_MS,
                "${row.scenario}: interpreter ${row.interp.medianMs}ms exceeds ${ABS_CEILING_MS}ms ceiling at N=${row.n}")
            // (b) relative backstop vs the kernel: trips only on a large relative regression (the kernel is the
            // slow one, so this is loose by design — the scaling-shape test is the precise O(N²) detector).
            val ratio = row.ratio
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
            PerfHarness.runRepeated(PerfCatalog.b1(), n = 1_500, variants = 20),
            PerfHarness.runRepeated(PerfCatalog.b3(), n = 300, variants = 20),   // × 5 subs = 1500 Sub rows
        )
        rows.forEach { println(PerfHarness.formatRepeated(it, CAVEAT)) }
        rows.forEach { row ->
            assertTrue(row.interp.medianMs < ABS_CEILING_MS,
                "${row.scenario}: repeated per-eval ${row.interp.medianMs}ms exceeds ${ABS_CEILING_MS}ms at N=${row.n}")
        }
    }

    /**
     * The precise O(N²) detector: time the interpreter (only — no kernel, so high N stays cheap) on B1 at N and
     * 4N; a linear engine grows ~4×, a quadratic one ~16×. Assert the growth factor stays well under quadratic.
     * This is what actually guards the #28 indexing from a reintroduced linear-scan regression.
     */
    @Test
    fun scalingIsSubQuadratic() {
        val b1 = PerfCatalog.b1()
        val small = PerfHarness.timeInterpreter(b1, N_SMALL, warmup = 5, iters = 7)
        val large = PerfHarness.timeInterpreter(b1, N_SMALL * 4, warmup = 5, iters = 7)
        val growth = large.medianMs / small.medianMs
        println("[perf] B1 sub-quadratic check: ${"%.1f".format(small.medianMs)}ms@$N_SMALL → " +
            "${"%.1f".format(large.medianMs)}ms@${N_SMALL * 4}  growth=${"%.2f".format(growth)}× (linear≈4, quadratic≈16)")
        assertTrue(growth < SUB_QUADRATIC_MAX,
            "B1 scaling looks super-linear: 4× the rows took ${growth}× the time (quadratic would be ~16×)")
    }

    private companion object {
        const val ABS_CEILING_MS = 5_000.0
        const val RATIO_CEILING = 5.0
        const val N_SMALL = 5_000
        const val SUB_QUADRATIC_MAX = 9.0   // linear ≈ 4; a quadratic 4×-rows blowup is ≈ 16 — 9 is the trip line
        // Codegen is NOT in the timed region (primed out by warmup — the kernel generates ahead). The residual
        // unfairness is only that the kernel validates MORE dimensions than the interpreter models yet.
        const val CAVEAT = "(NOT apples-to-apples: kernel also validates uniqueness/enum/date-range; codegen excluded)"
    }
}
