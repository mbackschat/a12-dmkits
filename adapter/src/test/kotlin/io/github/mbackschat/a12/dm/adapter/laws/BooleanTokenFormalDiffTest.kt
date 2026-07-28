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
 *
 * **The token set is `true`/`false` for EVERY A12 DocumentModel, including one that declares its own tokens.**
 * `BooleanType`/`ConfirmType` do carry `notInDCustomTrueValue`/`notInDCustomFalseValue` in DM-JSON, and a review
 * of the kernel's `FormatDefinitionJaNein` (which compares against configurable `jaWert`/`neinWert`) read that as
 * our hard-coded tokens being a refusal broader than its basis (raised as IG88 — the *second* gap to carry that
 * code, after IF151's — withdrawn 2026-07-29; IF229 holds the amendment). Measurement refuted it: those
 * keys are `@NotInD` — "ignored while generating the validation code" — and `TypeConverter.convertBoolean` gates
 * them behind `considerNotInD`, which every kernel caller leaves `false` (only the no-arg
 * `ExternalDocumentModelService()` is ever constructed), so codegen emits `FormatDefinitionJaNein("true","false")`
 * whatever the model says. The runtime override lane (`DarstellungsInformationIntern.getWahrWert()`) needs a
 * `PresentationInformation` that `DocumentProcessingConfig` has no channel for and that the md-runtime service
 * explicitly nulls ("the document values always use the canonical format", `LowKernelApiUtils`).
 * [tokensDeclaredByTheModelDoNotMoveTheFormalCheck] is that measurement, kept as the lock for IF229's scope.
 */
class BooleanTokenFormalDiffTest {

    private val orderJson = RuntimeLaws.model("models/order.dm.json")
    private val def by lazy { ModelLoader.load(orderJson) }

    /** A model whose BOOLEAN / CONFIRM fields declare non-default tokens (`Y`/`N`) in DM-JSON. */
    private val tokensJson = RuntimeLaws.model("models/return-tokens.dm.json")
    private val tokensDef by lazy { ModelLoader.load(tokensJson) }

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

    /**
     * The discriminating case for IG88: a model that DECLARES `Y`/`N` as its boolean tokens (and `Y` for the
     * confirm). IG88's mechanism predicts the kernel is clean on `Y` and fires on `true`; the measured mechanism
     * predicts no change at all. Both engines are asked, so this is a differential and a kernel pin at once.
     *
     * The fixture's tokens are proven to BIND first — a key the kernel's deserializer ignored would make every
     * verdict below agree for the uninteresting reason that the model never declared anything. (The first run of
     * this probe was exactly that vacuous: an injection that silently failed to match compared a model with
     * itself.)
     */
    @Test
    fun tokensDeclaredByTheModelDoNotMoveTheFormalCheck() {
        assertEquals(
            listOf("notInDCustomFalseValue=N", "notInDCustomTrueValue=Y", "notInDCustomTrueValue=Y"),
            Regex(""""(notInDCustom(?:True|False)Value)"\s*:\s*"([^"]*)"""")
                .findAll(RuntimeLaws.modelRoundTrip(tokensJson))
                .map { "${it.groupValues[1]}=${it.groupValues[2]}" }.sorted().toList(),
            "the fixture's declared tokens must survive A12's own model round trip — otherwise this test is vacuous",
        )
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(tokensJson),
            "the token-declaring fixture must be a CONSISTENT A12 model, not merely a parseable one")

        val expected = mapOf(
            // The DEFAULT tokens stay clean even though the model declares Y/N…
            (DECL_BOOLEAN to "true") to emptyList<String>(),
            (DECL_BOOLEAN to "false") to emptyList(),
            // …and the DECLARED tokens are formal errors, exactly as on a model that declares nothing.
            (DECL_BOOLEAN to "Y") to listOf("feldJaNeinFalsch"),
            (DECL_BOOLEAN to "N") to listOf("feldJaNeinFalsch"),
            (DECL_CONFIRM to "true") to emptyList(),
            (DECL_CONFIRM to "Y") to listOf("feldJaFalsch"),
            (DECL_CONFIRM to "false") to listOf("feldJaFalsch"),
        )

        val divergences = expected.mapNotNull { (key, kernelVerdict) ->
            val (field, token) = key
            val kernel = RuntimeLaws.firedCodes(tokensJson, setOf("/Return"), mapOf(field to token)).sorted()
            assertEquals(kernelVerdict, kernel,
                "the KERNEL's verdict for a model-DECLARED token changed at $field=\"$token\" — re-read IF229")

            val document = tokensDef.newDocument().field(field, token, 1, 1)
            val interpreter = DmInterpreter(tokensDef).validateFull(document).map { it.code }.sorted()
            if (kernel == interpreter) null else "$field=\"$token\" kernel=$kernel interp=$interpreter"
        }.toSet()

        assertEquals(emptySet<String>(), divergences, "declared-token formal validity diverged")
    }

    private companion object {
        const val BOOLEAN = "/Order/ExpressShipping"
        const val CONFIRM = "/Order/TermsConfirmed"
        const val DECL_BOOLEAN = "/Return/GiftWrapped"
        const val DECL_CONFIRM = "/Return/PolicyAccepted"

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
