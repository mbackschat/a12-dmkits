package io.github.mbackschat.a12.dm.adapter.perf

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.gen.asSink
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/**
 * **Profiling driver for the compute-overlay scenarios** — the COMPUTE sibling of [EvalProfileTest], which loops
 * `validateFull` and so cannot reach this path at all.
 *
 * `CO-correlation` is the estate's tightest cross-engine row: the interpreter beats generated Java by 1.97x at
 * N=200, against 2.5x-50x everywhere else. This driver exists to find out why. Opt-in, asserts nothing.
 *
 * ```
 * ./build.sh :adapter:test --tests "*ComputeOverlayProfileTest" -PrunPerf -Dperf.profile=true \
 *     -Dperf.n=800 -Dperf.jfr=$PWD/build/co.jfr
 * jfr print --events jdk.ExecutionSample --stack-depth 40 build/co.jfr
 * ```
 */
@EnabledIfSystemProperty(named = "perf.profile", matches = "true")
class ComputeOverlayProfileTest {

    private val n: Int get() = System.getProperty("perf.n", "800").toInt()

    @Test
    fun loopCorrelationForProfiler() = profile(PerfCatalog.computeOverlayCorrelation(), "CO-correlation")

    @Test
    fun loopIndexForProfiler() = profile(PerfCatalog.computeOverlaySemanticIndex(), "CO-index")

    private fun profile(scenario: PerfScenario, label: String) {
        val model = scenario.interpModel
        val doc = model.newDocument()
        scenario.instance(doc.asSink(), n, 0)
        val interp = DmInterpreter(model)
        repeat(20) { interp.compute(doc, scenario.locale) }
        val deadline = System.nanoTime() + SECONDS * 1_000_000_000L
        var loops = 0
        while (System.nanoTime() < deadline) { interp.compute(doc, scenario.locale); loops++ }
        println("[profile] $label @ N=$n: $loops compute calls in ~${SECONDS}s " +
            "(${"%.2f".format(SECONDS * 1000.0 / loops)} ms/call)")
    }

    private companion object { const val SECONDS = 20 }
}
