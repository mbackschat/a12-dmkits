package io.github.mbackschat.a12.dm.adapter.perf

import io.github.mbackschat.a12.dm.adapter.laws.RuntimeLaws
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/**
 * The interpreter's **two-goal** analysis (INTERPRETER-PERFORMANCE.md "the interpreter's two goals"):
 *  1. **Fast startup** — no code generation, so the *first* validation is cheap (the kernel pays codegen +
 *     Groovy compile on its first call; the interpreter pays only a lazy index build + first parse).
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
        println("[scaling] B1 per-row, interp vs groovy (the routine shortcut baseline):")
        for (n in listOf(1_000, 3_000, 10_000, 30_000)) {
            val row = PerfHarness.runScenario(PerfCatalog.b1(), n, withJava = false)
            println("  N=%6d  %s".format(n, PerfHarness.format(row)))
        }
        println("[scaling] interp-only at production scale (linearity check — no kernel, stays cheap):")
        for (n in listOf(30_000, 100_000, 300_000)) {
            val s = PerfHarness.timeInterpreter(PerfCatalog.b1(), n, warmup = 4, iters = 6)
            println("  N=%7d  interp=%.1fms (min %.1fms)  -> %.2f µs/row".format(n, s.medianMs, s.minMs, s.minMs * 1000 / n))
        }
    }

    /**
     * **Goal 1 — fast startup.** The kernel's FIRST validateFull pays codegen + Groovy compile; the interpreter's
     * first call pays only a lazy index build + first rule parse. Times the cold first call of each (a fresh
     * engine, one validateFull) on the 48-rule model — the interpreter should start far faster.
     */
    @Test
    fun startupCost() {
        val model = ruled48()
        val doc = smallDoc()

        // Kernel cold start: build the rt-service (deserialize+expand, no codegen yet), then the FIRST
        // validateFull triggers Groovy codegen + compile. That whole first call IS the kernel's startup cost.
        val kEng = RuntimeLaws.kernelEngine(model)
        val kBuilt = kEng.instance(doc)
        val kCold = timeOnce { kEng.firedCodesSorted(kBuilt) }

        // Interp cold start: build DmInterpreter (lazy — just AST wrappers) then the FIRST validateFull
        // (lazy index build + first parse of the touched rules).
        val scenario = PerfCatalog.manyRules(48)
        val tEng = System.nanoTime()
        val iEng = InterpreterPerfEngine.build(scenario)
        val iBuildMs = (System.nanoTime() - tEng) / 1e6
        val iInst = iEng.instance(48)   // small doc
        val iCold = timeOnce { iInst.run() }

        println("[startup] 48-rule model, cold first validateFull:")
        println("  kernel:  first call (incl. codegen+compile) = %.1f ms".format(kCold))
        println("  interp:  engine build = %.2f ms,  first call (index+parse) = %.2f ms".format(iBuildMs, iCold))
        println("  → interpreter startup is ~%.0f× cheaper (no codegen)".format(kCold / (iBuildMs + iCold)))
    }

    private fun ruled48(): String {
        // The same 48-rule shape the crossover sweep uses, but inline so this test owns its model.
        val base = RuntimeLaws.model("models/perf.dm.json")
        var m = base
        for (i in 0 until 48) {
            val code = "R%03d".format(i)
            m = when (i % 4) {
                0 -> RuntimeLaws.withRule(m, "/Perf/Lines", "R_$code", "../Qty", code, "FieldFilled(Qty) And [Qty] > ${50 + i}")
                1 -> RuntimeLaws.withRule(m, "/Perf/Lines", "R_$code", "../Amount", code, "FieldFilled(Qty) And FieldFilled(Amount) And [Amount] > [Qty]")
                2 -> RuntimeLaws.withRule(m, "/Perf/Lines/Subs", "R_$code", "../Cost", code, "FieldFilled(Cost) And [Cost] > ${40 + (i % 50)}")
                else -> RuntimeLaws.withRule(m, "/Perf", "R_$code", "../Lines/Amount", code, "Sum(Lines*/Amount) > ${1000 + i}")
            }
        }
        return m
    }

    private fun smallDoc(): RuntimeLaws.Doc {
        val doc = RuntimeLaws.doc()
        for (i in 1..20) {
            doc.group("/Perf/Lines", 1, i)
            doc.field("/Perf/Lines/LineKey", "K$i", 1, i, 1)
            doc.field("/Perf/Lines/Qty", (i * 5).toString(), 1, i, 1)
            doc.field("/Perf/Lines/Amount", (i * 3).toString(), 1, i, 1)
        }
        return doc
    }

    private fun timeOnce(block: () -> Unit): Double {
        val t = System.nanoTime(); block(); return (System.nanoTime() - t) / 1e6
    }
}
