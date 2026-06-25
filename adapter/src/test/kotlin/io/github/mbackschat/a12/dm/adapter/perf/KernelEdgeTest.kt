package io.github.mbackschat.a12.dm.adapter.perf

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/**
 * **Where the kernel has the EDGE** (INTERPRETER-PERFORMANCE.md "where the kernel wins"). The kernel team's
 * pride is well-founded: its architecture is **compile-once codegen + per-run caches** (`IndexFieldCache`,
 * `ValidationCache`, `CalculationCache`, `GroupFillCache`, `RepetitionNotUniqueErrorCache`), so its advantage
 * shows in the **many-rules** and **batch (repeated-eval)** regimes — exactly where the interpreter's per-call
 * rule re-parse (the #31 "prepare once" cost) accumulates. This opt-in test HUNTS the crossover (interp/java
 * → 1 and beyond) rather than gating it; it is the proof-of-kernel-edge measurement for the analysis log.
 *
 * ```
 * ./build.sh :adapter:test --tests "*KernelEdgeTest" -Dperf.edge=true [-Dperf.edge.n=1000]
 * ```
 * All comparisons use **interp/kernel-Java** (the Groovy-tax-free fair ratio). Correctness (interp == groovy
 * == java) is asserted inside the harness before any timing.
 */
@EnabledIfSystemProperty(named = "perf.edge", matches = "true")
class KernelEdgeTest {

    private val n: Int get() = System.getProperty("perf.edge.n", "1000").toInt()

    /**
     * Rule-count sweep at fixed document size: as the rule count grows, the interpreter re-parses ALL rules on
     * every `validateFull` call while the kernel runs already-generated code — so interp/kernel-Java climbs
     * toward (and past) 1.0. The crossover is the kernel's compile-once edge, made visible.
     */
    @Test
    fun ruleCountCrossover() {
        println("[edge] rule-count sweep @ N=$n Lines (×5 Subs), single document (interp/java is the fair ratio):")
        for (count in listOf(6, 12, 24, 48, 96, 192)) {
            val row = PerfHarness.runScenario(PerfCatalog.manyRules(count), n)
            println("  rules=%3d  %s".format(count, PerfHarness.format(row)))
        }
    }

    /**
     * The **low-firing** rule-count sweep — the realistic *valid-document* regime (most rules PASS, ~0 fires).
     * Here the per-row eval (firedType + polarity) is the dominant cost (no message-building to hide behind), so
     * this is the regime the kernel's two-phase short-circuit guard helps and the interpreter's single typed walk
     * does not (INTERPRETER-PERFORMANCE.md "How the kernel's generated code evaluates a rule"). The high-firing
     * [ruleCountCrossover] above hides that behind message-rendering; this is the twin that exposes it.
     */
    @Test
    fun ruleCountCrossoverLowFiring() {
        println("[edge] LOW-FIRING rule-count sweep @ N=$n Lines (×5 Subs), valid doc (mostly passes):")
        for (count in listOf(6, 12, 24, 48, 96, 192)) {
            val row = PerfHarness.runScenario(PerfCatalog.manyRules(count, firing = false), n)
            println("  rules=%3d  %s".format(count, PerfHarness.format(row)))
        }
    }

    /**
     * **Aggregate-heavy sweep** (the `Sum` fold's home). The fold — B2's star sum over Amount, B5's deep
     * nested-star sum over Cost across both repetition levels, and B7's per-Line `compute` of Sum-over-Subs —
     * is where decimal-addition cost concentrates. It is once-per-`validateFull` in the `manyRules` sweeps, so
     * THIS is the sweep that shows a fold optimization. interp/kernel-Java; correctness asserted before timing.
     */
    @Test
    fun aggregateSweep() {
        println("[edge] AGGREGATE sweep (Sum fold) @ interp/java:")
        for (n in listOf(1000, 3000)) {
            println("  B2 star-sum        " + PerfHarness.format(PerfHarness.runScenario(PerfCatalog.b2(), n)))
            println("  B5 deep-nested-sum " + PerfHarness.format(PerfHarness.runScenario(PerfCatalog.b5(), n / 5)))   // ×5 subs
            println("  B7 per-line compute" + PerfHarness.format(PerfHarness.runScenario(PerfCatalog.b7(), n / 5)))   // ×5 subs
        }
    }

    /**
     * **The semantic-index cliff — found, then closed.** A per-row rule resolves `[/Perf/Lines/Amount For
     * "NOMATCH"]`. WITHOUT a row index, the interpreter scanned the whole repetition list per lookup (O(rows))
     * × per-row iteration → **O(rows²)** — verified to go quadratic (growth ×3.5/doubling, the interp's lead
     * collapsing) while the kernel's `IndexFieldCache` kept it ~linear; the kernel decisively won at scale.
     * That was the proof that the kernel's runtime optimization is well-founded and *not* trivially beaten.
     *
     * The fix applied the same idea clean-room — `Document` now indexes rows by prefix (the `IndexFieldCache`
     * analog) — so the interpreter is **linear again** here (growth ~×2/doubling). This test now GUARDS that:
     * it prints the growth and asserts the interpreter stays **sub-quadratic** (a regression of the prefix
     * index would send it back over the cliff). Correctness (interp == kernel) is asserted inside the harness.
     */
    @Test
    fun semanticIndexCliff() {
        val sc = PerfCatalog.semanticIndexPerRow()
        println("[edge] semantic-index per-row — guarded sub-quadratic (was O(rows²) before the row-prefix index):")
        var prevInterp = 0.0
        var first = 0.0
        var last = 0.0
        for (n in listOf(500, 1000, 2000, 4000)) {
            val row = PerfHarness.runScenario(sc, n, withJava = false)
            val gi = if (prevInterp > 0) "  interp×%.1f".format(row.interp.medianMs / prevInterp) else ""
            println("  N=%5d  %s%s".format(n, PerfHarness.format(row), gi))
            if (first == 0.0) first = row.interp.medianMs
            last = row.interp.medianMs
            prevInterp = row.interp.medianMs
        }
        // 8× the rows: linear ≈ ×8 (+ fixed overhead), quadratic ≈ ×64. Trip well below quadratic.
        org.junit.jupiter.api.Assertions.assertTrue(last / first < 20.0,
            "semantic-index scaling looks super-linear: 8× rows took ${last / first}× the time (the row-prefix index regressed?)")
        println("  (8× rows ⇒ linear ~×8, quadratic ~×64; measured ×${"%.1f".format(last / first)})")
    }

    /**
     * **Batch validation** (the user's explicit ask): the engine built ONCE, M slightly-varying documents
     * looped — the form-input / batch pattern the kernel's codegen + per-run caches are tuned for. Per-eval
     * interp vs both kernel baselines, swept over rule count so the kernel's batch advantage is visible.
     */
    @Test
    fun manyRulesBatch() {
        println("[edge] BATCH (repeated-eval): engine built once, 20 varying docs looped, per-eval:")
        for (count in listOf(12, 48, 96)) {
            val row = PerfHarness.runRepeated(PerfCatalog.manyRules(count), n = n / 2, variants = 20)
            println("  rules=%3d  %s".format(count, PerfHarness.formatRepeated(row)))
        }
    }
}
