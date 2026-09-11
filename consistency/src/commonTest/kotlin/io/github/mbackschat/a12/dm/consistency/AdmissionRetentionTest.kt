package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * B3's parse-once minimum: admission keeps what it parsed.
 *
 * The baseline interpreter path this replaces round-trips through JSON — `ReferenceAssembly`/`ModelWorkspace`
 * serialize and reparse — so the checker has to retain the tree from the start rather than inherit that.
 */
class AdmissionRetentionTest {

    @Test
    fun admissionRetainsTheParsedTreeSoALaterPhaseNeedNotReparse() {
        val outcome = admit(ModelSource(modelId = "order", json = """{"modelInfo":{"name":"order"}}"""))

        val admitted = assertIs<Admission.Admitted>(outcome)
        val name = admitted.parsed.jsonObject["modelInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content
        assertEquals("order", name)
    }

    /**
     * The discriminating case for the read-path fidelity contract: a bound's authored scale must survive.
     * A parse that decoded through a binary double would yield "250.0" here, and the trailing zero would be
     * unrecoverable downstream — the loss happens at parse time, upstream of any check that needs it.
     */
    @Test
    fun aNumericFieldConfigurationKeepsItsAuthoredScale() {
        val outcome = admit(ModelSource(modelId = "order", json = """{"config":{"max":250.00}}"""))

        val admitted = assertIs<Admission.Admitted>(outcome)
        val max = admitted.parsed.jsonObject["config"]!!.jsonObject["max"]!!.jsonPrimitive.content
        assertEquals("250.00", max)
    }
}
