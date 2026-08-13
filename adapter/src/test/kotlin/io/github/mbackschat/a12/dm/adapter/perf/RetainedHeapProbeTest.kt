package io.github.mbackschat.a12.dm.adapter.perf

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.gen.asSink
import java.util.Locale
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * NFR-F2 retained-footprint calibration (IG79 → IF161) — the interpreter's runtime state must grow with the
 * model/document it accelerates and **reach a plateau**; it must not leak or duplicate document-wide state per
 * eval. This in-process probe retains one diagnostic view but does not prove that contract:
 *
 * - **The same-owner endpoint smoke (diagnostic assertion).** One prepared engine + document is sampled after
 *   warmup and after many more evaluations. The generous slack catches catastrophic growth in this one shape,
 *   but two endpoints cannot prove a plateau, detect a small per-eval leak, or detect a global cache retaining
 *   discarded owners; reachability and GC noise also remain uncontrolled.
 * The sound multi-scale JVM/Node measurement is the independently forked `interpreterFootprint` lane (IF162).
 * `@Tag("perf")` (opt-in): this GC-sensitive endpoint smoke runs only at relevant checkpoints.
 */
@Tag("perf-bench")
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
            .format(Locale.ROOT, 
                PerfHarness.measurementLabel(sc, N),
                warmed / 1e6, REPEATS, afterRepeats / 1e6, growthMb, ENDPOINT_SLACK_MB,
            ))
        // Diagnostic only: this catches gross endpoint growth, not bounded slope, owner release, or leak freedom.
        assertTrue(growthMb < ENDPOINT_SLACK_MB,
            "retained heap grew ${"%.1f".format(Locale.ROOT, growthMb)}MB across $REPEATS repeated evaluations of the SAME " +
                "prepared engine+document — the diagnostic endpoint slack was exceeded; investigate ownership/GC")
    }

    private companion object {
        const val N = 4_000
        const val WARM = 10
        const val REPEATS = 400
        const val ENDPOINT_SLACK_MB = 24.0
    }
}
