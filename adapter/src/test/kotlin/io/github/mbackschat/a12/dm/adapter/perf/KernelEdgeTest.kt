package io.github.mbackschat.a12.dm.adapter.perf

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/**
 * **Where the kernel has the EDGE** (INTERPRETER-PERFORMANCE.md "where the kernel wins"). The kernel team's
 * pride is well-founded: its architecture is **compile-once codegen + per-run caches** (`IndexFieldCache`,
 * `ValidationCache`, `CalculationCache`, `GroupFillCache`, `RepetitionNotUniqueErrorCache`), so its advantage
 * shows in the **many-rules** and **batch (repeated-eval)** regimes — exactly where the interpreter's per-call
 * rule re-parse (the #31 "prepare once" cost) accumulates. This opt-in test HUNTS the crossover (interp/java
 * → 1 and beyond) rather than gating it; it is the proof-of-kernel-edge measurement for the analysis log.
 *
 * ```
 * ./build.sh :adapter:test --tests "*KernelEdgeTest" -Dperf.edge=true [-Dperf.edge.n=1000]
 * ```
 * All comparisons use **interp/kernel-Java** (the Groovy-tax-free fair ratio). Correctness (interp == groovy
 * == java) is asserted inside the harness before any timing.
 */
@EnabledIfSystemProperty(named = "perf.edge", matches = "true")
class KernelEdgeTest {

    private val n: Int get() = System.getProperty("perf.edge.n", "1000").toInt()

    /**
     * Rule-count sweep at fixed document size: as the rule count grows, the interpreter re-parses ALL rules on
     * every `validateFull` call while the kernel runs already-generated code — so interp/kernel-Java climbs
     * toward (and past) 1.0. The crossover is the kernel's compile-once edge, made visible.
     */
    @Test
    fun ruleCountCrossover() {
        println("[edge] rule-count sweep @ N=$n Lines (×5 Subs), single document (interp/java is the fair ratio):")
        for (count in listOf(6, 12, 24, 48, 96, 192)) {
            val row = PerfHarness.runScenario(PerfCatalog.manyRules(count), n)
            println("  rules=%3d  %s".format(count, PerfHarness.format(row)))
        }
    }

    /**
     * **Batch validation** (the user's explicit ask): the engine built ONCE, M slightly-varying documents
     * looped — the form-input / batch pattern the kernel's codegen + per-run caches are tuned for. Per-eval
     * interp vs both kernel baselines, swept over rule count so the kernel's batch advantage is visible.
     */
    @Test
    fun manyRulesBatch() {
        println("[edge] BATCH (repeated-eval): engine built once, 20 varying docs looped, per-eval:")
        for (count in listOf(12, 48, 96)) {
            val row = PerfHarness.runRepeated(PerfCatalog.manyRules(count), n = n / 2, variants = 20)
            println("  rules=%3d  %s".format(count, PerfHarness.formatRepeated(row)))
        }
    }
}
