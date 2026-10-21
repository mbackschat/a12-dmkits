package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * B2's first phase separation: admission is not a consistency verdict.
 *
 * The Java seam this replaces folds the two together — `ModelLoader.loadAndCheck` turns "the backend could
 * not BUILD the model" into a `Notification` on the same channel as a consistency error, so a caller cannot
 * tell an unreadable source from an invalid declaration. A checker that must diagnose invalid input has to
 * keep them apart, which is why the outcome type is a sum rather than a diagnostic list.
 */
class AdmissionPhaseTest {

    @Test
    fun malformedSourceIsRejectedAtAdmission() {
        val outcome = admit(ModelSource("{ not json"))

        val rejected = assertIs<Admission.Rejected>(outcome)
        assertEquals(DiagnosticCode.SOURCE_NOT_WELL_FORMED, rejected.diagnostic.code)
        assertEquals(null, rejected.diagnostic.modelId, "an unreadable source cannot name the model it is")
    }

    /**
     * Identity is EXTRACTED from `header.id`, never asserted by the caller: A12 puts the model's identity
     * there and every cross-source reference resolves through it, so a caller-supplied id would be an echo
     * rather than the retention stage 2 requires. `WorkspaceModelSource` takes the same shape, which is why
     * [ModelSource] has no id parameter at all.
     */
    @Test
    fun identityComesFromTheSourcesOwnHeader() {
        val admitted = assertIs<Admission.Admitted>(
            admit(ModelSource("""{"header":{"id":"orders/v2"},"content":{}}""")),
        )

        assertEquals("orders/v2", admitted.modelId)
    }

    @Test
    fun aSourceWithoutAHeaderIdCannotBeAdmitted() {
        listOf("""{"content":{}}""", """{"header":{},"content":{}}""", """{"header":{"id":null},"content":{}}""")
            .forEach { json ->
                val rejected = assertIs<Admission.Rejected>(admit(ModelSource(json)), json)
                assertEquals(DiagnosticCode.SOURCE_HAS_NO_IDENTITY, rejected.diagnostic.code, json)
            }
    }

    /** A12 admits a model whose `header.id` is empty or spaces, so that text is its identity (`WorkspaceBlankTextDiffTest`). */
    @Test
    fun anIdOfSpacesIsStillTheSourcesIdentity() {
        listOf("", " ", "\u00A0").forEach { id ->
            val admitted = assertIs<Admission.Admitted>(admit(ModelSource("""{"header":{"id":"$id"},"content":{}}""")), id)
            assertEquals(id, admitted.modelId)
        }
    }

    /**
     * A source with no `content` is not a DocumentModel, and the refusal has a measured Kernel basis rather
     * than a shape preference: A12's own reader raises on it, where every thinner shape above `content` — an
     * empty `rootGroups`, an absent `rootGroups`, even an absent `modelRoot` — it reads without complaint
     * (`ModelRootReadabilityLawsTest`). So this is the one place the checker may refuse, and the rows below
     * it are why it refuses no further: a type-def-only model is exactly an empty root and is Kernel-valid.
     */
    @Test
    fun aSourceCarryingNoContentIsNotADocumentModel() {
        val rejected = assertIs<Admission.Rejected>(admit(ModelSource("""{"header":{"id":"order"}}""")))

        assertEquals(DiagnosticCode.SOURCE_HAS_NO_MODEL_CONTENT, rejected.diagnostic.code)
        assertIs<Admission.Admitted>(
            admit(ModelSource("""{"header":{"id":"order"},"content":{"modelRoot":{"rootGroups":[]}}}""")),
            "an empty root is a Kernel-valid model, so it must stay admissible",
        )
    }

    /** The caller's label names the file a finding came from, which the model id alone cannot. */
    @Test
    fun theSourcesLabelTravelsWithItsDiagnostic() {
        val outcome = admit(ModelSource("{ not json", diagnosticLabel = "orders/order.dm.json"))

        assertEquals("orders/order.dm.json", assertIs<Admission.Rejected>(outcome).diagnostic.diagnosticLabel)
    }

    /**
     * A commented model is a legal A12 artifact: both Kernel mappers enable `ALLOW_COMMENTS`, so refusing one
     * would make a file A12's own tooling round-trips unreadable here. The trailing-comma arm is the control
     * that this is parity with A12's ingress rather than a general leniency — neither mapper enables it.
     */
    @Test
    fun aCommentedSourceIsAdmittedWhileATrailingCommaIsStillARejection() {
        val commented = "{\n  // the order model\n  \"header\": {\"id\": \"order\"},\n  \"content\": {}\n}"

        assertIs<Admission.Admitted>(admit(ModelSource(commented)))
        assertTrue(admit(ModelSource("""{"header":{"id":"order"},}""")) is Admission.Rejected)
    }

    /** The authored text survives admission — a later check cannot recover what admission normalized away. */
    @Test
    fun wellFormedSourceIsAdmittedWithItsAuthoredTextRetained() {
        val json = """{"header":{"id":"order"},"content":{"modelInfo":{"name":"Order"}}}"""

        val admitted = assertIs<Admission.Admitted>(admit(ModelSource(json)))

        assertEquals(json, admitted.source.json)
        assertEquals("order", admitted.modelId)
    }
}
