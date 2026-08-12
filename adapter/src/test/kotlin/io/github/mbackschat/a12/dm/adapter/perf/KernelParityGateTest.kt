package io.github.mbackschat.a12.dm.adapter.perf

import io.github.mbackschat.a12.dm.adapter.perf.PerfBounds.ABS_CEILING_MS
import io.github.mbackschat.a12.dm.adapter.perf.PerfBounds.CAVEAT
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * **The interpreter beats BOTH kernel strategies, on every gated scenario, at two scales.**
 *
 * This is the estate's #1 requirement and it was asserted on **two** scenarios. The gate's eighteen growth lanes
 * time the interpreter alone — the source comment said so plainly, "interpreter-only, so high N stays cheap" — and
 * the benchmark tier's fifteen inventory rows assert only `RATIO_CEILING`, which is `interp/groovy < 5.0` and so
 * tolerates the interpreter being **five times slower**, with no bar against generated Java at all. The single
 * production path reaching `requireInterpreterBeatsBoth` and [PairedAcceptance] was
 * `PerfHarness.pairedScenarioGrowth`, called from the two Number-index lanes ([PerfGateTest]). Every other gated
 * scenario could have lost to the kernel, at any factor below five against Groovy and by any margin against Java,
 * without a single test turning red.
 *
 * **Each lane here asserts four things**, all inside [PerfHarness.pairedScenarioGrowth]:
 * 1. interpreter == dynamic-Groovy == generated-Java on complete signatures, at **both** scales, before any clock;
 * 2. the interpreter's median is below **both** strategies' at both scales — the level bar;
 * 3. the interpreter's **per-unit cost** `(t_large − t_small)/(n_large − n_small)` is the lowest of the three —
 *    the scaling bar, and the reason a growth *factor* is not used for it: over a 4x step a linear engine's growth
 *    factor is `4 − 3f` in the fixed cost's share, so comparing growth factors ranks engines by fixed-cost share
 *    and the leanest one loses deterministically ([IF279]);
 * 4. the interpreter's own N/4N growth stays under the catastrophe floor.
 *
 * **Why a REDUCED scale, and why that costs nothing.** A per-unit cost needs two scales, not large ones, and the
 * kernel pays codegen per scenario plus 5x-8x the interpreter's time per evaluation — which is exactly why the
 * growth lanes went interpreter-only at N=5,000/20,000. The comparison is dimensionless (both engines run in the
 * same JVM on the same vCPUs in the same run), so a bar at a tenth of the scale decides it. Over the three
 * isolated runs of 2026-08-12 that carry the current code (`094119Z` and the two before it, `loadAverage` 2.8-7.1),
 * the margin is **2.5x or better on 32 of the 34 rows**, the median row sits at **8.50x**, and the tightest is
 * `CO-correlation @200` at **1.99x** with a run-to-run spread of its own ratio of **1.036x** — so the margin
 * exceeds the noise it has to survive, which it did not before the steady-state warm
 * ([PerfHarness.warmToSteadyState]). The two rows under 2.5x are that row and `CO-correlation @800` (2.49x); the
 * widest ratio spread in the set is 1.544x and the median 1.134x. The re-measurement of a comparative breach stays
 * behind all of it as the backstop (IF281, IF282). The high-N growth floors stay exactly
 * where they are: a reduced scale dilutes a quadratic term's share, so it is a weaker place to detect a blowup and
 * these lanes never replace those.
 *
 * <p><b>The WORST case, which a three-run basis cannot see (added 2026-08-12, {@code scripts/perf-trend.sh
 * --ratio} over the 27-run record).</b> The medians above hold — over 68 margin rows (the 34 scenario-scale rows
 * against each kernel strategy) the median margin is <b>7.80x</b> and 66 of 68 clear 2.5x. What the wider basis
 * adds is the low tail: by <em>worst observed</em> margin only <b>55 of 68</b> stay above 2.5x, and the tightest
 * row in the whole record is <b>{@code CO-correlation @200} vs generated Java at 1.35x</b> — still a pass, but
 * 35 % of headroom rather than the 99 % the median pairing suggests. The five tightest are both
 * {@code CO-correlation} scales, {@code complexRealWorld @60} and {@code Berlin-AddDays @250}, all against Java.
 *
 * <p><b>Do not "reconcile" the two spread figures — they are different statistics.</b> The `1.036x`/`1.134x`/
 * `1.544x` above are that basis's own dispersion measure; `perf-trend.sh --ratio` reports a MAD-based `±%` (median
 * ±19.7 %, widest ±35.3 %). Comparing them as if they were one quantity is the mistake KERNEL-FINDINGS' "name the
 * quantity before you compare numbers" rule exists to prevent. What is comparable across the two bases is the
 * margin itself.
 *
 * **These lanes are deliberately NOT anchored.** An anchor is an absolute-time bar against a committed number, and
 * it is the cross-engine ratio that matters here — a ratio needs no history, and adding 26 keys would make every
 * anchor refresh larger for nothing.
 */
