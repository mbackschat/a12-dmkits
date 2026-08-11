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
     * beating **both** kernel strategies at every scale with a per-unit cost no higher than either (IF279), and
     * `PerfHarness.requireInterpreterBeatsBoth` plus [PairedAcceptance] enforce those on the comparison path.
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

    /**
     * How far above its committed per-runner-class anchor an absolute median may sit before [PerfAnchors] trips.
     *
     * **This is the only bar that can see a UNIFORM regression** (IF280): a growth ratio is unchanged by a uniform
     * `k×` slowdown at every `k`, and the interp/kernel bars need 2.4×–3800×. Because the comparison is against the
     * same runner class's own number, the tolerance covers run-to-run noise only, not the 2.07×–2.69× drift between
     * environments that defeats every portable constant.
     *
     * **Measured basis (2026-08-11).** Across two isolated full gate runs of the same commit on one machine, the
     * CPU-clock growth-lane medians this budget guards moved **1.01×–1.16×** (`B1@5000` 8.3 → 9.6 ms is the widest).
     * The small *wall-clock* fixed-size rows moved up to **1.54×** (`Number-index-compute@500`: 3.04 → 2.39 →
     * 1.98 ms) — which is why the anchored set is the CPU-clock lanes and not those. `1.5` therefore sits well
     * above the observed noise of what it measures, a breach is re-measured and accepted on the median of the
     * attempts, and the `slackMs` term keeps a sub-2 ms lane (`wideGroups@50` anchors at ~1.1 ms) from flapping on
     * a fraction of a millisecond — the same shape as `CharsetPerfTest`'s `FACTOR × … + 50 ms`.
     *
     * Tighten it once several nightlies have shown the per-lane spread; never widen it to make a real regression
     * pass — refresh the anchor deliberately instead, which is a reviewable diff.
     */
    val ANCHOR_TOLERANCE = AnchorTolerance(factor = 1.5, slackMs = 1.0)

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
