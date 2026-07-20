package io.github.mbackschat.a12.dm.adapter.perf

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/**
 * The interpreter's **two-goal** analysis (INTERPRETER-PERFORMANCE.md "the interpreter's two goals"):
 *  1. **Fast startup** — measure fresh interpreter construction through its first result. This class does not
 *     compare kernel generation/compilation because the benchmark contract places it ahead of request execution.
 *  2. **On-par at scale** — stays linear to large row counts (the #28 indexing + the per-group context memo).
 *
 * Opt-in (heavy), single-threaded analysis — not a gate:
 * ```
 * ./build.sh :adapter:test --tests "*PerfScalingTest" -Dperf.scaling=true
 * ```
 */
@EnabledIfSystemProperty(named = "perf.scaling", matches = "true")
class PerfScalingTest {

    /**
     * **Goal 2 — large documents.** B1 (per-row) interp vs Groovy at growing row counts, then interp-only to
     * 100k+ (Groovy is too slow to time there). The interp time should grow ~linearly with N.
     */
    @Test
    fun rowScaling() {
        val scenario = PerfCatalog.b1()
        println("[scaling] B1 per-row, interp vs groovy (the routine shortcut baseline):")
        for (n in listOf(1_000, 3_000, 10_000, 30_000)) {
            val row = PerfHarness.runScenario(scenario, n, withJava = false)
            println("  N=%6d  %s".format(n, PerfHarness.format(row)))
        }
        println("[scaling] interp-only at production scale (linearity check — no kernel, stays cheap):")
        for (n in listOf(30_000, 100_000, 300_000)) {
            val s = PerfHarness.timeInterpreter(scenario, n, warmup = 4, iters = 6)
            println("  %s N=%7d  interp=%.1fms (min %.1fms)  -> %.2f µs/row".format(
                PerfHarness.measurementLabel(scenario, n), n, s.medianMs, s.minMs, s.minMs * 1000 / n))
        }
    }

    /**
     * **Goal 1 — fast startup, measured on the interpreter's OWN terms.** The interpreter's startup is: build
     * the engine (lazy — just AST wrappers, no parse) + the first `validateFull` (one-time lazy index build +
     * first rule parse), after which it is steady-state. We report those in absolute terms.
     *
     * **We do NOT time kernel generation/compilation here** — the benchmark contract treats it as ahead-of-request
     * model preparation, not per-request latency. This method is interpreter-only and therefore cannot establish
     * an against-kernel startup ratio. The interpreter's not needing generated-validator preparation remains a
     * capability point (native image, one-shot CLI / JS), not a startup-timing race.
     */
    @Test
    fun startupCost() {
        val scenario = PerfCatalog.manyRules(48)
        val tEng = System.nanoTime()
        val eng = InterpreterPerfEngine.build(scenario)          // lazy — builds AST wrappers, no parse
        val buildMs = (System.nanoTime() - tEng) / 1e6
        val inst = eng.instance(48)                              // small doc, built OUTSIDE the timed region
        val coldMs = timeOnce { inst.run() }                     // first call: lazy index build + first parse + eval
        val warmMs = (0 until 8).minOf { timeOnce { inst.run() } }   // steady-state per call
        val measurement = PerfHarness.measurementLabel(scenario, 48)

        println("[startup] interpreter, 48-rule model (kernel preparation/compile is outside this interpreter-only measurement):")
        println("  %s engine build       = %.2f ms (lazy AST wrappers, no parse)".format(measurement, buildMs))
        println("  %s first validateFull = %.2f ms (one-time lazy index build + first parse)".format(measurement, coldMs))
        println("  %s steady-state/call  = %.2f ms".format(measurement, warmMs))
        println("  %s → one-time startup overhead ≈ %.2f ms; no generated-validator preparation"
            .format(measurement, buildMs + (coldMs - warmMs)))
    }

    /**
     * **Cold-startup spectrum — is it acceptable for ALL cases?** The truest cold latency: a FRESH engine + a
     * FRESH document + exactly ONE `validateFull`, no warmup (so JIT is cold too — the worst case). Swept along
     * both axes that could inflate a first call: rule count (the first-parse cost) and document size (the lazy
     * index build + cold eval). Reports absolute ms so the acceptability can be judged.
     */
    @Test
    fun coldStartupSpectrum() {
        println("[startup] cold first-call (fresh engine + fresh doc + ONE validateFull, NO warmup):")
        // Axis 1 — rule count, small document (~500 sub rows): the first-parse-of-N-rules cost.
        for (rules in listOf(6, 48, 192)) {
            val scenario = PerfCatalog.manyRules(rules)
            val ms = PerfHarness.freshRunMedianMillis(scenario, n = 100, repeats = 1)
            println("  %s rules=%-3d  small doc (100 Lines × 5)   cold = %.1f ms".format(
                PerfHarness.measurementLabel(scenario, 100), rules, ms))
        }
        // Axis 2 — document size, one rule: the lazy index build + cold (un-JIT-warmed) per-row eval.
        val scenario = PerfCatalog.b1()
        for (n in listOf(10_000, 100_000)) {
            val ms = PerfHarness.freshRunMedianMillis(scenario, n, repeats = 1)
            println("  %s 1 rule    %-7d rows              cold = %.1f ms".format(
                PerfHarness.measurementLabel(scenario, n), n, ms))
        }
    }

    private fun timeOnce(block: () -> Unit): Double {
        val t = System.nanoTime(); block(); return (System.nanoTime() - t) / 1e6
    }
}
