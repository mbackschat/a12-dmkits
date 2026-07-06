package io.github.mbackschat.a12.dm.adapter.perf

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
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
 * the kernel on requiredness, format/formal-checking, rich messages, index-field uniqueness, AND the
 * `FieldValuesNotUnique` operator (all forced to parity by the fuzz net), but the kernel's `validateFull` still
 * auto-validates one dimension the interpreter does not model — the **declared field-type constraints** (number
 * range min/max + integer-digit-count, string length/pattern, enum/value-list domain membership; KERNEL-FINDINGS
 * §3 "declared field-type constraints" / INTERPRETER-FINDINGS IF20). These scenarios carry no such constraints,
 * so the missing dimension does not skew them, but in general "interp faster" can partly mean "interp does less"
 * — the honest read is **competitive-to-faster on these scenarios**, not "Nx faster" as a speed verdict. The
 * bounds are GATES (catch a regression, tolerate CI variance), not SLAs. Pure engine, no CLI.
 */
@Tag("perf")
class PerfGateTest {

    /** (scenario, gate N). Nested scenarios use a smaller Line count (× [PerfCatalog.SUBS_PER_LINE] = sub rows). */
    private val gate = listOf(
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
        val rows = gate.map { (scenario, n) ->
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
     * measured-against-the-kernel half of the IF21 fix (the growth guard is [tieredComputationStaysSubQuadratic]).
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

    private companion object {
        const val ABS_CEILING_MS = 5_000.0
        const val RATIO_CEILING = 5.0
        const val N_SMALL = 5_000
        const val GROWTH_REPEATS = 3        // min growth over a few back-to-back pairings → robust to machine load
        const val SUB_QUADRATIC_MAX = 9.0   // linear ≈ 4; a quadratic 4×-rows blowup is ≈ 16 — 9 is the trip line
        // Codegen is NOT in the timed region (primed out by warmup — the kernel generates ahead). This routine
        // gate measures against Groovy (the shortcut); the Groovy execution tax (~1.0–1.3×, pinned by
        // KernelEdgeTest) and the residual "kernel validates MORE dimensions" make this NOT apples-to-apples.
        const val CAVEAT = "(NOT apples-to-apples: kernel also auto-validates declared field-type constraints (range/length/pattern/enum-domain — IF20); codegen excluded; Groovy baseline + ~1.0–1.3× Groovy tax)"
    }
}
