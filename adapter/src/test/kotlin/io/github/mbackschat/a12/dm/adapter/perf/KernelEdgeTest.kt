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

    /** The rule counts the crossover sweeps walk — overridable via `-Dperf.edge.counts=192,384,768,1000` to
     *  probe scaling beyond the default ceiling. */
    private val counts: List<Int>
        get() = System.getProperty("perf.edge.counts", "6,12,24,48,96,192").split(",").map { it.trim().toInt() }

    /**
     * Rule-count sweep at fixed document size: as the rule count grows, the interpreter re-parses ALL rules on
     * every `validateFull` call while the kernel runs already-generated code — so interp/kernel-Java climbs
     * toward (and past) 1.0. The crossover is the kernel's compile-once edge, made visible.
     */
    @Test
    fun ruleCountCrossover() {
        println("[edge] rule-count sweep @ N=$n Lines (×5 Subs), single document (interp/java is the fair ratio):")
        for (count in counts) {
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
        for (count in counts) {
            val row = PerfHarness.runScenario(PerfCatalog.manyRules(count, firing = false), n)
            println("  rules=%3d  %s".format(count, PerfHarness.format(row)))
        }
    }

    /**
     * **HUGE-DOC scaling (many rows).** The non-quadratic `Document` is designed for up to ~100k rows
     * (PROJECT goal); this sweep exercises that. It times the interpreter at growing row counts on a per-row
     * rule (B1) and a deep nested-star aggregate (B5) — interp-only at the extreme (no kernel instance, so 100k
     * is cheap) — and prints interp-vs-kernel at the largest row count the kernel comfortably builds. A flat
     * ms/row (B1) confirms the linear scan the [PerfGateTest] gates; here we *see* the absolute numbers at scale.
     */
    @Test
    fun hugeDocScaling() {
        val maxRows = System.getProperty("perf.edge.rows", "20000").toInt()
        println("[edge] HUGE-DOC interp scaling (per-row B1):")
        var prev = 0.0
        for (rows in listOf(1_000, 5_000, 10_000, maxRows)) {
            val s = PerfHarness.timeInterpreter(PerfCatalog.b1(), rows, warmup = 3, iters = 5)
            val growth = if (prev > 0) "  ×%.2f".format(s.medianMs / prev) else ""
            println("  B1 per-row     N=%6d  interp=%8.2fms  %.4f ms/row%s".format(rows, s.medianMs, s.medianMs / rows, growth))
            prev = s.medianMs
        }
        println("[edge] HUGE-DOC interp scaling (deep nested-star B5, ×5 subs/line):")
        for (rows in listOf(1_000, 2_000, maxRows / 5)) {
            val s = PerfHarness.timeInterpreter(PerfCatalog.b5(), rows, warmup = 3, iters = 5)
            println("  B5 nested-star N=%6d (=%d sub rows)  interp=%8.2fms".format(rows, rows * PerfCatalog.SUBS_PER_LINE, s.medianMs))
        }
        println("[edge] interp vs kernel-Java at a large doc (B1 per-row):")
        for (rows in listOf(10_000, maxRows)) {
            val row = PerfHarness.runScenario(PerfCatalog.b1(), rows)
            println("  B1 N=%6d  %s".format(rows, PerfHarness.format(row)))
        }
    }

    /**
     * **DISTRIBUTED rules — does spreading rules across fields lift the kernel's codegen ceiling?** The
     * [ruleCountCrossover] sweep concentrates rules on ~3 error fields (`manyRules`), forcing the kernel's
     * codegen into one method that blows the 64 KB limit at ~768. THIS sweep uses [PerfCatalog.manyRulesDistributed]
     * (one rule per distinct field) — the realistic "wide form" shape — so the kernel splits across many small
     * methods. It reports the interp/java ratio per count and, if codegen still fails, says so (caught, not
     * aborted). Counts via `-Dperf.edge.counts`; a modest row count (the model is WIDE, not tall).
     */
    @Test
    fun distributedRuleScaling() {
        val dn = 100   // wide model (count fields/row); rows kept modest — the field/rule count is the scale knob
        println("[edge] DISTRIBUTED rules (1 rule per field, realistic) @ N=$dn rows — kernel codegen + interp/java:")
        for (count in counts) {
            try {
                val row = PerfHarness.runScenario(PerfCatalog.manyRulesDistributed(count, firing = false), dn)
                println("  distributed rules=%4d  %s".format(count, PerfHarness.format(row)))
            } catch (e: Throwable) {
                val tooLarge = generateSequence(e as Throwable?) { it.cause }
                    .any { (it.message ?: "").contains("Method too large") }
                println("  distributed rules=%4d  KERNEL CODEGEN FAILED: %s".format(count,
                    if (tooLarge) "Method too large (JVM 64KB limit)" else (e.message ?: e::class.simpleName)?.take(90)))
            }
        }
    }

    /**
     * **Interpreter scaling PAST the kernel's structural ceiling.** At ~512–768 rules the kernel's Groovy
     * codegen generates a single `fuehreAus_0` validation method that exceeds the **JVM 64 KB method bytecode
     * limit** (`MethodTooLargeException`) — so the kernel **cannot build the validator at all** beyond that. The
     * interpreter is a tree-walk with no per-model codegen, so it has no such ceiling. This sweep times the
     * interpreter ONLY (no kernel) across high rule counts to show it stays **linear** where the kernel can't
     * run. Counts via `-Dperf.edge.counts=384,768,1000,1500,2000`; both firing regimes.
     */
    @Test
    fun interpScalingPastKernelCeiling() {
        println("[edge] INTERP-ONLY scaling @ N=$n (the kernel cannot codegen past ~512–768 rules — 64KB method limit):")
        for (firing in listOf(true, false)) {
            var prev = 0.0
            val regime = if (firing) "firing    " else "low-firing"
            for (count in counts) {
                val s = PerfHarness.timeInterpreter(PerfCatalog.manyRules(count, firing = firing), n, warmup = 8, iters = 8)
                val perRule = "%.3fms/rule".format(s.medianMs / count)
                val growth = if (prev > 0) "  ×%.2f".format(s.medianMs / prev) else ""
                println("  %s rules=%4d  interp=%7.2fms  %s%s".format(regime, count, s.medianMs, perRule, growth))
                prev = s.medianMs
            }
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
