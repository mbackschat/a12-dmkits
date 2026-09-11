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
        val outcome = admit(ModelSource(modelId = "order", json = "{ not json"))

        val rejected = assertIs<Admission.Rejected>(outcome)
        assertEquals(DiagnosticCode.SOURCE_NOT_WELL_FORMED, rejected.diagnostic.code)
        assertEquals("order", rejected.diagnostic.modelId)
    }

    /** The caller's label names the file a finding came from, which the model id alone cannot. */
    @Test
    fun theSourcesLabelTravelsWithItsDiagnostic() {
        val outcome = admit(
            ModelSource(modelId = "order", json = "{ not json", diagnosticLabel = "orders/order.dm.json"),
        )

        assertEquals("orders/order.dm.json", assertIs<Admission.Rejected>(outcome).diagnostic.diagnosticLabel)
    }

    /**
     * A commented model is a legal A12 artifact: both Kernel mappers enable `ALLOW_COMMENTS`, so refusing one
     * would make a file A12's own tooling round-trips unreadable here. The trailing-comma arm is the control
     * that this is parity with A12's ingress rather than a general leniency — neither mapper enables it.
     */
    @Test
    fun aCommentedSourceIsAdmittedWhileATrailingCommaIsStillARejection() {
        val commented = "{\n  // the order model\n  \"header\": {\"id\": \"order\"}\n}"

        assertIs<Admission.Admitted>(admit(ModelSource("order", commented)))
        assertTrue(admit(ModelSource("order", """{"header":{"id":"order"},}""")) is Admission.Rejected)
    }

    /** The authored text survives admission — a later check cannot recover what admission normalized away. */
    @Test
    fun wellFormedSourceIsAdmittedWithItsAuthoredTextRetained() {
        val json = """{"modelInfo":{"name":"order"}}"""

        val outcome = admit(ModelSource(modelId = "order", json = json))

        val admitted = assertIs<Admission.Admitted>(outcome)
        assertEquals(json, admitted.source.json)
        assertEquals("order", admitted.source.modelId)
    }
}
