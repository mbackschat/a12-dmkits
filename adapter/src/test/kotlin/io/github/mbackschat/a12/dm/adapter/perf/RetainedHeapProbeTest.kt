package io.github.mbackschat.a12.dm.adapter.perf

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.gen.asSink
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.math.ln

/**
 * NFR-F2 retained-footprint calibration (IG79 → IF161) — the interpreter's runtime state must grow with the
 * model/document it accelerates and **reach a plateau**; it must not leak or duplicate document-wide state per
 * eval. This in-process probe records two diagnostic views but does not prove that contract:
 *
 * - **The same-owner endpoint smoke (diagnostic assertion).** One prepared engine + document is sampled after
 *   warmup and after many more evaluations. The generous slack catches catastrophic growth in this one shape,
 *   but two endpoints cannot prove a plateau, detect a small per-eval leak, or detect a global cache retaining
 *   discarded owners; reachability and GC noise also remain uncontrolled.
 * - **The multi-scale retained-heap RECORDING (informative, NOT gated).** Retained heap at `N, 2N, 4N, 8N` with a
 *   fitted log-log space exponent, recorded for the record. It is **not** gated on the candidate `≤1.25` exponent /
 *   `≤8×` step. Building the forked JVM/Node workers, `--expose-gc` checks, and logical cache counters is active
 *   IG79 work; only promoting a numerical bar and CI scheduling require owner approval. This single run calibrates,
 *   it does not certify. `@Tag("perf")` (opt-in): a GC-sensitive measurement, run at relevant checkpoints.
 */
@Tag("perf")
class RetainedHeapProbeTest {

    /** Post-GC used heap (bytes): settle allocations so a leak (retained) is distinguishable from transient churn. */
    private fun retainedBytes(): Long {
        repeat(4) { System.gc(); runCatching { Thread.sleep(30) } }
        val rt = Runtime.getRuntime()
        return rt.totalMemory() - rt.freeMemory()
    }

    @Test
    fun sameOwnerRetainedEndpointsStayWithinDiagnosticSlack() {
        val sc = PerfCatalog.b1()
        val model = sc.interpModel
        val interp = DmInterpreter(model)                    // held live for the whole method → caches can't be GC'd
        val doc = model.newDocument().also { sc.instance(it.asSink(), N, 0) }
        repeat(WARM) { interp.validateFull(doc) }            // warm every lazy cache (index, parsed AST, row contexts)
        val warmed = retainedBytes()
        repeat(REPEATS) { interp.validateFull(doc) }         // reuse the SAME engine+doc: no new retained state if bounded
        val afterRepeats = retainedBytes()

        val growthMb = (afterRepeats - warmed) / 1e6
        println("[heap] %s same-owner endpoints: warmed=%.1fMB → after %d evals=%.1fMB  Δ=%.1fMB (diagnostic slack %.0fMB)"
            .format(
                PerfHarness.measurementLabel(sc, N),
                warmed / 1e6, REPEATS, afterRepeats / 1e6, growthMb, ENDPOINT_SLACK_MB,
            ))
        // Diagnostic only: this catches gross endpoint growth, not bounded slope, owner release, or leak freedom.
        assertTrue(growthMb < ENDPOINT_SLACK_MB,
            "retained heap grew ${"%.1f".format(growthMb)}MB across $REPEATS repeated evaluations of the SAME " +
                "prepared engine+document — the diagnostic endpoint slack was exceeded; investigate ownership/GC")
    }

    @Test
    fun retainedHeapAcrossScalesIsRecorded() {
        // Informative only (NOT a gate): a single in-process run calibrates. The active IG79 tooling still needs
        // independently forked JVM/Node workers; only promotion of the candidate bars is owner-gated (IF137).
        val sc = PerfCatalog.b1()
        val scales = intArrayOf(N, N * 2, N * 4, N * 8)
        val retained = DoubleArray(scales.size)
        for ((i, n) in scales.withIndex()) {
            val model = sc.interpModel
            val base = retainedBytes()
            val interp = DmInterpreter(model)
            val doc = model.newDocument().also { sc.instance(it.asSink(), n, 0) }
            repeat(WARM) { interp.validateFull(doc) }
            retained[i] = (retainedBytes() - base).toDouble().coerceAtLeast(1.0)
            println("[heap] %s N=$n retained≈%.1fMB".format(
                PerfHarness.measurementLabel(sc, n), retained[i] / 1e6))
            // keep interp+doc reachable until measured, then let the next iteration reclaim them
            check(interp.validateFull(doc).size >= 0)
        }
        // Fit a log-log space exponent over the 4 scales (record only).
        val exponent = spaceExponent(scales, retained)
        val identities = scales.joinToString(" | ") { PerfHarness.measurementLabel(sc, it) }
        println(("[heap] $identities fitted log-log space exponent ≈ %.2f " +
            "(informative; promoting the candidate ≤1.25 bar requires owner approval, IG79)").format(exponent))
    }

    /** Least-squares slope of ln(retained) vs ln(n) — the space-complexity exponent (1 = linear). */
    private fun spaceExponent(scales: IntArray, retained: DoubleArray): Double {
        val xs = scales.map { ln(it.toDouble()) }
        val ys = retained.map { ln(it) }
        val mx = xs.average(); val my = ys.average()
        val num = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) }
        val den = xs.sumOf { (it - mx) * (it - mx) }
        return if (den == 0.0) 0.0 else num / den
    }

    private companion object {
        const val N = 4_000
        const val WARM = 10
        const val REPEATS = 400
        const val ENDPOINT_SLACK_MB = 24.0
    }
}
