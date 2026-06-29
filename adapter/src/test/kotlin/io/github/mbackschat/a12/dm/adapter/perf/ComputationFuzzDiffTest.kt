package io.github.mbackschat.a12.dm.adapter.perf

import io.github.mbackschat.a12.dm.adapter.laws.FuzzDiagnostics
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * **Harness F — the computation-cascade differential.** Where the validation fuzz nets ([ModelFuzzDiffTest],
 * [io.github.mbackschat.a12.dm.adapter.laws.FullMessageFuzzDiffTest]) never exercise `compute()`, this sweeps
 * random computation CHAINS ([ComputationFuzz]) and asserts the interpreter's `compute()` agrees with the real
 * kernel on every computed-field outcome (value / cleared). It is the standing guard for the cascade fix — a
 * dependent computation reading its dependency's COMPUTED value, not the stored-empty operand — which a single
 * hand-picked case (`ComputeCascadeDiffTest`) had been the *only* thing testing (that thinness is why the bug
 * survived). A divergence is reported via [FuzzDiagnostics.computeDivergence] (which `(model, doc)` seed + the
 * outcome signatures present in only one engine).
 */
@Tag("fuzz")
class ComputationFuzzDiffTest {

    @Test
    fun cascadedComputationsAgreeWithKernel() {
        val failures = buildList {
            for (m in 0 until MODELS) {
                val scenario = ComputationFuzz.model(m)
                for (d in 0 until DOCS) {
                    FuzzDiagnostics.computeDivergence(scenario, d)?.let { add("${scenario.name} :: $it") }
                }
            }
        }
        assertTrue(failures.isEmpty()) {
            "computation-cascade fuzz: interpreter diverged from the kernel on ${failures.size} (model, doc) pairs:\n" +
                failures.take(15).joinToString("\n")
        }
    }

    private companion object {
        const val MODELS = 40
        const val DOCS = 5
    }
}
