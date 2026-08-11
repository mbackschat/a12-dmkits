package io.github.mbackschat.a12.dm.adapter.perf

/** The two scales a paired estimator compares. A step with `large <= small` has no growth to measure. */
data class ScaleStep(val small: Int, val large: Int) {
    init {
        require(small > 0) { "the small scale must be positive: small=$small" }
        require(large > small) { "the large scale must exceed the small one: small=$small large=$large" }
    }

    /** The scale units the step spans — the denominator of a per-unit cost. */
    val units: Int get() = large - small
}

/**
 * One engine's paired curve: its name plus the [Growth] the estimator produced for it.
 *
 * [perUnitMs] is the quantity the acceptance rule compares. Decomposing `time = fixed + perUnit·N`, it is the
 * slope — what each additional row/value/line costs this engine — while [Growth.ratio] mixes that slope with the
 * engine's fixed per-call cost and therefore cannot be compared ACROSS engines (IF205, IF279).
 */
data class EngineCurve(val engine: String, val growth: Growth) {
    fun perUnitMs(step: ScaleStep): Double = (growth.largeMs - growth.smallMs) / step.units
}

/**
 * The cross-engine acceptance rule of [PerfHarness.pairedScenarioGrowth], separated from the clock so the rule
 * itself is checked on scripted numbers ([PairedAcceptanceTest]) rather than only by a timed run.
 *
 * **Why per-unit cost and not the N/4N growth ratio (IF279).** For an engine that is linear over the step, the
 * measured ratio is exactly `4 - 3f` at a 4× step, where `f` is the fixed cost's share of the small-scale
 * measurement. So the ratio ranks engines by fixed-cost share, not by scaling: a heavy engine (Groovy, `f≈17%`
 * on a hosted runner) measures `≈3.5×` while a lean one (`f≈2%`) measures `≈3.94×`, and demanding that the
 * interpreter's ratio be no worse than both kernels' demands that the interpreter carry the LARGEST fixed cost
 * of the three. That inverts the goal, cannot be satisfied by scaling better, and failed the nightly gate on
 * 2026-08-11 with the interpreter ~9.6× cheaper per row than generated Java. IF205 had already established the
 * dilution mechanism on the synthesized depth lane and parked the guard change; this is that change.
 *
 * Per-unit cost is scale-free and fixed-cost-free, so it compares across engines. It does NOT detect a
 * catastrophic blowup on its own — the interpreter here has ~10× of per-unit headroom over the kernel, so a
 * quadratic interpreter would still hold the lowest slope at these scales. That is the separate, self-referential
 * job of [PerfHarness.assertGrowthBelowCatastropheFloor], which `pairedScenarioGrowth` applies to the
 * interpreter's OWN curve.
 */
internal object PairedAcceptance {

    /**
     * Refuse a run in which the interpreter does not have the lowest per-unit cost of the measured engines.
     *
     * A non-positive or non-finite slope means the engine did not measurably grow with the scale, which is a
     * broken measurement rather than a fast engine, so it is refused loudly instead of silently skipped.
     */
    fun requireInterpreterHasLowestPerUnitCost(
        scenario: String,
        step: ScaleStep,
        interpreter: EngineCurve,
        kernels: List<EngineCurve>,
    ) {
        require(kernels.isNotEmpty()) { "$scenario: a cross-engine acceptance needs at least one kernel curve" }
        val curves = listOf(interpreter) + kernels
        val degenerate = curves.filterNot { it.perUnitMs(step).let { c -> c.isFinite() && c > 0.0 } }
        check(degenerate.isEmpty()) {
            "$scenario: refusing to compare per-unit cost over ${step.small}→${step.large} — " +
                degenerate.joinToString(", ") { render(it, step) } +
                " did not measurably grow with the scale, so the measurement is degenerate, not fast."
        }
        val slower = kernels.filter { interpreter.perUnitMs(step) > it.perUnitMs(step) }
        check(slower.isEmpty()) {
            "$scenario: the interpreter must cost no more PER SCALE UNIT than every kernel strategy over " +
                "${step.small}→${step.large}, but it is above ${slower.joinToString(prefix = "[", postfix = "]") { it.engine }}: " +
                curves.joinToString("; ") { render(it, step) } +
                ". Per-unit cost is the comparable quantity; a raw N/4N growth ratio is not, because it ranks " +
                "engines by fixed-cost share (IF279)."
        }
    }

    /** [java.util.Locale.ROOT], so a refusal reads the same on a German JVM as on an English one. */
    private fun render(curve: EngineCurve, step: ScaleStep): String =
        "%s %.4fms/unit (%.3fms@%d → %.3fms@%d, ratio %.2f×)".format(
            java.util.Locale.ROOT,
            curve.engine, curve.perUnitMs(step),
            curve.growth.smallMs, step.small, curve.growth.largeMs, step.large, curve.growth.ratio,
        )
}
