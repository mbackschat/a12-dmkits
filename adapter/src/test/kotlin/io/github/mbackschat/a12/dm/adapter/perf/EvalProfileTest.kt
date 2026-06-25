package io.github.mbackschat.a12.dm.adapter.perf

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.gen.asSink
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/**
 * **Profiling driver** (INTERPRETER-PERFORMANCE.md #64). Runs `validateFull` on the low-firing 192-rule
 * scenario (the only spot still above the kernel) in a tight loop for ~[SECONDS]s so a sampling profiler gets
 * dense samples — to attribute `validateFull`'s cost (eval / `firedType` vs the shared machinery:
 * `iterationContexts`, the per-(rule,row) `Interpreter` alloc, the suppressor scan, `Document` lookups). Run:
 * ```
 * ./build.sh :adapter:test --tests "*EvalProfileTest" -Dperf.profile=true -Dperf.jfr=<abs>/prof.jfr
 * jfr print --events jdk.ExecutionSample --stack-depth 30 <abs>/prof.jfr   # then aggregate the hot frames
 * ```
 * It is opt-in (`perf.profile`) and asserts nothing — it exists to feed the profiler, not to gate.
 */
@EnabledIfSystemProperty(named = "perf.profile", matches = "true")
class EvalProfileTest {

    @Test
    fun loopLowFiring192ForProfiler() = profile(PerfCatalog.manyRules(192, firing = false),
        System.getProperty("perf.n", "1000").toInt(), "low-firing 192")

    /** Profile the DEEP+RICH scenario (the one that exposed a super-linear cliff) at `-Dperf.n` vendors. */
    @Test
    fun loopDeepRichForProfiler() = profile(PerfCatalog.deepRich(firing = false),
        System.getProperty("perf.n", "500").toInt(), "deepRich")

    private fun profile(scenario: io.github.mbackschat.a12.dm.adapter.perf.PerfScenario, n: Int, label: String) {
        val model = scenario.interpModel
        val doc = Document(model.kinds, model.declaredReps, model.unsignedFields, model.rangeConfig, model.dateFormats, model.scales)
        scenario.instance(doc.asSink(), n, 0)
        val interp = DmInterpreter(model)
        repeat(20) { interp.validateFull(doc) }   // warm + let JFR start sampling
        val deadline = System.nanoTime() + SECONDS * 1_000_000_000L
        var loops = 0
        while (System.nanoTime() < deadline) { interp.validateFull(doc); loops++ }
        println("[profile] $label @ N=$n: $loops validateFull calls in ~${SECONDS}s")
    }

    private companion object { const val SECONDS = 20 }
}
