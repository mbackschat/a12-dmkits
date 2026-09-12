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
        val outcome = admit(ModelSource("""{"header":{"id":"order"},"content":{"modelInfo":{"name":"Order"}}}"""))

        val admitted = assertIs<Admission.Admitted>(outcome)
        val content = admitted.parsed.jsonObject["content"]!!.jsonObject
        val name = content["modelInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content
        assertEquals("Order", name)
    }

    /**
     * The discriminating case for the read-path fidelity contract, on the carrier that actually holds a
     * bound: a `NumberType`'s `maxValue`. A parse that decoded through a binary double would yield "250.0"
     * here, and the trailing zero would be unrecoverable downstream — the loss happens at parse time,
     * upstream of any check that needs it. The fixture uses A12's real envelope rather than an invented
     * `config`/`max` pair, so it also fails if that shape is ever mis-navigated.
     */
    @Test
    fun aNumericFieldConfigurationKeepsItsAuthoredScale() {
        val json = """
            {"header":{"id":"order"},"content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"/Order","name":"Order","Group":{"elements":[
                {"type":"Field","id":"/Order/Total","name":"Total","Field":{"fieldType":{
                  "type":"NumberType","NumberType":{"minValue":0.00,"maxValue":250.00}}}}
              ]}}
            ]}}}
        """.trimIndent()

        val admitted = assertIs<Admission.Admitted>(admit(ModelSource(json)))
        val numberType = prepare(admitted).elements.single { it.kind == "Field" }
            .declaration["Field"]!!.jsonObject["fieldType"]!!.jsonObject["NumberType"]!!.jsonObject

        assertEquals("250.00", numberType["maxValue"]!!.jsonPrimitive.content)
        assertEquals("0.00", numberType["minValue"]!!.jsonPrimitive.content)
    }
}
