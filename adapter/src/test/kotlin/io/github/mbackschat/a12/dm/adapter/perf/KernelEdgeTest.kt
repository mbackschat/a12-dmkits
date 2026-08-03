package io.github.mbackschat.a12.dm.adapter.perf

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/**
 * **Kernel-edge search** (INTERPRETER-PERFORMANCE.md "where the kernel wins"). The kernel's architecture has
 * strong compile-once code generation and specialized per-run caches (`IndexFieldCache`, `ValidationCache`,
 * `CalculationCache`, `GroupFillCache`, `RepetitionNotUniqueErrorCache`). This opt-in suite searches the shapes
 * where those mechanisms might outrun the interpreter, including many rules, repeated evaluation, deep/wide
 * models, and large documents. A search is allowed to find no crossover; the observed ratios, not this KDoc,
 * decide whether either strategy leads.
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
     * Rule-count sweep at fixed document size: the interpreter tree-walks cached rule ASTs while the kernel runs
     * already-generated code. The sweep measures whether and where that compile-once advantage produces a
     * crossover; it does not assume one.
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
     * **LATENT O(N²) hunt** — the constructs the easy benchmarks overshadowed (the `Having` cliff's siblings).
     * Times the interpreter at N and 4N; **linear ≈ ×4, quadratic ≈ ×16**. Suspects: a per-row rule referencing a
     * row-INVARIANT global aggregate (one scoped fold shared across rows), and `NumberOfDifferentValues` (sorted
     * adjacent-distinct). A ×16-ish growth means one of those repaired mechanisms regressed.
     */
    @Test
    fun latentQuadraticHunt() {
        println("[edge] LATENT O(N²) hunt — interp growth N→4N (linear≈4, quadratic≈16):")
        for ((name, sc) in listOf(
            "per-row-global-agg" to PerfCatalog.perRowGlobalAggregate(),
            "NumberOfDifferentValues" to PerfCatalog.numDifferentValues(),
            "FieldValuesNotUnique" to PerfCatalog.fieldValuesNotUniqueStar(),
        )) {
            val small = PerfHarness.timeInterpreter(sc, 2000, warmup = 5, iters = 6)
            val large = PerfHarness.timeInterpreter(sc, 8000, warmup = 5, iters = 6)
            println("  %s → %s %-24s %.1fms@2000 → %.1fms@8000  x%.2f".format(
                PerfHarness.measurementLabel(sc, 2000),
                PerfHarness.measurementLabel(sc, 8000),
                name, small.medianMs, large.medianMs, large.medianMs / small.medianMs,
            ))
        }
    }

    /** Fair tri-engine comparison for the row-invariant aggregate whose interpreter fold used to run per row. */
    @Test
    fun rowInvariantAggregateKernelComparison() {
        val scenario = PerfCatalog.perRowGlobalAggregate()
        println("[edge] ROW-INVARIANT aggregate — repaired interpreter vs both kernel strategies:")
        for (rows in listOf(500, 2_000)) {
            println("  N=%4d  %s".format(rows, PerfHarness.format(PerfHarness.runScenario(scenario, rows))))
        }
    }

    /**
     * **WIDE — many sibling groups (breadth axis).** A model with N sibling repeatable groups, each its own
     * index + field + per-row rule, at fixed rows/group. Stresses any per-group setup that scans the whole model
     * (would show as super-linear group-count growth); the interpreter's per-group machinery is memoized, so it
     * should stay linear. Also confirms the kernel codegen handles many groups (rules distributed 1-per-field).
     */
    @Test
    fun wideGroupsScaling() {
        println("[edge] WIDE (many sibling groups) @ 20 rows/group — interp/java + group-count growth:")
        var prev = 0.0
        for (g in listOf(25, 50, 100, 200)) {
            val row = PerfHarness.runScenario(PerfCatalog.wideGroups(g), 20)
            val growth = if (prev > 0) "  interp×%.2f".format(row.interp.medianMs / prev) else ""
            println("  groups=%4d  %s%s".format(g, PerfHarness.format(row), growth))
            prev = row.interp.medianMs
        }
    }

    /**
     * **DEEP + RICH rules** (4-level nesting + cross-level / `Having` / nested-star). The realistic "sensible
     * rules across a deep DM" shape: a per-level comparison, cross-level refs **1 and 3 parents up** (common-prefix
     * walk-up), a `Having`-filtered Sum, and a 3-level deep nested-star Sum — distributed across the levels.
     * Runs BOTH regimes (firing exercises the cross-level walk-up non-trivially → interp==kernel is a real
     * multi-construct correctness check, asserted in the harness) and scales on the Vendor count (×12 tiers each).
     */
    @Test
    fun deepRichScaling() {
        println("[edge] DEEP+RICH (4-level, cross-level/Having/nested-star) @ interp/java:")
        for (firing in listOf(false, true)) {
            for (vendors in listOf(100, 500, 1000)) {
                val row = PerfHarness.runScenario(PerfCatalog.deepRich(firing), vendors)
                println("  %-10s vendors=%4d (=%d tiers)  %s".format(
                    if (firing) "firing" else "low-firing", vendors, vendors * 12, PerfHarness.format(row)))
            }
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
        val b1 = PerfCatalog.b1()
        var prev = 0.0
        var prevRows: Int? = null
        for (rows in listOf(1_000, 5_000, 10_000, maxRows)) {
            val s = PerfHarness.timeInterpreter(b1, rows, warmup = 3, iters = 5)
            val growth = prevRows?.let {
                "  ×%.2f vs %s".format(s.medianMs / prev, PerfHarness.measurementLabel(b1, it))
            } ?: ""
            println("  %s B1 per-row     N=%6d  interp=%8.2fms  %.4f ms/row%s".format(
                PerfHarness.measurementLabel(b1, rows), rows, s.medianMs, s.medianMs / rows, growth))
            prev = s.medianMs
            prevRows = rows
        }
        println("[edge] HUGE-DOC interp scaling (deep nested-star B5, ×5 subs/line):")
        val b5 = PerfCatalog.b5()
        for (rows in listOf(1_000, 2_000, maxRows / 5)) {
            val s = PerfHarness.timeInterpreter(b5, rows, warmup = 3, iters = 5)
            println("  %s B5 nested-star N=%6d (=%d sub rows)  interp=%8.2fms".format(
                PerfHarness.measurementLabel(b5, rows),
                rows, rows * PerfCatalog.SUBS_PER_LINE, s.medianMs,
            ))
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
            var previousScenario: PerfScenario? = null
            val regime = if (firing) "firing    " else "low-firing"
            for (count in counts) {
                val scenario = PerfCatalog.manyRules(count, firing = firing)
                val s = PerfHarness.timeInterpreter(scenario, n, warmup = 8, iters = 8)
                val perRule = "%.3fms/rule".format(s.medianMs / count)
                val growth = previousScenario?.let {
                    "  ×%.2f vs %s".format(s.medianMs / prev, PerfHarness.measurementLabel(it, n))
                } ?: ""
                println("  %s %s rules=%4d  interp=%7.2fms  %s%s".format(
                    PerfHarness.measurementLabel(scenario, n), regime, count, s.medianMs, perRule, growth))
                prev = s.medianMs
                previousScenario = scenario
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
        println("  %s → %s (8× rows ⇒ linear ~×8, quadratic ~×64; measured ×${"%.1f".format(last / first)})"
            .format(
                PerfHarness.measurementLabel(sc, 500),
                PerfHarness.measurementLabel(sc, 4000),
            ))
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

    /**
     * **THE COMBINED REAL-WORLD WORST-CASE** — the headline measurement (the user's real-world tax-validation
     * shape: 1000s of fields, 1000s of rules, many crossing groups, summing, top-level references, scaled on the
     * data axis). The FULL ≈1000-field/≈1000-rule model — deep (Claim ▸ Visit ▸ Charge) **and** wide (top-level
     * + many coverage groups) **and** rule-heavy (per-row + walk-up + cross-sibling + `Having`-Sum + nested-star
     * + cross-group Sum-vs-Sum) — measured **interp vs kernel-Java** at N (both regimes), then the interp-only
     * N→4N growth as the interaction-effect O(N²) detector (linear ≈ 4×). The kernel is the ratio baseline at the
     * **feasible N** only: at 4N it would build a 12k-row IDocument + compile the ~1000-rule Java validator and
     * OOM the test worker — and the kernel can't go native anyway, so the interpreter is the thing being stressed
     * (same split as `hugeDocScaling`). The `PerfGateTest.complexRealWorldStaysSubQuadratic` guard trips the build
     * if the growth regresses; correctness (interp == groovy == java) is asserted in the harness at N. If the
     * growth ever goes super-linear, profile with `*PerfProfileTest.profileComplexRealWorld -Dperf.profile=true` + JFR.
     */
    @Test
    fun complexRealWorldScaling() {
        val probe = PerfCatalog.complexRealWorldFull(firing = false)
        println("[edge] COMBINED real-world (deep+wide+rule-heavy+data-axis): " +
            "${probe.interpModel.fields.size} fields, ${probe.interpModel.rules.size} rules:")
        val small = 500
        for (firing in listOf(false, true)) {
            val regime = if (firing) "firing    " else "low-firing"
            val sc = PerfCatalog.complexRealWorldFull(firing)
            // interp-vs-kernel-Java at the feasible N (correctness asserted in the harness)
            val row = PerfHarness.runScenario(sc, small)
            println("  %s claims=%4d (=%d charge rows)  %s".format(regime, small, small * 6, PerfHarness.format(row)))
            // interp-ONLY N→4N growth (the interaction-O(N²) detector at the real scale — no giant kernel doc)
            val n1 = PerfHarness.timeInterpreter(sc, small, warmup = 4, iters = 6)
            val n4 = PerfHarness.timeInterpreter(sc, small * 4, warmup = 4, iters = 6)
            println("  %s → %s %s interp-only  %.1fms@%d → %.1fms@%d claims  N→4N=×%.2f (linear≈4, quadratic≈16)"
                .format(
                    PerfHarness.measurementLabel(sc, small),
                    PerfHarness.measurementLabel(sc, small * 4),
                    regime, n1.medianMs, small, n4.medianMs, small * 4, n4.medianMs / n1.medianMs,
                ))
        }
    }
}
