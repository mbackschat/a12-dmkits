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
    // Codegen is NOT in the timed region (primed out by warmup — the kernel generates ahead). Both tiers
    // measure against Groovy (the shortcut); the Groovy execution tax (~1.0–1.3×, pinned by KernelEdgeTest)
    // makes this NOT apples-to-apples. (The interpreter now auto-validates the SAME declared field-type
    // constraints as the kernel — IF20, locked by FieldConstraintFormalDiffTest — so that former coverage gap
    // is closed and is no longer a fairness caveat.)
    const val CAVEAT = "(NOT apples-to-apples: codegen excluded; Groovy baseline + ~1.0–1.3× Groovy tax)"
}
