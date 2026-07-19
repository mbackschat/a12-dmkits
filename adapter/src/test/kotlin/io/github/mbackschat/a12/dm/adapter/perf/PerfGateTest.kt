package io.github.mbackschat.a12.dm.adapter.perf

import io.github.mbackschat.a12.dm.adapter.perf.PerfBounds.ABS_CEILING_MS
import io.github.mbackschat.a12.dm.adapter.perf.PerfBounds.CAVEAT
import io.github.mbackschat.a12.dm.adapter.perf.PerfBounds.GROWTH_REPEATS
import io.github.mbackschat.a12.dm.adapter.perf.PerfBounds.N_SMALL
import io.github.mbackschat.a12.dm.adapter.perf.PerfBounds.RATIO_CEILING
import io.github.mbackschat.a12.dm.adapter.perf.PerfBounds.SUB_QUADRATIC_MAX
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * The **routine performance gate** (`scripts/perf.sh gate` — INTERPRETER-PERFORMANCE.md "The regression gate
 * (mechanics)"): the **sub-quadratic growth guards** — interpreter-only, so high N stays cheap — plus ONE
 * against-kernel canary ([complexRealWorldAgreesWithKernel], whose reduced model keeps kernel codegen fast).
 * A growth check times N and 4N back-to-back: a linear engine grows ≈4×, a quadratic one ≈16×; the min-growth
 * over a few repeats is robust to machine load (a single-scale absolute bound can't tell linear from
 * quadratic, but a growth factor across scales can). This tier runs at EVERY landing; the kernel-ratio
 * benchmark sweeps live in [PerfBenchTest] (`scripts/perf.sh bench`, 2026-07-10 split — their per-scenario
 * Groovy codegen + baselines dominated the old single-tier wall clock). Bounds + the fairness caveat:
 * [PerfBounds]. Pure engine, no CLI.
 */
@Tag("perf")
class PerfGateTest {

    /**
     * The precise O(N²) detector: time the interpreter (only — no kernel, so high N stays cheap) on B1 at N and
     * 4N; a linear engine grows ~4×, a quadratic one ~16×. Assert the growth factor stays well under quadratic.
     * This is what actually guards the #28 indexing from a reintroduced linear-scan regression.
     */
    @Test
    fun scalingIsSubQuadratic() {
        val b1 = PerfCatalog.b1()
        // Robust estimator against machine load (this gate flipped pass↔fail on identical code):
        //  - per N, take the MIN time (least-contended single-thread run; noise only inflates);
        //  - measure small+large back-to-back so a repeat's two Ns share the same load → their RATIO is stable;
        //  - take the MIN growth over a few repeats → the least-contended pairing. (A single min/min could still
        //    inflate under SUSTAINED load if large-N never got a clean slot while small-N did; min-of-repeats does not.)
        var best = Double.MAX_VALUE
        var bestSmall = 0.0
        var bestLarge = 0.0
        repeat(GROWTH_REPEATS) {
            val small = PerfHarness.timeInterpreter(b1, N_SMALL, warmup = 5, iters = 7)
            val large = PerfHarness.timeInterpreter(b1, N_SMALL * 4, warmup = 5, iters = 7)
            val growth = large.minMs / small.minMs
            if (growth < best) { best = growth; bestSmall = small.minMs; bestLarge = large.minMs }
        }
        println("[perf] B1 sub-quadratic check: ${"%.1f".format(bestSmall)}ms@$N_SMALL → " +
            "${"%.1f".format(bestLarge)}ms@${N_SMALL * 4}  min-growth=${"%.2f".format(best)}× (linear≈4, quadratic≈16)")
        assertTrue(best < SUB_QUADRATIC_MAX,
            "B1 scaling looks super-linear: 4× the rows took ${best}× the time (quadratic would be ~16×)")
    }

    /**
     * Guards the **row-prefix index** (the `IndexFieldCache` analog) against regression: a per-row rule with a
     * semantic-index lookup was O(rows²) before that index (the `starRows` O(all-rows) scan per call). Time the
     * interpreter (only — no kernel) at N and 4N; linear ≈ 4×, the old quadratic ≈ 16×. Trip well below.
     */
    @Test
    fun semanticIndexStaysSubQuadratic() {
        val si = PerfCatalog.semanticIndexPerRow()
        var best = Double.MAX_VALUE
        var bSmall = 0.0; var bLarge = 0.0
        repeat(GROWTH_REPEATS) {
            val small = PerfHarness.timeInterpreter(si, N_SMALL, warmup = 5, iters = 7)
            val large = PerfHarness.timeInterpreter(si, N_SMALL * 4, warmup = 5, iters = 7)
            val g = large.minMs / small.minMs
            if (g < best) { best = g; bSmall = small.minMs; bLarge = large.minMs }
        }
        println("[perf] semantic-index sub-quadratic: ${"%.1f".format(bSmall)}ms@$N_SMALL → " +
            "${"%.1f".format(bLarge)}ms@${N_SMALL * 4}  min-growth=${"%.2f".format(best)}× (linear≈4, old-quadratic≈16)")
        assertTrue(best < SUB_QUADRATIC_MAX,
            "semantic-index scaling looks super-linear: 4× rows took ${best}× the time (the row-prefix index regressed?)")
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
        val small0 = 250
        var best = Double.MAX_VALUE
        var bSmall = 0.0; var bLarge = 0.0
        repeat(GROWTH_REPEATS) {
            val small = PerfHarness.timeInterpreter(dr, small0, warmup = 5, iters = 7)
            val large = PerfHarness.timeInterpreter(dr, small0 * 4, warmup = 5, iters = 7)
            val g = large.minMs / small.minMs
            if (g < best) { best = g; bSmall = small.minMs; bLarge = large.minMs }
        }
        println("[perf] deep-nested Having sub-quadratic: ${"%.1f".format(bSmall)}ms@$small0 → " +
            "${"%.1f".format(bLarge)}ms@${small0 * 4} vendors  min-growth=${"%.2f".format(best)}× (linear≈4, old-quadratic≈16)")
        assertTrue(best < SUB_QUADRATIC_MAX,
            "deep-nested Having scaling looks super-linear: 4× vendors took ${best}× the time (rowContextsScoped prefix-index regressed?)")
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
        var best = Double.MAX_VALUE
        var bSmall = 0.0; var bLarge = 0.0
        repeat(GROWTH_REPEATS) {
            val small = PerfHarness.timeInterpreter(ndv, N_SMALL, warmup = 5, iters = 7)
            val large = PerfHarness.timeInterpreter(ndv, N_SMALL * 4, warmup = 5, iters = 7)
            val g = large.minMs / small.minMs
            if (g < best) { best = g; bSmall = small.minMs; bLarge = large.minMs }
        }
        println("[perf] NumberOfDifferentValues sub-quadratic: ${"%.1f".format(bSmall)}ms@$N_SMALL → " +
            "${"%.1f".format(bLarge)}ms@${N_SMALL * 4} values  min-growth=${"%.2f".format(best)}× (n·log n≈4–5, old-quadratic≈16)")
        assertTrue(best < SUB_QUADRATIC_MAX,
            "NumberOfDifferentValues scaling looks quadratic: 4× values took ${best}× the time (the distinct sort regressed to a linear scan?)")
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
        var best = Double.MAX_VALUE
        var bSmall = 0.0; var bLarge = 0.0
        repeat(GROWTH_REPEATS) {
            val small = PerfHarness.timeInterpreter(fvnu, N_SMALL, warmup = 5, iters = 7)
            val large = PerfHarness.timeInterpreter(fvnu, N_SMALL * 4, warmup = 5, iters = 7)
            val g = large.minMs / small.minMs
            if (g < best) { best = g; bSmall = small.minMs; bLarge = large.minMs }
        }
        println("[perf] FieldValuesNotUnique sub-quadratic: ${"%.1f".format(bSmall)}ms@$N_SMALL → " +
            "${"%.1f".format(bLarge)}ms@${N_SMALL * 4} values  min-growth=${"%.2f".format(best)}× (linear≈4, old-quadratic≈16)")
        assertTrue(best < SUB_QUADRATIC_MAX,
            "FieldValuesNotUnique scaling looks quadratic: 4× values took ${best}× the time (the key-set regressed to a pairwise scan?)")
    }

    /**
     * Guards the **breadth axis** (many sibling groups) against a per-group setup that scans the whole model
     * (which would be O(groups²)). Time the interpreter (only) at N and 4N sibling groups, fixed rows/group;
     * linear ≈ 4×, a per-group full-model scan ≈ 16×. The per-group machinery (`contextsFor`, attach-depth maps)
     * is memoized per group, so this stays linear — the guard catches a regression that de-memoizes it.
     */
    @Test
    fun wideGroupsStaysSubQuadratic() {
        val small0 = 50
        var best = Double.MAX_VALUE
        var bSmall = 0.0; var bLarge = 0.0
        repeat(GROWTH_REPEATS) {
            val small = PerfHarness.timeInterpreter(PerfCatalog.wideGroups(small0), 20, warmup = 5, iters = 7)
            val large = PerfHarness.timeInterpreter(PerfCatalog.wideGroups(small0 * 4), 20, warmup = 5, iters = 7)
            val g = large.minMs / small.minMs
            if (g < best) { best = g; bSmall = small.minMs; bLarge = large.minMs }
        }
        println("[perf] wide sibling-groups sub-quadratic: ${"%.1f".format(bSmall)}ms@$small0 → " +
            "${"%.1f".format(bLarge)}ms@${small0 * 4} groups  min-growth=${"%.2f".format(best)}× (linear≈4, per-group-scan≈16)")
        assertTrue(best < SUB_QUADRATIC_MAX,
            "wide sibling-groups scaling looks super-linear: 4× groups took ${best}× the time (per-group setup scans the whole model?)")
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
        var best = Double.MAX_VALUE
        var bSmall = 0.0; var bLarge = 0.0
        repeat(GROWTH_REPEATS) {
            val small = PerfHarness.timeInterpreter(tc, N_SMALL, warmup = 5, iters = 7)
            val large = PerfHarness.timeInterpreter(tc, N_SMALL * 4, warmup = 5, iters = 7)
            val g = large.minMs / small.minMs
            if (g < best) { best = g; bSmall = small.minMs; bLarge = large.minMs }
        }
        println("[perf] tiered compute sub-quadratic: ${"%.1f".format(bSmall)}ms@$N_SMALL → " +
            "${"%.1f".format(bLarge)}ms@${N_SMALL * 4} rows  min-growth=${"%.2f".format(best)}× (linear≈4, per-row-rescan≈16)")
        assertTrue(best < SUB_QUADRATIC_MAX,
            "tiered-computation compute scaling looks super-linear: 4× rows took ${best}× the time (precondition selection rescans per row?)")
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
        var best = Double.MAX_VALUE
        var bSmall = 0.0; var bLarge = 0.0
        repeat(GROWTH_REPEATS) {
            val small = PerfHarness.timeInterpreter(cf, N_SMALL, warmup = 5, iters = 7)
            val large = PerfHarness.timeInterpreter(cf, N_SMALL * 4, warmup = 5, iters = 7)
            val g = large.minMs / small.minMs
            if (g < best) { best = g; bSmall = small.minMs; bLarge = large.minMs }
        }
        println("[perf] constrained-field formal check sub-quadratic: ${"%.1f".format(bSmall)}ms@$N_SMALL → " +
            "${"%.1f".format(bLarge)}ms@${N_SMALL * 4} rows  min-growth=${"%.2f".format(best)}× (linear≈4, per-row-rescan≈16)")
        assertTrue(best < SUB_QUADRATIC_MAX,
            "constrained-field formal check looks super-linear: 4× rows took ${best}× the time (the per-cell constraint check rescans?)")
    }

    /**
     * Guards the **multi-star `$`-correlation** (IG98) against the O(rows²) naive scan it used before the
     * first-star-scope hash join: `MoreThanOneFieldFilled(Lines* then Subs* then Cost Having [Cost] == [$Cost])`,
     * per-Sub, correlates each Sub against EVERY Line's Subs. The naive scan was measured WORSE than the kernel
     * (interp/kernel ratio widened 0.51→1.21); the hash join makes it O(rows). Time the interpreter (only) at N
     * and 4N Lines (×5 Subs); linear ≈ 4×, a reverted naive scan ≈ 16×. Trip well below.
     */
    @Test
    fun multiStarCorrelationStaysSubQuadratic() {
        val ms = PerfCatalog.multiStarCorrelation()
        var best = Double.MAX_VALUE
        var bSmall = 0.0; var bLarge = 0.0
        repeat(GROWTH_REPEATS) {
            val small = PerfHarness.timeInterpreter(ms, N_SMALL, warmup = 5, iters = 7)
            val large = PerfHarness.timeInterpreter(ms, N_SMALL * 4, warmup = 5, iters = 7)
            val g = large.minMs / small.minMs
            if (g < best) { best = g; bSmall = small.minMs; bLarge = large.minMs }
        }
        println("[perf] multi-star \$-correlation sub-quadratic: ${"%.1f".format(bSmall)}ms@$N_SMALL → " +
            "${"%.1f".format(bLarge)}ms@${N_SMALL * 4} lines  min-growth=${"%.2f".format(best)}× (linear≈4, naive-scan≈16)")
        assertTrue(best < SUB_QUADRATIC_MAX,
            "multi-star \$-correlation scaling looks super-linear: 4× lines took ${best}× the time (reverted to the naive scan?)")
    }

    /**
     * The O(rows²) detector for the **parallel-iteration outer join** — the place a naive sibling-join would
     * quadratic. Time the interpreter (only) at N and 4N; the index-keyed join is linear (≈4×), a nested-loop
     * join would be ≈16×. Guards the `IndexFieldCache`-analog (value→row map + prefix-indexed rows).
     */
    @Test
    fun parallelIterationStaysSubQuadratic() {
        val pi = PerfCatalog.parallel()
        var best = Double.MAX_VALUE
        var bSmall = 0.0; var bLarge = 0.0
        repeat(GROWTH_REPEATS) {
            val small = PerfHarness.timeInterpreter(pi, N_SMALL, warmup = 5, iters = 7)
            val large = PerfHarness.timeInterpreter(pi, N_SMALL * 4, warmup = 5, iters = 7)
            val g = large.minMs / small.minMs
            if (g < best) { best = g; bSmall = small.minMs; bLarge = large.minMs }
        }
        println("[perf] parallel-iteration sub-quadratic: ${"%.1f".format(bSmall)}ms@$N_SMALL → " +
            "${"%.1f".format(bLarge)}ms@${N_SMALL * 4}  min-growth=${"%.2f".format(best)}× (linear≈4, nested-loop≈16)")
        assertTrue(best < SUB_QUADRATIC_MAX,
            "parallel-iteration scaling looks super-linear: 4× rows took ${best}× the time (nested-loop join regression?)")
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
        var best = Double.MAX_VALUE
        var bSmall = 0.0; var bLarge = 0.0
        repeat(GROWTH_REPEATS) {
            val small = PerfHarness.timeInterpreter(pf, N_SMALL, warmup = 5, iters = 7)
            val large = PerfHarness.timeInterpreter(pf, N_SMALL * 4, warmup = 5, iters = 7)
            val g = large.minMs / small.minMs
            if (g < best) { best = g; bSmall = small.minMs; bLarge = large.minMs }
        }
        println("[perf] framed parallel-iteration sub-quadratic: ${"%.1f".format(bSmall)}ms@$N_SMALL → " +
            "${"%.1f".format(bLarge)}ms@${N_SMALL * 4}  min-growth=${"%.2f".format(best)}× (linear≈4, un-hoisted≈16)")
        assertTrue(best < SUB_QUADRATIC_MAX,
            "framed parallel-iteration scaling looks super-linear: 4× rows took ${best}× the time (clean-group map not hoisted out of the frame loop?)")
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
        var best = Double.MAX_VALUE
        var bSmall = 0.0; var bLarge = 0.0
        repeat(GROWTH_REPEATS) {
            val small = PerfHarness.coldRunMillis(b1, N_SMALL)
            val large = PerfHarness.coldRunMillis(b1, N_SMALL * 4)
            val g = large / small
            if (g < best) { best = g; bSmall = small; bLarge = large }
        }
        println("[perf] COLD first-call (startup): ${"%.1f".format(bSmall)}ms@$N_SMALL → " +
            "${"%.1f".format(bLarge)}ms@${N_SMALL * 4}  min-growth=${"%.2f".format(best)}× (linear≈4, quadratic≈16)")
        assertTrue(best < SUB_QUADRATIC_MAX,
            "cold first-call scaling looks super-linear: 4× rows took ${best}× the time (a cold-path O(N²) regression?)")
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
        val small0 = 250
        var best = Double.MAX_VALUE
        var bSmall = 0.0; var bLarge = 0.0
        repeat(GROWTH_REPEATS) {
            val small = PerfHarness.timeInterpreter(sc, small0, warmup = 4, iters = 6)
            val large = PerfHarness.timeInterpreter(sc, small0 * 4, warmup = 4, iters = 6)
            val g = large.minMs / small.minMs
            if (g < best) { best = g; bSmall = small.minMs; bLarge = large.minMs }
        }
        println("[perf] complex real-world (${sc.interpModel.fields.size}f/${sc.interpModel.rules.size}r) sub-quadratic: " +
            "${"%.1f".format(bSmall)}ms@$small0 → ${"%.1f".format(bLarge)}ms@${small0 * 4} claims  " +
            "min-growth=${"%.2f".format(best)}× (linear≈4, quadratic≈16)")
        assertTrue(best < SUB_QUADRATIC_MAX,
            "complex real-world scaling looks super-linear: 4× claims took ${best}× the time (an interaction O(N²)?)")
    }

    /**
     * Guards the **Europe/Berlin DATE_TIME instant path** (IF132) against a per-conversion regression: every
     * DATE_TIME parse under a non-UTC model runs the `berlinOffsetSeconds` civil-calendar offset (IF128/IF131),
     * and a per-row `DifferenceInHours` rule runs it once per conversion per row — so it must stay LINEAR in rows.
     * Time the interpreter (only) at N and 4N Berlin datetime rows; linear ≈ 4×, a per-conversion blowup ≈ 16×.
     * This is the committed gate that keeps the IF128 offset optimization from silently regressing (its speedup
     * was previously probe-only — the datetime hole IF132 named); the against-kernel ratio is
     * [PerfBenchTest.berlinDateTimeScalesLikeKernel].
     */
    @Test
    fun berlinDateTimeStaysSubQuadratic() {
        val dt = PerfCatalog.berlinDateTime()
        var best = Double.MAX_VALUE
        var bSmall = 0.0; var bLarge = 0.0
        repeat(GROWTH_REPEATS) {
            val small = PerfHarness.timeInterpreter(dt, N_SMALL, warmup = 5, iters = 7)
            val large = PerfHarness.timeInterpreter(dt, N_SMALL * 4, warmup = 5, iters = 7)
            val g = large.minMs / small.minMs
            if (g < best) { best = g; bSmall = small.minMs; bLarge = large.minMs }
        }
        println("[perf] Berlin DATE_TIME sub-quadratic: ${"%.1f".format(bSmall)}ms@$N_SMALL → " +
            "${"%.1f".format(bLarge)}ms@${N_SMALL * 4} rows  min-growth=${"%.2f".format(best)}× (linear≈4, per-conversion-blowup≈16)")
        assertTrue(best < SUB_QUADRATIC_MAX,
            "Berlin DATE_TIME scaling looks super-linear: 4× rows took ${best}× the time (the offset path regressed?)")
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
    fun computeOverlaySemanticIndexStaysSubQuadratic() = overlaySubQuadratic(
        PerfCatalog.computeOverlaySemanticIndex(), "compute-overlay index-column", CO_N,
        "the OverlayContext rebuilds indexColumn per outer Row instead of caching per step?")

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
    fun computeOverlayCorrelationStaysSubQuadratic() = overlaySubQuadratic(
        PerfCatalog.computeOverlayCorrelation(), "compute-overlay \$-correlation", CO_N,
        "the OverlayContext rebuilds correlationBuckets per outer Row with a large constant?")

    /** Shared sub-quadratic estimator for the two compute-overlay scenarios (IF143) — smaller N than the validate
     *  gates (the compute path runs a two-computation cascade per Row, so it is heavier), same min-of-repeats. */
    private fun overlaySubQuadratic(sc: PerfScenario, label: String, n: Int, culprit: String) {
        var best = Double.MAX_VALUE
        var bSmall = 0.0; var bLarge = 0.0
        repeat(GROWTH_REPEATS) {
            val small = PerfHarness.timeInterpreter(sc, n, warmup = 8, iters = 7)
            val large = PerfHarness.timeInterpreter(sc, n * 4, warmup = 8, iters = 7)
            val g = large.minMs / small.minMs
            if (g < best) { best = g; bSmall = small.minMs; bLarge = large.minMs }
        }
        println("[perf] $label sub-quadratic: ${"%.1f".format(bSmall)}ms@$n → " +
            "${"%.1f".format(bLarge)}ms@${n * 4} rows  min-growth=${"%.2f".format(best)}× (linear≈4, old-per-row-rebuild≈16)")
        assertTrue(best < SUB_QUADRATIC_MAX,
            "$label scaling looks super-linear: 4× rows took ${best}× the time ($culprit)")
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
        /** Row count for the index-column compute-overlay gate — smaller than [N_SMALL] (the compute cascade is
         *  heavier), yet the per-row `indexColumn` rebuild dominates, so the O(rows²) is unmistakable here. */
        const val CO_N = 1_500
    }
}
