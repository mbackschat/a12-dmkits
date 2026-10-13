package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * JVM + JS mirror of `adapter.laws.KeyedMessageMembershipAgreementTest` (KERNEL-FINDINGS KF562): a keyed read is
 * no member of an unkeyed parameter, a keyed GROUP read admits a keyed parameter on each of its fields, a keyed
 * group named in the field position is the key's own class, and a group read reaching a field across a repeatable
 * child the rule does not iterate binds it as a star.
 */
class KeyedMessageMembershipTest {

    @Test
    fun whichParametersAKeyedReadAdmits() {
        val keyedField = "FieldFilled(Holder) And FieldFilled(/Policy/Claims/Note For \\\"c1\\\")"
        val keyedGroup = "FieldFilled(Holder) And GroupFilled(/Policy/Claims For \\\"c1\\\")"
        val office = "FieldFilled(Holder) And GroupFilled(/Policy/Office)"
        assertEquals(mapOf(
            "keyed field, unkeyed parameter" to listOf("INVALID_FIELD"),
            "keyed field, the field keyed" to emptyList(),
            "keyed group, a field keyed" to emptyList(),
            "keyed group, another key" to listOf("MVK_INDEX_FOR_ERROR_TEXT_INVALID"),
            "keyed group, unkeyed parameter" to listOf("INVALID_FIELD"),
            "keyed group, the group named" to listOf("MVK_INDEX_FOR_ERROR_TEXT_INVALID"),
            "keyed and plain reads, unkeyed parameter" to emptyList(),
            "group read, its own field" to emptyList(),
            "group read, a repeatable child's field" to listOf("PARAM_VALUE_WITH_STARREFERENCE"),
        ), mapOf(
            "keyed field, unkeyed parameter" to refused(keyedField, "\$/Policy/Claims/Note.value\$"),
            "keyed field, the field keyed" to refused(keyedField, "\$/Policy/Claims/Note.value For \\\"c1\\\"\$"),
            "keyed group, a field keyed" to refused(keyedGroup, "\$/Policy/Claims/Gate.value For \\\"c1\\\"\$"),
            "keyed group, another key" to refused(keyedGroup, "\$/Policy/Claims/Gate.value For \\\"c2\\\"\$"),
            "keyed group, unkeyed parameter" to refused(keyedGroup, "\$/Policy/Claims/Note.value\$"),
            "keyed group, the group named" to refused(keyedGroup, "\$/Policy/Claims For \\\"c1\\\"\$"),
            "keyed and plain reads, unkeyed parameter" to
                refused("$keyedField And NumberOfFilledFields(/Policy/Claims*/Note) > 0", "\$/Policy/Claims/Note.value\$"),
            "group read, its own field" to refused(office, "\$/Policy/Office/Staff.value\$"),
            "group read, a repeatable child's field" to refused(office, "\$/Policy/Office/Desks/Seats.value\$"),
        ))
    }

    private fun refused(condition: String, text: String): List<String> =
        checkRead(ModelSource(model(condition, text))).findings.filter { it.justifiesRejection }.map { it.code }

    /** An insurance policy: claims keyed by reference, an office with repeatable desks. */
    private fun model(condition: String, text: String): String = """
        {"header":{"id":"policy-keyed-membership","locales":[{"code":"en_US"}]},
         "content":{"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Policy","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Holder","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Group","id":"g1","name":"Claims","Group":{"repeatability":5,"indexFieldName":"Ref","elements":[
              {"type":"Field","id":"f1","name":"Ref","Field":{"fieldType":{"type":"StringType"}}},
              {"type":"Field","id":"f2","name":"Note","Field":{"fieldType":{"type":"StringType"}}},
              {"type":"Field","id":"f3","name":"Gate","Field":{"fieldType":{"type":"StringType"}}}
            ]}},
            {"type":"Group","id":"g2","name":"Office","Group":{"elements":[
              {"type":"Field","id":"f4","name":"Staff","Field":{"fieldType":{"type":"StringType"}}},
              {"type":"Group","id":"g3","name":"Desks","Group":{"repeatability":2,"elements":[
                {"type":"Field","id":"f5","name":"Seats","Field":{"fieldType":{"type":"StringType"}}}
              ]}}
            ]}},
            {"type":"Rule","id":"r0","name":"ProbeRule","Rule":{
              "errorCondition":"$condition",
              "errorEntityRelPath":"../Holder",
              "errorCode":"PROBE","errorMessage":[{"locale":"en_US","text":"Check $text."}]}}
          ]}}
         ]}}}
    """.trimIndent()
}
