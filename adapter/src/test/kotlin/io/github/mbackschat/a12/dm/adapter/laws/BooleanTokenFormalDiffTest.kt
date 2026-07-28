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
 * The token matrix is deliberately wider than the two codes: the case variants pin that this check is
 * case-**SENSITIVE** — `TRUE`, `FALSE`, and `True` are each a formal error here — and the German/numeric
 * spellings pin that nothing else is quietly accepted.
 *
 * (An earlier version of this comment claimed the matrix pinned an `equalsIgnoreCase` comparison. That belongs to
 * the **ingress converter**, a different layer, whose rows live in `A12JsonIngressKernelTest`; asserting it here
 * imported the mechanism this very test disproves for the stored-token layer. Caught by review — it is the same
 * layer-confusion IF229 exists to record, committed in the file that records it.)
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

    @Test
    fun theKernelsOwnVerdictsAreAlsoPinnedSoTheDOCUMENTEDFactCannotROT() {
        // The differential above asserts only kernel == interpreter, so if the kernel ever became case-INSENSITIVE
        // both sides would move together and it would still pass — while IF229's central corrected fact (this check
        // is case-sensitive) silently became false. Review caught that; these rows pin the kernel's own answers, so
        // a kernel change fails HERE and forces the finding to be re-read rather than rotting.
        val expected = mapOf(
            (BOOLEAN to "true") to emptyList<String>(),
            (BOOLEAN to "false") to emptyList(),
            (BOOLEAN to "TRUE") to listOf("feldJaNeinFalsch"),
            (BOOLEAN to "True") to listOf("feldJaNeinFalsch"),
            (BOOLEAN to "yes") to listOf("feldJaNeinFalsch"),
            (CONFIRM to "true") to emptyList(),
            (CONFIRM to "TRUE") to listOf("feldJaFalsch"),
            (CONFIRM to "false") to listOf("feldJaFalsch"),
        )

        for ((key, codes) in expected) {
            val (field, token) = key
            assertEquals(
                codes,
                RuntimeLaws.firedCodes(orderJson, setOf("/Order"), mapOf(field to token)).sorted(),
                "the KERNEL's verdict for $field=\"$token\" changed — re-read INTERPRETER-FINDINGS IF229",
            )
        }
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
