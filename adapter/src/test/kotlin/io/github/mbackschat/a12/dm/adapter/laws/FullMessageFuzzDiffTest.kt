package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.perf.PerfCatalog
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * **Harness D — the full-message differential.** The codes-only fuzz net ([io.github.mbackschat.a12.dm.adapter
 * .perf.InterpreterKernelFuzzTest]) gates on the fired-**code** multiset; this stronger gate sweeps the same
 * random-but-VALID documents and asserts the interpreter and the real kernel agree on the full message
 * **signature** `code|type|pointer` — so it also trips on a wrong **polarity** (VALUE↔OMISSION) or a right code
 * on the **wrong row**, which a code multiset hides. (It earned its keep immediately: it caught the
 * `CurrentRepetition`/`FirstFilledValue` comparisons being mis-typed OMISSION — KERNEL-FINDINGS §1 supplement.)
 *
 * Pure engine ([DmInterpreter.validateFull]) vs the [RuntimeLaws] kernel oracle, both materialized from the
 * same kernel-free generator; a diverging seed is reported via [FuzzDiagnostics.signatureDivergence].
 */
@Tag("fuzz")
class FullMessageFuzzDiffTest {

    @Test
    fun fullMessagesAgreeWithKernel() {
        val scenario = PerfCatalog.fuzz()
        val diverging = (0 until SEEDS).filterNot { FuzzDiagnostics.signaturesAgree(scenario, it) }
        assertTrue(diverging.isEmpty()) {
            "interpreter diverged from the kernel at the full-message level (code|type|pointer) on " +
                "${diverging.size}/$SEEDS fuzz seeds — a code-only gate would miss these:\n" +
                diverging.take(15).joinToString("\n") { FuzzDiagnostics.signatureDivergence(scenario, it) }
        }
    }

    private companion object {
        const val SEEDS = 200
    }
}
