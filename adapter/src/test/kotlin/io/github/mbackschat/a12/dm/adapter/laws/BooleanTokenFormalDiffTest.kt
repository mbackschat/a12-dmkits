package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — **the formal validity of a BOOLEAN / CONFIRM token** (KERNEL-FINDINGS §2 "Value-token
 * representation"; graduated from INTERPRETER-GAPS IG84).
 *
 * A boolean stores `true`/`false` and a confirm accepts only `true`; the Kernel reports `feldJaNeinFalsch` and
 * `feldJaFalsch` respectively for anything else. The interpreter had **no such check at all** — `formalError`'s
 * `Kind.BOOLEAN, Kind.CONFIRM -> null` arm returned clean — so a malformed token evaluated as `false` instead of
 * making the cell formally invalid (unknown, suppressing the referencing rule).
 *
 * This is the **internal** path: the document is built programmatically, because the gap is not an ingress
 * defect — it is equally absent when a caller fills a `Document` directly. The A12 Document-JSON ingress path is
 * covered by [A12JsonIngressDiffTest], whose characterized-residual set carried these rows until now.
 *
 * The token matrix is deliberately wider than the two codes: case variants pin that the Kernel's comparison is
 * `equalsIgnoreCase` on **both** tokens rather than only on `true` (the half the ledger had not measured), and
 * the German/numeric spellings pin that nothing else is quietly accepted.
 */
class BooleanTokenFormalDiffTest {

    private val orderJson = RuntimeLaws.model("models/order.dm.json")
    private val def by lazy { ModelLoader.load(orderJson) }

    @Test
    fun everyBooleanAndConfirmTokenAgreesWithTheKernelOnFormalValidity() {
        val divergences = MATRIX.mapNotNull { (field, token) ->
            val kernel = RuntimeLaws.firedCodes(orderJson, setOf("/Order"), mapOf(field to token)).sorted()

            val document = def.newDocument().field(field, token, 1, 1)
            val interpreter = DmInterpreter(def).validateFull(document).map { it.code }.sorted()

            if (kernel == interpreter) null else "$field=\"$token\" kernel=$kernel interp=$interpreter"
        }.toSet()

        assertEquals(emptySet<String>(), divergences, "BOOLEAN/CONFIRM token formal validity diverged")
    }

    private companion object {
        const val BOOLEAN = "/Order/ExpressShipping"
        const val CONFIRM = "/Order/TermsConfirmed"

        /** Every (field, authored token) pair probed against the real Kernel. */
        val MATRIX = listOf(
            // The two legal boolean tokens, then the case variants, then spellings a lenient parser might take.
            BOOLEAN to "true",
            BOOLEAN to "false",
            BOOLEAN to "TRUE",
            BOOLEAN to "FALSE",
            BOOLEAN to "True",
            BOOLEAN to "yes",
            BOOLEAN to "1",
            BOOLEAN to "0",
            BOOLEAN to "wahr",
            // A confirm has no false: only `true` is legal, so even a well-formed `false` is a formal error.
            CONFIRM to "true",
            CONFIRM to "TRUE",
            CONFIRM to "false",
            CONFIRM to "FALSE",
            CONFIRM to "yes",
            CONFIRM to "1",
        )
    }
}
