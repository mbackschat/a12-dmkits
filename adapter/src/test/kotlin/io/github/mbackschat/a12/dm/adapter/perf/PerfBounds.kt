package io.github.mbackschat.a12.dm.adapter.perf

/**
 * The bounds + fairness caveat shared by the two perf tiers — [PerfGateTest] (the routine gate: the
 * sub-quadratic growth guards + the rich-model kernel canary) and [PerfBenchTest] (the kernel-ratio
 * benchmarks). One source so the two tiers trip identically. The tier split exists so the routine gate stays
 * fast and well under an agent harness's ~10-minute background-command cap
 * (INTERPRETER-PERFORMANCE.md "The regression gate (mechanics)").
 */
internal object PerfBounds {
    const val ABS_CEILING_MS = 5_000.0
    const val RATIO_CEILING = 5.0
    const val N_SMALL = 5_000
    const val GROWTH_PAIRS = 5          // IF137 paired estimator: interleaved N/4N pairs, median of within-pair ratios
    const val SUB_QUADRATIC_MAX = 9.0   // linear ≈ 4; a quadratic 4×-rows blowup is ≈ 16 — 9 is the trip line

    /**
     * **A deliberately CONSERVATIVE floor (owner, 2026-08-07), not a target: higher is always better.** The
     * entry-point guards ([KernelAdvantage]) trip below it. Measured headroom is never licence to regress
     * toward it — the entries it guards run 3.2×–16× and are expected to stay there; a drop to 1.6× would pass
     * this floor and still be a performance defect under the standing development rule.
     *
     * **Never relax a stricter stated limit to this number.** It is the weakest of three and supersedes none:
     * the standing development acceptance rule (INTERPRETER-PERFORMANCE.md "Performance contract") requires
     * beating **both** kernel strategies at every scale with growth no worse than either, and
     * `PerfHarness.requireInterpreterBeatsBoth` enforces that on the comparison path.
     *
     * **What it is measured against:** dynamic Groovy. The guards drive `RuntimeLaws.kernelEngine`, so this
     * says nothing on its own about generated Java, which is the faster strategy (the ~1.0–1.3× Groovy tax in
     * [CAVEAT]). "Beat both strategies" stays the governing requirement; this floor is one strategy's trip line.
     *
     * Polarity, because it is the inverse of its neighbour: this is kernel/interp, so higher is better and 1.5
     * is a minimum, while [RATIO_CEILING] is interp/kernel and loose (5.0 tolerates the interpreter being 5×
     * SLOWER) — a backstop against a large relative regression that never expressed a target.
     */
    const val MIN_KERNEL_ADVANTAGE = 1.5

    /** Near-linear: an entry point's growth may exceed the row factor by this much before it reads super-linear.
     *  STRICTER than [SUB_QUADRATIC_MAX] by construction (5.4 at a 4× factor, against that 9.0 trip line) — a
     *  linear entry point has no reason to need the wider catastrophe band, so do not harmonize it upward. */
    const val NEAR_LINEAR_SLACK = 1.35
    // Codegen is NOT in the timed region (primed out by warmup — the kernel generates ahead). Both tiers
    // measure against Groovy (the shortcut); the Groovy execution tax (~1.0–1.3×, pinned by KernelEdgeTest)
    // makes this NOT apples-to-apples. (The interpreter now auto-validates the SAME declared field-type
    // constraints as the kernel — IF20, locked by FieldConstraintFormalDiffTest — so that former coverage gap
    // is closed and is no longer a fairness caveat.)
    const val CAVEAT = "(NOT apples-to-apples: codegen excluded; Groovy baseline + ~1.0–1.3× Groovy tax)"
}