@Tag("perf")
class KernelParityGateTest {

    @Test
    fun b1PerRowBeatsBothKernelStrategies() =
        assertBeatsBothKernelStrategies(PerfCatalog.b1(), small = 250)

    @Test
    fun berlinDateTimeBeatsBothKernelStrategies() =
        assertBeatsBothKernelStrategies(PerfCatalog.berlinDateTime(), small = 250)

    @Test
    fun parallelIterationBeatsBothKernelStrategies() =
        assertBeatsBothKernelStrategies(PerfCatalog.parallel(), small = 250)

    @Test
    fun tieredComputationBeatsBothKernelStrategies() =
        assertBeatsBothKernelStrategies(PerfCatalog.tieredComputation(), small = 250)

    @Test
    fun semanticIndexPerRowBeatsBothKernelStrategies() =
        assertBeatsBothKernelStrategies(PerfCatalog.semanticIndexPerRow(), small = 250)

    @Test
    fun rowInvariantAggregateBeatsBothKernelStrategies() =
        assertBeatsBothKernelStrategies(PerfCatalog.perRowGlobalAggregate(), small = 125)

    @Test
    fun deepNestedHavingBeatsBothKernelStrategies() =
        assertBeatsBothKernelStrategies(PerfCatalog.deepRich(firing = false), small = 60)

    @Test
    fun numberOfDifferentValuesBeatsBothKernelStrategies() =
        assertBeatsBothKernelStrategies(PerfCatalog.numDifferentValues(), small = 250)

    @Test
    fun fieldValuesNotUniqueBeatsBothKernelStrategies() =
        assertBeatsBothKernelStrategies(PerfCatalog.fieldValuesNotUniqueStar(), small = 250)

    @Test
    fun constrainedFieldsBeatBothKernelStrategies() =
        assertBeatsBothKernelStrategies(PerfCatalog.constrainedFields(), small = 250)

    @Test
    fun multiStarCorrelationBeatsBothKernelStrategies() =
        assertBeatsBothKernelStrategies(PerfCatalog.multiStarCorrelation(), small = 150)

    @Test
    fun parallelFramedBeatsBothKernelStrategies() =
        assertBeatsBothKernelStrategies(PerfCatalog.parallelFramed(), small = 250)

    @Test
    fun berlinAddDaysBeatsBothKernelStrategies() =
        assertBeatsBothKernelStrategies(PerfCatalog.berlinAddDays(), small = 250)

    @Test
    fun computeOverlaySemanticIndexBeatsBothKernelStrategies() =
        assertBeatsBothKernelStrategies(PerfCatalog.computeOverlaySemanticIndex(), small = 200)

    @Test
    fun computeOverlayCorrelationBeatsBothKernelStrategies() =
        assertBeatsBothKernelStrategies(PerfCatalog.computeOverlayCorrelation(), small = 200)

    /**
     * The combined multi-construct mix. The scenario is [PerfCatalog.complexRealWorldGate] — the reduced ~100-rule
     * shape whose kernel codegen is fast — rather than the ~1,000-rule `complexRealWorldFull` the growth lane
     * scales, because generating and compiling that model twice per lane is the cost the tier split exists to
     * avoid. The full model keeps its interpreter-only interaction-effect growth floor.
     */
    @Test
    fun complexRealWorldBeatsBothKernelStrategies() =
        assertBeatsBothKernelStrategies(PerfCatalog.complexRealWorldGate(firing = false), small = 60)

