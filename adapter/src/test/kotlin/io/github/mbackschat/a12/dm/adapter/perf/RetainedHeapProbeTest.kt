package io.github.mbackschat.a12.dm.adapter.perf

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.gen.asSink
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.math.ln

/**
 * NFR-F2 retained-footprint probe (IG79 → IF161) — the interpreter's runtime state must grow with the
 * model/document it accelerates and **reach a plateau**; it must not leak or duplicate document-wide state per
 * eval. This probe delivers the two pieces that need no owner promotion:
 *
 * - **The plateau / no-leak guard (a STRUCTURAL invariant, gated).** A prepared engine + document evaluated many
 *   times must reach a retained-memory plateau — the caches are owned by the engine / `Document`/index and keyed
 *   on that document, so repeated evaluation adds no retained state. A per-eval leak (an unbounded/global cache)
 *   would climb without bound; a generous bound over hundreds of evals catches it while tolerating GC noise. This
 *   is NFR-F2's "reaches a retained-memory plateau" invariant, not a candidate number.
 * - **The multi-scale retained-heap RECORDING (informative, NOT gated).** Retained heap at `N, 2N, 4N, 8N` with a
 *   fitted log-log space exponent, recorded for the record. It is **not** gated on the candidate `≤1.25` exponent /
 *   `≤8×` step: those bars, the ≥5 forked-process protocol, `--expose-gc` Node, and logical cache-entry counts are
 *   the owner-gated certification (IF137 six-point gate) — an in-process single-run measurement calibrates, it does
 *   not certify. `@Tag("perf")` (opt-in): a GC-sensitive measurement, run at footprint-relevant checkpoints.
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
    fun preparedEnginePlateausAcrossRepeatedEvaluation() {
        val sc = PerfCatalog.b1()
        val model = sc.interpModel
        val interp = DmInterpreter(model)                    // held live for the whole method → caches can't be GC'd
        val doc = model.newDocument().also { sc.instance(it.asSink(), N, 0) }
        repeat(WARM) { interp.validateFull(doc) }            // warm every lazy cache (index, parsed AST, row contexts)
        val warmed = retainedBytes()
        repeat(REPEATS) { interp.validateFull(doc) }         // reuse the SAME engine+doc: no new retained state if bounded
        val afterRepeats = retainedBytes()

        val growthMb = (afterRepeats - warmed) / 1e6
        println("[heap] plateau: warmed=%.1fMB → after %d evals=%.1fMB  Δ=%.1fMB (bound %.0fMB)"
            .format(warmed / 1e6, REPEATS, afterRepeats / 1e6, growthMb, PLATEAU_SLACK_MB))
        // Generous: a real per-eval leak over REPEATS evals is orders of magnitude past this; noise is not.
        assertTrue(growthMb < PLATEAU_SLACK_MB,
            "retained heap grew ${"%.1f".format(growthMb)}MB across $REPEATS repeated evaluations of the SAME " +
                "prepared engine+document — a cache is leaking / not owned by the document (NFR-F2 plateau breached)")
    }

    @Test
    fun retainedHeapAcrossScalesIsRecorded() {
        // Informative only (NOT a gate): a single in-process run calibrates; the ≤1.25 exponent / ≤8× step
        // certification needs ≥5 forked processes + the six-point gate (owner-gated, IF137).
        val scales = intArrayOf(N, N * 2, N * 4, N * 8)
        val retained = DoubleArray(scales.size)
        for ((i, n) in scales.withIndex()) {
            val sc = PerfCatalog.b1()
            val model = sc.interpModel
            val base = retainedBytes()
            val interp = DmInterpreter(model)
            val doc = model.newDocument().also { sc.instance(it.asSink(), n, 0) }
            repeat(WARM) { interp.validateFull(doc) }
            retained[i] = (retainedBytes() - base).toDouble().coerceAtLeast(1.0)
            println("[heap] N=$n retained≈%.1fMB".format(retained[i] / 1e6))
            // keep interp+doc reachable until measured, then let the next iteration reclaim them
            check(interp.validateFull(doc).size >= 0)
        }
        // Fit a log-log space exponent over the 4 scales (record only).
        val exponent = spaceExponent(scales, retained)
        println("[heap] fitted log-log space exponent ≈ %.2f (informative; certified bar ≤1.25 is owner-gated, IG79)".format(exponent))
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
        const val PLATEAU_SLACK_MB = 24.0   // generous: a real per-eval leak over 400 evals is far larger than GC noise
    }
}
