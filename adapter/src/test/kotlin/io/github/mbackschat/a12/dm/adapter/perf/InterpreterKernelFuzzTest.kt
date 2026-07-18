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
 * The generator is **kernel-free** (`:interpreter`), so this same generator backs the perf harness and the
 * `dmtool model seed` verb; the kernel half here is the `RuntimeLaws` oracle (so the test lives in `:adapter`). Each
 * seed is materialized into all THREE engines from the same generator instance — deterministic for a fixed seed,
 * so a failing seed is exactly reproducible (printed in the assertion).
 *
 * **Codegen tri-differential (WI-1):** the kernel side runs BOTH codegen strategies — Groovy-dynamic (the
 * production-normative parity anchor, S3) and static-JAVA ([KernelJavaPerfEngine]) — so a fired-code multiset
 * that agrees `interp == groovy == java-static` also gates the static codegen the runtime differential can't see.
 * (Its stronger `code|type|pointer` sibling is [io.github.mbackschat.a12.dm.adapter.laws.FullMessageFuzzDiffTest]
 * over the same model.)
 */
@Tag("fuzz")
class InterpreterKernelFuzzTest {

    @Test
    fun randomValidDocumentsAgreeWithKernel() {
        val scenario = PerfCatalog.fuzz()
        val interp = InterpreterPerfEngine.build(scenario)
        val kernel = KernelPerfEngine.build(scenario)           // Groovy-dynamic — the parity anchor
        val java = KernelJavaPerfEngine.build(scenario)         // static-JAVA codegen — WI-1's third engine
        // Collect ALL diverging seeds (not fail-fast) so one run shows every distinct mismatch to triage.
        val diverging = (0 until SEEDS).filter { seed ->
            val i = interp.instance(n = 0, variant = seed).run()
            val g = kernel.instance(n = 0, variant = seed).run()
            val j = java.instance(n = 0, variant = seed).run()
            i != g || g != j
        }
        assertTrue(diverging.isEmpty()) {
            // FuzzDiagnostics (harness B) localizes each to the (code @ pointer) pairs that diverge — naming WHICH
            // pair split (interp≠kernel = interpreter bug; kernel≠java-static = codegen artifact) — and shrinks
            // the first to a minimal failing document, so the failure names the exact row + code, not just codes.
            val scenario = PerfCatalog.fuzz()
            "interpreter / kernel Groovy-dynamic / kernel java-static diverged on ${diverging.size}/$SEEDS fuzz seeds:\n" +
                diverging.take(15).joinToString("\n") { FuzzDiagnostics.localize(scenario, it) } +
                "\n\n── shrunk first divergence ──\n" + FuzzDiagnostics.shrink(scenario, diverging.first())
        }
    }

    private companion object {
        const val SEEDS = 100
    }
}
