package io.github.mbackschat.a12.dm.adapter.perf

import io.github.mbackschat.a12.dm.adapter.laws.FuzzDiagnostics
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * The **differential fuzz net** (INTERPRETER-PERFORMANCE.md "model-driven seed generator"): the strongest
 * correctness gate for the #1 rule (match the kernel EXACTLY). For many random-but-VALID documents generated
 * from one model by the model-driven [PerfCatalog.fuzz] / `SeedGenerator`, assert the interpreter and the real
 * kernel produce the **identical** fired-code multiset. Where the hand-written `*DiffTest`s (in `laws/`) pin
 * one case each, this sweeps random *combinations* — varying row counts across nested groups, random fill/empty
 * patterns (exercising requiredness on empty index fields), aggregates over varying lists, a cross-level
 * reference — surfacing divergences no hand-picked case would.
 *
 * The generator is **kernel-free** (`:interpreter`), so this same generator backs the perf harness and a future
 * `dmtool seed` verb; the kernel half here is the `RuntimeLaws` oracle (so the test lives in `:adapter`). Each
 * seed is materialized into BOTH engines from the same generator instance — deterministic for a fixed seed, so
 * a failing seed is exactly reproducible (printed in the assertion).
 */
@Tag("fuzz")
class InterpreterKernelFuzzTest {

    @Test
    fun randomValidDocumentsAgreeWithKernel() {
        val scenario = PerfCatalog.fuzz()
        val interp = InterpreterPerfEngine.build(scenario)
        val kernel = KernelPerfEngine.build(scenario)
        // Collect ALL diverging seeds (not fail-fast) so one run shows every distinct mismatch to triage.
        val diverging = (0 until SEEDS).filter { seed ->
            interp.instance(n = 0, variant = seed).run() != kernel.instance(n = 0, variant = seed).run()
        }
        assertTrue(diverging.isEmpty()) {
            // FuzzDiagnostics (harness B) localizes each to the (code @ pointer) pairs that diverge, and shrinks
            // the first to a minimal failing document — so the failure names the exact row + code, not just codes.
            val scenario = PerfCatalog.fuzz()
            "interpreter diverged from the kernel on ${diverging.size}/$SEEDS fuzz seeds:\n" +
                diverging.take(15).joinToString("\n") { FuzzDiagnostics.localize(scenario, it) } +
                "\n\n── shrunk first divergence ──\n" + FuzzDiagnostics.shrink(scenario, diverging.first())
        }
    }

    private companion object {
        const val SEEDS = 100
    }
}
