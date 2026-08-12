package io.github.mbackschat.a12.dm.adapter.perf

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.gen.asSink
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/**
 * **Profiling driver** (INTERPRETER-PERFORMANCE.md #64). Runs `validateFull` on the low-firing rule
 * scenario in a tight loop for ~[SECONDS]s so a sampling profiler gets
 * dense samples — to attribute `validateFull`'s cost (eval / `firedType` vs the shared machinery:
 * `iterationContexts`, the per-(rule,row) `Interpreter` alloc, the suppressor scan, `Document` lookups). Run:
 * ```
 * ./build.sh :adapter:test --tests "*EvalProfileTest" -Dperf.profile=true -Dperf.rules=384 -Dperf.jfr=<abs>/prof.jfr
 * jfr print --events jdk.ExecutionSample --stack-depth 30 <abs>/prof.jfr   # then aggregate the hot frames
 * ```
 * It is opt-in (`perf.profile`) and asserts nothing — it exists to feed the profiler, not to gate.
 */
@EnabledIfSystemProperty(named = "perf.profile", matches = "true")
class EvalProfileTest {

    @Test
    fun loopLowFiringForProfiler() {
        val rules = System.getProperty("perf.rules", "192").toInt()
        profile(
            PerfCatalog.manyRules(rules, firing = false),
            System.getProperty("perf.n", "1000").toInt(),
            "low-firing $rules",
        )
    }

    /** Profile the DEEP+RICH scenario (the one that exposed a super-linear cliff) at `-Dperf.n` vendors. */
    @Test
    fun loopDeepRichForProfiler() = profile(PerfCatalog.deepRich(firing = false),
        System.getProperty("perf.n", "500").toInt(), "deepRich")

    /**
     * The two thinnest VALIDATE rows against the kernel, so a hot path found on one scenario can be checked for
     * being shared before it is optimized. `CO-correlation` is thinner still but is a COMPUTE scenario, which this
     * driver cannot reach — [ComputeOverlayProfileTest] is its sibling.
     */
    @Test
    fun loopParallelFramedForProfiler() = profile(PerfCatalog.parallelFramed(),
        System.getProperty("perf.n", "1000").toInt(), "PF-framed")

    @Test
    fun loopComplexRealWorldForProfiler() = profile(PerfCatalog.complexRealWorldGate(firing = false),
        System.getProperty("perf.n", "240").toInt(), "complexRealWorld")

    private fun profile(scenario: io.github.mbackschat.a12.dm.adapter.perf.PerfScenario, n: Int, label: String) {
        val measurement = PerfHarness.measurementLabel(scenario, n)
        val model = scenario.interpModel
        val doc = model.newDocument()
        scenario.instance(doc.asSink(), n, 0)
        val interp = DmInterpreter(model)
        repeat(20) { interp.validateFull(doc) }   // warm + let JFR start sampling
        val deadline = System.nanoTime() + SECONDS * 1_000_000_000L
        var loops = 0
        while (System.nanoTime() < deadline) { interp.validateFull(doc); loops++ }
        println("[profile] $measurement $label @ N=$n: $loops validateFull calls in ~${SECONDS}s")
    }

    private companion object { const val SECONDS = 20 }
}
