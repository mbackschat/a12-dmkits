package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

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
        assertEquals(AdmissionDiagnosticCode.SOURCE_NOT_WELL_FORMED, rejected.diagnostic.code)
        assertEquals("order", rejected.diagnostic.modelId)
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