    /**
     * The breadth scenario, compared on the **row** axis at a fixed breadth.
     *
     * Its growth lane scales the number of sibling GROUPS, which is a property of the model rather than of the
     * instance, so each scale is a different model and a two-scale comparison of one engine pair cannot be
     * expressed that way. Scaling rows inside a fixed 50-group model asks the cross-engine question honestly on the
     * axis the harness can hold constant; the breadth axis keeps its interpreter-only growth floor, which is what
     * guards the per-group setup from scanning the whole model.
     */
    @Test
    fun wideSiblingGroupsBeatBothKernelStrategies() =
        assertBeatsBothKernelStrategies(PerfCatalog.wideGroups(50), small = 20)

    /**
     * Run one scenario's tri-engine curve and report it. Every bar lives in [PerfHarness.pairedScenarioGrowth];
     * the absolute ceiling is asserted here because that helper deliberately owns only the comparative bars.
     */
    private fun assertBeatsBothKernelStrategies(scenario: PerfScenario, small: Int, large: Int = small * 4) {
        val curve = PerfHarness.pairedScenarioGrowth(
            scenario, small = small, large = large, pairs = PAIRS, warmup = WARMUP, iters = ITERS,
            // Named here, not defaulted in the harness: it is what joins this lane's warm-outcome and cost rows to
            // the medians they qualify, and the same constant labels the observations below.
            lane = LANE,
        )
        println(PerfHarness.format(curve.small, CAVEAT))
        println(PerfHarness.format(curve.large, CAVEAT))
        for (row in listOf(curve.small, curve.large)) {
            assertTrue(row.interp.medianMs < ABS_CEILING_MS,
                "${row.scenario}: interpreter ${row.interp.medianMs}ms exceeds ${ABS_CEILING_MS}ms at N=${row.n}")
            record(scenario, row)
        }
    }

    /**
     * Put all THREE engines' medians in the committed record.
     *
     * Without this the family that now carries the estate's headline gate leaves no trace: a run wrote 36
     * interpreter-only growth observations and not one cross-engine row, so the ratios could not be compared
     * against their own history and IG121's cheapest unbuilt piece — an early-warning band on these very
     * ratios — had no series to read. These rows are recorded but NOT anchored: an anchor is an absolute-time
     * bar against a committed number, and what matters here is the ratio, which needs no history to be decided.
     */
    private fun record(scenario: PerfScenario, row: PerfRow) {
        val identity = "${row.identity.label} fingerprint=${row.identity.fingerprint}"
        for ((engine, stats) in listOf("interpreter" to row.interp, "groovy" to row.groovy, "java" to row.java)) {
            if (stats == null) continue
            PerfObservations.record(
                ObservationKey(lane = LANE, identityLabel = identity, n = row.n, engine = engine, clock = "wall"),
                stats.medianMs,
                status = "unasserted",
            )
        }
    }

    private companion object {
        /**
         * Three pairs keep the median of the within-pair ratios robust to one contended slice. Three warmup and
         * three timed iterations are what a **warm** instance needs; they are emphatically not enough to warm a
         * cold one, and the claim that they were is what the per-pair trace falsified — the Groovy validator at
         * N=800 was still 1.51x-1.70x above its settled cost after two full pairs. Reaching steady state is
         * [PerfHarness.warmToSteadyState]'s job now, paid once per instance instead of again by every pair, which
         * matters because every timed iteration here costs 5x-8x an interpreter one and this is the term that
         * decides whether the tier fits the routine gate. The Number-index lanes keep their own richer
         * `warmup=5, iters=5`: they were tuned for canonical decimal projection, not this sweep.
         */
        /** Single-sourced: the observations, the warm outcomes and the cost rows must share one lane label. */
        const val LANE = "kernel parity"

        const val PAIRS = 3
        const val WARMUP = 3
        const val ITERS = 3
    }
}
