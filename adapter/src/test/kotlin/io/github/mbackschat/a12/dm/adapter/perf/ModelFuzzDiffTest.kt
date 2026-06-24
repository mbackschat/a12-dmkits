package io.github.mbackschat.a12.dm.adapter.perf

import io.github.mbackschat.a12.dm.adapter.laws.FuzzDiagnostics
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **Harness E — model fuzzing.** The document fuzz ([FullMessageFuzzDiffTest]) varies *documents* over one
 * fixed model; this varies the **model** — [ModelFuzz] generates random-depth (2..4) nested repeatable models
 * (the deep-nesting structural space the fixed 2-level fuzz model can't reach) and, for each, asserts the
 * interpreter and the real kernel agree on the full message signature `code|type|pointer` ([FuzzDiagnostics
 * .signaturesAgree]) over fuzzed documents. Models are valid-by-construction (modelkit `toJson`); a divergence
 * is reported via [FuzzDiagnostics.signatureDivergence] (which `(model, doc)` seed + the diverging signatures).
 */
class ModelFuzzDiffTest {

    @Test
    fun nestedModelsAgreeWithKernel() {
        val failures = buildList {
            for (m in 0 until MODELS) {
                val scenario = ModelFuzz.model(m)
                for (d in 0 until DOCS) {
                    if (!FuzzDiagnostics.signaturesAgree(scenario, d)) {
                        add("${scenario.name} docSeed=$d :: " + FuzzDiagnostics.signatureDivergence(scenario, d))
                    }
                }
            }
        }
        assertTrue(failures.isEmpty()) {
            "model fuzz: interpreter diverged from the kernel on ${failures.size} (model, doc) pairs " +
                "at the full-message level:\n" + failures.take(15).joinToString("\n")
        }
    }

    private companion object {
        const val MODELS = 40
        const val DOCS = 5
    }
}
