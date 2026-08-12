package io.github.mbackschat.a12.dm.adapter.perf

import io.github.mbackschat.a12.dm.adapter.perf.PerfBounds
import io.github.mbackschat.a12.dm.adapter.perf.PerfBounds.ABS_CEILING_MS
import io.github.mbackschat.a12.dm.adapter.perf.PerfBounds.CAVEAT
import io.github.mbackschat.a12.dm.adapter.perf.PerfBounds.N_SMALL
import io.github.mbackschat.a12.dm.adapter.perf.PerfBounds.RATIO_CEILING
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import java.util.Locale
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * The **routine performance gate** (`scripts/perf.sh gate` — INTERPRETER-PERFORMANCE.md "The regression gate
 * (mechanics)"): the **sub-quadratic growth guards** — interpreter-only, so high N stays cheap — plus the
 * against-kernel correctness canaries ([complexRealWorldAgreesWithKernel] and [computeOverlayAgreesWithKernel],
 * whose reduced models keep kernel codegen fast).
 * Each growth guard runs the **IF137-ratified paired estimator** ([PerfHarness.pairedGrowth] via
 * [assertSubQuadratic]): interleaved N/4N samples, the **median of the within-pair ratios** — a linear engine
 * medians ≈4×, a quadratic one ≈16×. (Median replaced the earlier min-of-repeats, which "flipped pass↔fail on
 * identical code"; the trip line stays the ratified `<9×` backstop — a tighter bar is an owner-gated per-number
 * promotion, IF137.) This tier runs at EVERY landing; the kernel-ratio benchmark sweeps live in [PerfBenchTest]
 * (`scripts/perf.sh bench`, 2026-07-10 split — their per-scenario Groovy codegen + baselines dominated the old
 * single-tier wall clock). Bounds + the fairness caveat: [PerfBounds]. Pure engine, no CLI.
 */
@Tag("perf")
class PerfGateTest {

    /**
     * Run the [PerfHarness.pairedGrowth] estimator for a gate and assert the median within-pair growth stays under
     * the sub-quadratic trip line. [measureAt] returns a repeated median at a scale; [small]/[large] (default
     * 4×[small]) are
     * the two scales, [unit] labels the scaled axis, and [culprit] names the likely regression when it trips.
     */
    private fun assertSubQuadratic(
        label: String, small: Int, unit: String, culprit: String,
        identityAt: (Int) -> String, large: Int = small * 4, clock: String = CPU_CLOCK,
        measureAt: (Int) -> Double,
    ) {
        val accepted = PerfHarness.assertGrowthBelowCatastropheFloor(label, culprit) {
            PerfHarness.pairedGrowth(small, large, measureAt = measureAt).also { g ->
                println("[perf] %s → %s %s: %.1fms@%d → %.1fms@%d %s  median-growth=%.2f× (linear≈4, quadratic≈16)"
                    .format(Locale.ROOT, identityAt(small), identityAt(large), label, g.smallMs, small, g.largeMs, large, unit, g.ratio))
            }
        }
        // The growth ratio above is blind to a UNIFORM slowdown — it is unchanged at every factor — so the same
        // accepted medians are also held against this runner class's committed anchor (IF280).
        for ((scale, observed) in listOf(small to accepted.smallMs, large to accepted.largeMs)) {
            PerfAnchors.assertWithinBudget(
                ObservationKey(
                    lane = label,
                    identityLabel = identityAt(scale),
                    n = scale,
                    engine = "interpreter",
                    clock = clock,
                ),
                observed,
            ) { PerfHarness.medianOf(PerfBounds.GROWTH_PAIRS) { measureAt(scale) } }
        }
    }

    /**
     * The precise O(N²) detector: time the interpreter (only — no kernel, so high N stays cheap) on B1 at N and
     * 4N; a linear engine grows ~4×, a quadratic one ~16×. Assert the growth factor stays well under quadratic.
     * This is what actually guards the #28 indexing from a reintroduced linear-scan regression.
     */
    @Test
    @TimesInterpreterEntryPoint("validate", Document::class, String::class)
    fun scalingIsSubQuadratic() {
        val b1 = PerfCatalog.b1()
        assertSubQuadratic("B1 sub-quadratic check", N_SMALL, "rows", "the #28 indexing regressed to a linear scan?",
            identityAt = { n -> PerfHarness.measurementLabel(b1, n) }) { n ->
            PerfHarness.timeInterpreterCpu(b1, n, warmup = 5, iters = 7).medianMs
        }
    }

    /**
     * Guards the **row-prefix index** (the `IndexFieldCache` analog) against regression: a per-row rule with a
     * semantic-index lookup was O(rows²) before that index (the `starRows` O(all-rows) scan per call). Time the
     * interpreter (only — no kernel) at N and 4N; linear ≈ 4×, the old quadratic ≈ 16×. Trip well below.
     */
    @Test
    fun semanticIndexStaysSubQuadratic() {
        val si = PerfCatalog.semanticIndexPerRow()
        assertSubQuadratic("semantic-index sub-quadratic", N_SMALL, "rows", "the row-prefix index regressed?",
            identityAt = { n -> PerfHarness.measurementLabel(si, n) }) { n ->
            PerfHarness.timeInterpreterCpu(si, n, warmup = 5, iters = 7).medianMs
        }
    }

    /**
     * Guards a per-row rule that reads a row-INVARIANT starred aggregate. The aggregate's scope is the parent of
     * the iterated group, so every row observes the same value; rebuilding and folding the whole star once per row
     * is O(rows²). The evaluation-call memo must share one result for that scope while keeping nested scopes apart.
     */
    @Test
    fun rowInvariantAggregateStaysSubQuadratic() {
        val aggregate = PerfCatalog.perRowGlobalAggregate()
        assertSubQuadratic("row-invariant aggregate sub-quadratic", 500, "rows",
            "the scoped aggregate memo stopped sharing across rows?",
            identityAt = { n -> PerfHarness.measurementLabel(aggregate, n) }) { n ->
            PerfHarness.timeInterpreterCpu(aggregate, n, warmup = 5, iters = 7).medianMs
        }
    }

    /**
     * Guards the **deep-nested `Having` path** against the O(N²) the `deepRichScaling` benchmark exposed: the
     * `Having` clause's per-scope row contexts (`rowContextsScoped`) scanned ALL rows of the group and
     * prefix-filtered — O(scopes·rows) on a deep doc (it flipped interp/kernel from 0.34 to 1.77 at 1000
     * vendors). The fix routes it through the prefix index ([Document] `rowsWithPrefix`). Time the interpreter
     * (only) at N and 4N vendors (×12 tiers each); linear ≈ 4×, the old quadratic ≈ 16×. Trip well below.
     */
    @Test
    fun deepNestedHavingStaysSubQuadratic() {
        val dr = PerfCatalog.deepRich(firing = false)
        assertSubQuadratic("deep-nested Having sub-quadratic", 250, "vendors", "rowContextsScoped prefix-index regressed?",
            identityAt = { n -> PerfHarness.measurementLabel(dr, n) }) { n ->
            PerfHarness.timeInterpreterCpu(dr, n, warmup = 5, iters = 7).medianMs
        }
    }

    /**
     * Guards **`NumberOfDifferentValues`** against the O(values²) it had: the distinct count was a linear scan of
     * the distinct-so-far per element (a latent cliff the easy benchmarks overshadowed; `latentQuadraticHunt`
     * caught it super-linear). Fixed to sort + count adjacent-distinct (O(n log n)). Time the interpreter (only)
     * over N and 4N all-distinct values; n log n ≈ ~4–5×, the old quadratic ≈ 16×. Trip well below.
     */
    @Test
    fun numberOfDifferentValuesStaysSubQuadratic() {
        val ndv = PerfCatalog.numDifferentValues()
        assertSubQuadratic("NumberOfDifferentValues sub-quadratic", N_SMALL, "values",
            "the distinct sort regressed to a linear scan?",
            identityAt = { n -> PerfHarness.measurementLabel(ndv, n) }) { n ->
            PerfHarness.timeInterpreterCpu(ndv, n, warmup = 5, iters = 7).medianMs
        }
    }

    /**
     * Guards **`FieldValuesNotUnique`** against the O(values²) it had with a STARRED operand: uniqueness across
     * a repetition's rows was an O(n²) pairwise `eq` scan (latent — every other benchmark used unique keys, so
     * this path was never stressed; `latentQuadraticHunt` caught it ×9.6). Fixed to an O(n) canonical-key set.
     * Time the interpreter (only) over N and 4N all-distinct rows; linear ≈ ×4, the old quadratic ≈ ×16.
     */
    @Test
    fun fieldValuesNotUniqueStaysSubQuadratic() {
        val fvnu = PerfCatalog.fieldValuesNotUniqueStar()
        assertSubQuadratic("FieldValuesNotUnique sub-quadratic", N_SMALL, "values",
            "the key-set regressed to a pairwise scan?",
            identityAt = { n -> PerfHarness.measurementLabel(fvnu, n) }) { n ->
            PerfHarness.timeInterpreterCpu(fvnu, n, warmup = 5, iters = 7).medianMs
        }
    }

    /**
     * Guards the **breadth axis** (many sibling groups) against a per-group setup that scans the whole model
     * (which would be O(groups²)). Time the interpreter (only) at N and 4N sibling groups, fixed rows/group;
     * linear ≈ 4×, a per-group full-model scan ≈ 16×. The per-group machinery (`contextsFor`, attach-depth maps)
     * is memoized per group, so this stays linear — the guard catches a regression that de-memoizes it.
     */
    @Test
    fun wideGroupsStaysSubQuadratic() {
        assertSubQuadratic("wide sibling-groups sub-quadratic", 50, "groups", "per-group setup scans the whole model?",
            identityAt = { groups -> PerfHarness.measurementLabel(PerfCatalog.wideGroups(groups), 20) }) { g ->
            PerfHarness.timeInterpreterCpu(PerfCatalog.wideGroups(g), 20, warmup = 5, iters = 7).medianMs
        }
    }

    /**
     * Guards **tiered computations** (IF21) against a per-row regression: `compute()` evaluates each alternative's
     * `precondition` and selects the operation PER Line row, so the cost is O(rows · precondition) — it must stay
     * LINEAR (the precondition is a `Cond` eval per computed instance, exactly like a rule, folded into the
     * existing dependency graph; no per-row rescan). Time the interpreter (only) over N and 4N Line rows; linear
     * ≈ 4×, a per-row-rescan quadratic ≈ 16×. This is the perf half of the IF21 fix the standing perf discipline
     * requires — the "no O(N²)" claim MEASURED, not asserted.
     */
    @Test
    fun tieredComputationStaysSubQuadratic() {
        val tc = PerfCatalog.tieredComputation()
        assertSubQuadratic("tiered compute sub-quadratic", N_SMALL, "rows", "precondition selection rescans per row?",
            identityAt = { n -> PerfHarness.measurementLabel(tc, n) }) { n ->
            PerfHarness.timeInterpreterCpu(tc, n, warmup = 5, iters = 7).medianMs
        }
    }

    /**
     * Guards **IF20 declared field-type constraints** (here a NUMBER min/max range) against a per-row regression:
     * the constraint check runs once per filled cell inside the existing single-pass formal loop, so it must stay
     * LINEAR. Time the interpreter (only) over N and 4N rows of a constrained field with valid values; linear ≈ 4×,
     * a per-row rescan ≈ 16×. This is the measured "no O(N²)" for the per-cell family (uniqueValue — the genuine
     * O(N²) risk — is deferred precisely because it needs the canonical-key set, not this per-cell path).
     */
    @Test
    fun constrainedFieldsStaySubQuadratic() {
        val cf = PerfCatalog.constrainedFields()
        assertSubQuadratic("constrained-field formal check sub-quadratic", N_SMALL, "rows",
            "the per-cell constraint check rescans?",
            identityAt = { n -> PerfHarness.measurementLabel(cf, n) }) { n ->
            PerfHarness.timeInterpreterCpu(cf, n, warmup = 5, iters = 7).medianMs
        }
    }

    /**
     * Guards the **multi-star `$`-correlation** (IF144) against the O(rows²) naive scan it used before the
     * first-star-scope hash join: `MoreThanOneFieldFilled(Lines* then Subs* then Cost Having [Cost] == [$Cost])`,
     * per-Sub, correlates each Sub against EVERY Line's Subs. The naive scan was measured WORSE than the kernel
     * (interp/kernel ratio widened 0.51→1.21); the hash join makes it O(rows). Time the interpreter (only) at N
     * and 4N Lines (×5 Subs); linear ≈ 4×, a reverted naive scan ≈ 16×. Trip well below.
     */
    @Test
    fun multiStarCorrelationStaysSubQuadratic() {
        val ms = PerfCatalog.multiStarCorrelation()
        assertSubQuadratic("multi-star \$-correlation sub-quadratic", N_SMALL, "lines", "reverted to the naive scan?",
            identityAt = { n -> PerfHarness.measurementLabel(ms, n) }) { n ->
            PerfHarness.timeInterpreterCpu(ms, n, warmup = 5, iters = 7).medianMs
        }
    }

    /**
     * The O(rows²) detector for the **parallel-iteration outer join** — the place a naive sibling-join would
     * quadratic. Time the interpreter (only) at N and 4N; the index-keyed join is linear (≈4×), a nested-loop
     * join would be ≈16×. Guards the `IndexFieldCache`-analog (value→row map + prefix-indexed rows).
     */
    @Test
    fun parallelIterationStaysSubQuadratic() {
        val pi = PerfCatalog.parallel()
        assertSubQuadratic("parallel-iteration sub-quadratic", N_SMALL, "rows", "nested-loop join regression?",
            identityAt = { n -> PerfHarness.measurementLabel(pi, n) }) { n ->
            PerfHarness.timeInterpreterCpu(pi, n, warmup = 5, iters = 7).medianMs
        }
    }

    @Test
    fun numberIndexValidationBeatsBothKernelStrategiesAndCostsLessPerRow() {
        assertNumberIndexCurve(PerfCatalog.numberIndexValidation())
    }

    @Test
    fun numberIndexComputationBeatsBothKernelStrategiesAndCostsLessPerRow() {
        assertNumberIndexCurve(PerfCatalog.numberIndexComputation())
    }

    /** Fixed-size tri-engine ratios are the primary Number-key cost oracle; the paired curve additionally catches
     *  a catastrophic rescan. [PerfHarness.pairedScenarioGrowth] refuses a loss to either kernel strategy at both
     *  sizes, refuses a higher interpreter per-unit cost than either strategy, and refuses the interpreter's own
     *  CPU-clock growth reaching the catastrophe floor — all after proving exact semantic agreement (IF279). */
    private fun assertNumberIndexCurve(scenario: PerfScenario) {
        val curve = PerfHarness.pairedScenarioGrowth(
            scenario,
            small = NUMBER_INDEX_N,
            large = NUMBER_INDEX_N * 4,
            pairs = 3,
            warmup = 5,
            iters = 5,
        )
        println(PerfHarness.format(curve.small, CAVEAT))
        println(PerfHarness.format(curve.large, CAVEAT))
        for (row in listOf(curve.small, curve.large)) {
            assertTrue(row.interp.medianMs < ABS_CEILING_MS,
                "${row.scenario}: Number-index interpreter ${row.interp.medianMs}ms exceeds ${ABS_CEILING_MS}ms at N=${row.n}")
        }
    }

    /**
     * **Framed** parallel iteration (`/Plan/X/A ⋈ B`, X a repeatable frame) must stay **linear in the frame-row
     * count**. N scales the frame (X); the shared clean group B has a BOUNDED key set, built ONCE per
     * common-parent row (the `IndexFieldCache`-analog hoist), so each frame row's join is O(1) and the whole
     * thing O(N). Time the interpreter (only) at N and 4N — linear ≈4×; a regression that rebuilt B's map per
     * frame row, or made the frame loop super-linear, trips well above. (Scaling B too is inherently
     * O(frame × B) for both engines — the framed shape's cost, not a cliff — so only the frame scales here.)
     */
    @Test
    fun parallelFramedStaysSubQuadratic() {
        val pf = PerfCatalog.parallelFramed()
        assertSubQuadratic("framed parallel-iteration sub-quadratic", N_SMALL, "rows",
            "clean-group map not hoisted out of the frame loop?",
            identityAt = { n -> PerfHarness.measurementLabel(pf, n) }) { n ->
            PerfHarness.timeInterpreterCpu(pf, n, warmup = 5, iters = 7).medianMs
        }
    }

    /**
     * **Cold-path / startup gate — a CO-EQUAL goal.** The warm gates above prime everything out, so they are
     * blind to startup cost (that is how the index-build O(N²) hid). This guards the COLD first-call: a fresh
     * engine + fresh document + ONE `validateFull` (no warmup), timed at N and 4N. A reintroduced cold O(N²)
     * (build dedup, a per-call scan paid on the first pass) trips here — linear ≈ 4×, quadratic ≈ 16×.
     */
    @Test
    fun coldStartStaysSubQuadratic() {
        val b1 = PerfCatalog.b1()
        assertSubQuadratic("COLD first-call (startup)", N_SMALL, "rows", "a cold-path O(N²) regression?",
            identityAt = { n -> PerfHarness.measurementLabel(b1, n) }, clock = WALL_CLOCK) { n ->
            PerfHarness.freshRunMedianMillis(b1, n)
        }
    }

    /**
     * **The combined real-world worst-case — correctness gate.** A REDUCED-size instance of the
     * deep+wide+rule-heavy+data-axis model ([PerfCatalog.complexRealWorldGate] — ~100 rules, fast kernel
     * codegen), run in BOTH firing regimes. Asserts interp == kernel on the **full multi-construct mix at once**
     * (per-row + top-level walk-up + cross-sibling + `Having`-Sum + deep nested-star + cross-group Sum-vs-Sum) —
     * a combination no single-construct `*DiffTest` covers — then the generous gate bounds. The headline
     * interp-vs-Java numbers at the ≈1000-field/≈1000-rule real-world scale live in
     * `KernelEdgeTest.complexRealWorldScaling` (opt-in, where the full kernel codegen cost is acceptable).
     */
    @Test
    fun complexRealWorldAgreesWithKernel() {
        for (firing in listOf(false, true)) {
            val row = PerfHarness.runScenario(PerfCatalog.complexRealWorldGate(firing), n = 60, withJava = false)
            println(PerfHarness.format(row, CAVEAT))
            assertTrue(row.interp.medianMs < ABS_CEILING_MS,
                "${row.scenario}: interpreter ${row.interp.medianMs}ms exceeds ${ABS_CEILING_MS}ms at N=${row.n}")
            val ratio = row.ratioGroovy
            if (ratio != null) assertTrue(ratio < RATIO_CEILING,
                "${row.scenario}: complex interp/kernel ratio $ratio exceeds $RATIO_CEILING at N=${row.n}")
        }
    }

    /**
     * **The interaction-effect O(N²) guard** — the reason the combined benchmark exists. The FULL
     * ≈1000-field/≈1000-rule model, interp-only (no kernel codegen, so high N stays cheap), timed at N and 4N
     * Claim rows. The deep chain (Claim ▸ Visit ▸ Charge) scales with N while the whole ≈1000-rule mix runs; a
     * per-row rule that walked O(rows) (an un-indexed cross-ref / re-folded aggregate) would compound to
     * O(rows²) — invisible to every single-axis benchmark, visible here. Linear ≈ 4×; trip well below the
     * quadratic ~16×. (The fixed breadth-rule cost dilutes the ratio *toward* linear, so this is conservative —
     * a trip is a real regression, not noise.)
     */
    @Test
    fun complexRealWorldStaysSubQuadratic() {
        val sc = PerfCatalog.complexRealWorldFull(firing = false)
        assertSubQuadratic(
            "complex real-world (${sc.interpModel.fields.size}f/${sc.interpModel.rules.size}r) sub-quadratic",
            250, "claims", "an interaction O(N²)?",
            identityAt = { n -> PerfHarness.measurementLabel(sc, n) },
        ) { n -> PerfHarness.timeInterpreterCpu(sc, n, warmup = 4, iters = 7).medianMs }
    }

    /**
     * Guards the **Europe/Berlin DATE_TIME instant path** (IF132) against a per-conversion regression: every
     * DATE_TIME parse under a non-UTC model runs the `berlinOffsetSeconds` civil-calendar offset (IF128/IF131),
     * and a per-row `DifferenceInHours` rule runs it once per conversion per row — so it must stay LINEAR in rows.
     * Time the interpreter (only) at N and 4N Berlin datetime rows; linear ≈ 4×, a per-conversion blowup ≈ 16×.
     * This is the committed gate that keeps the IF128 offset optimization from silently regressing (its speedup
     * was previously probe-only — the datetime hole IF132 named); the against-kernel ratio is the B9-berlin case
     * in [PerfBenchTest.gatesEveryInventoryScenario] (derived from [PerfInventory]).
     */
    @Test
    fun berlinDateTimeStaysSubQuadratic() {
        val dt = PerfCatalog.berlinDateTime()
        assertSubQuadratic("Berlin DATE_TIME sub-quadratic", N_SMALL, "rows", "the offset path regressed?",
            identityAt = { n -> PerfHarness.measurementLabel(dt, n) }) { n ->
            PerfHarness.timeInterpreterCpu(dt, n, warmup = 5, iters = 7).medianMs
        }
    }

    /**
     * Guards DATE_TIME `AddDays` wall-landings across ordinary hours, spring gaps, and fall overlaps in both
     * directions, including the historical CEMT offset. Semantic agreement is proved at both measured scales
     * before timing; the paired N/4N estimator then keeps the path below the existing catastrophe floor.
     */
    @Test
    fun berlinAddDaysStaysSubQuadratic() {
        val dt = PerfCatalog.berlinAddDays()
        PerfHarness.assertSemanticAgreement(dt, listOf(AD_N, AD_N * 4), withJava = true)
        assertSubQuadratic(
            "Berlin historical AddDays sub-quadratic",
            AD_N,
            "rows",
            "wall-day landing rebuilt candidate collections per row?",
            identityAt = { n -> PerfHarness.measurementLabel(dt, n) },
        ) { n -> PerfHarness.timeInterpreterCpu(dt, n, warmup = 5, iters = 7).medianMs }
    }

    /**
     * Guards the **compute-overlay index-column view** (IF143) — the HARD O(rows²): under an ACTIVE cascade
     * overlay, a per-Row `[Src For "K1"]` read rebuilt `indexColumn(Key)` from the wrapped cells on EVERY outer Row
     * (the [io.github.mbackschat.a12.dm.interpreter.eval.OverlayContext] bypassed the raw document-level cache), and
     * that rebuild dominated. The per-step overlay-view cache shares the view across the Rows of a computation step →
     * O(rows). Time the interpreter (only) over N and 4N Rows; the fix took it from **18.75× (quadratic) to ~3.5×
     * (linear)** at 1500/6000. Trip well below the ~16× a reintroduced per-Row rebuild would show.
     */
    @Test
    fun computeOverlaySemanticIndexStaysSubQuadratic() {
        val sc = PerfCatalog.computeOverlaySemanticIndex()
        assertSubQuadratic("compute-overlay index-column sub-quadratic", CO_N, "rows",
            "the OverlayContext rebuilds indexColumn per outer Row instead of caching per step?",
            identityAt = { n -> PerfHarness.measurementLabel(sc, n) }) { n ->
            PerfHarness.timeInterpreterCpu(sc, n, warmup = 8, iters = 7).medianMs
        }
    }

    /**
     * A **soft** guard on the compute-overlay `$`-correlation path (IF143). Unlike the index-column path (a HARD
     * O(rows²), 18.75×→3.5× across the fix), the `$`-correlation rebuild reuses the doc-cached `rowContexts`, so
     * its per-row constant is tiny: the O(rows²) never clears the JIT-deflated timing noise into the quadratic
     * band, and the cache is roughly NEUTRAL here (the shared `OverlayViews` mechanism is proven by the strong
     * index gate above). This gate stays at the fast [CO_N] and only trips on a SEVERE regression (a large-constant
     * per-row rebuild pushing growth past `SUB_QUADRATIC_MAX`); the correlation cache's CORRECTNESS is locked by
     * [computeOverlayAgreesWithKernel] + `interpreter.OverlayDerivedViewCacheTest`.
     */
    @Test
    fun computeOverlayCorrelationStaysSubQuadratic() {
        val sc = PerfCatalog.computeOverlayCorrelation()
        assertSubQuadratic("compute-overlay \$-correlation sub-quadratic", CO_N, "rows",
            "the OverlayContext rebuilds correlationBuckets per outer Row with a large constant?",
            identityAt = { n -> PerfHarness.measurementLabel(sc, n) }) { n ->
            PerfHarness.timeInterpreterCpu(sc, n, warmup = 8, iters = 7).medianMs
        }
    }

    /**
     * **Compute-overlay correctness gate (IF143)** — both overlay-view scenarios (index-column + `$`-correlation)
     * run through the kernel at small N: `runScenario` asserts the compute outcomes agree interp == groovy == java
     * (the "zero unsupported + VALUE/CLEARED/ERRORED parity before timing" IF143 requires — a compute over an
     * unsupported construct would throw, and a stale overlay-view cache would diverge). Kept small so the kernel's
     * per-scenario codegen stays cheap; the growth gates above carry the scaling proof.
     */
    @Test
    fun computeOverlayAgreesWithKernel() {
        for (sc in listOf(PerfCatalog.computeOverlaySemanticIndex(), PerfCatalog.computeOverlayCorrelation())) {
            val row = PerfHarness.runScenario(sc, n = 200, withJava = true)
            println(PerfHarness.format(row, CAVEAT))
            assertTrue(row.interp.medianMs < ABS_CEILING_MS,
                "${row.scenario}: interpreter ${row.interp.medianMs}ms exceeds ${ABS_CEILING_MS}ms at N=${row.n}")
        }
    }

    private companion object {
        /** The clock an anchored observation was taken on — part of its key, since the two are not comparable. */
        const val CPU_CLOCK = "cpu"
        const val WALL_CLOCK = "wall"

        /** Rows for the historical AddDays gate: enough repetitions to expose per-row allocation/scaling. */
        const val AD_N = 1_000

        /** Row count for the index-column compute-overlay gate — smaller than [N_SMALL] (the compute cascade is
         *  heavier), yet the per-row `indexColumn` rebuild dominates, so the O(rows²) is unmistakable here. */
        const val CO_N = 1_500

        /** Fixed-size Number-index anchor; 4× is large enough to expose a per-row rescan without dominating gate time. */
        const val NUMBER_INDEX_N = 500
    }
}
