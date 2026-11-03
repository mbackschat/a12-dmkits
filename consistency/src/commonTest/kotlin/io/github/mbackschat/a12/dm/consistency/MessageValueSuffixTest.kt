package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of the two gates an error message's `.value` suffix passes.
 *
 * Every row mirrors one measured against the real kernel by
 * `rulekit.validate.laws.MessageValueSuffixGateOrderLawsTest`, on the same retail shape. The two control
 * rows are what keep the grid honest: the suffix on an ordinary read field and the PLAIN form on the
 * value-validation-free one are both admitted, so a family reporting on the SUFFIX alone or on the FIELD
 * alone would fail one of them.
 */
class MessageValueSuffixTest {

    private val starReference = "PARAM_VALUE_WITH_STARREFERENCE"
    private val noValueValidation = "INVALID_NO_VALUE_VALIDATION"

    /**
     * `Notes` and `Lines/Comment` waive value validation; `Reference` and `Lines/Sku` do not — so the
     * declaration gate and the star gate can be moved independently, and the row where both hold is a real
     * intersection rather than one variable wearing two names.
     */
    private fun codes(condition: String, message: String): List<String> = checkRead(
        ModelSource(
            """
        {"header":{"modelVersion":"29.4.0","id":"order","locales":[{"code":"en_US"}]},
         "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Field","id":"f1","name":"Notes","Field":{"fieldType":{"type":"StringType",
              "StringType":{"noValueValidation":true,"lineBreaksPermitted":true}}}},
            {"type":"Group","id":"g1","name":"Lines","Group":{"repeatability":9,"elements":[
              {"type":"Field","id":"f2","name":"Sku","Field":{"fieldType":{"type":"StringType"}}},
              {"type":"Field","id":"f3","name":"Comment","Field":{"fieldType":{"type":"StringType",
                "StringType":{"noValueValidation":true,"lineBreaksPermitted":true}}}}
            ]}},
            {"type":"Rule","id":"r0","name":"Probe","Rule":{
              "errorCondition":"${condition.replace("\"", "\\\"")}",
              "errorEntityRelPath":"../Reference","errorCode":"C1",
              "errorMessage":[{"locale":"en_US","text":"${message.replace("\"", "\\\"")}"}]}}
          ]}}
         ]}}}
            """.trimIndent(),
        ),
    ).findings.filter { it.at == "/Order/Probe" }.map { it.code }

    private val readsReference = "FieldFilled(Reference)"
    private val readsNotes = "FieldFilled(Reference) And FieldFilled(Notes)"
    private val readsStarredSku = "FieldFilled(Reference) And AtLeastOneFieldFilled(Lines*/Sku)"
    private val readsStarredComment = "FieldFilled(Reference) And AtLeastOneFieldFilled(Lines*/Comment)"

    @Test
    fun whichOfTheValueSuffixesTwoGatesThisFamilyReports() {
        val actual = mutableMapOf<String, List<String>>()
        actual["the suffix on an ordinary read field — the control"] =
            codes(readsReference, "see \$Reference.value\$")
        actual["the PLAIN form on the value-validation-free field — the control"] =
            codes(readsNotes, "see \$Notes\$")
        actual["the suffix on a value-validation-free field"] = codes(readsNotes, "see \$Notes.value\$")
        actual["the suffix on a field the condition reads through a STAR"] =
            codes(readsStarredSku, "see \$Lines/Sku.value\$")
        actual["the suffix where BOTH gates hold"] =
            codes(readsStarredComment, "see \$Lines/Comment.value\$")
        actual["the suffix on a field the condition does not read at all"] =
            codes(readsReference, "see \$Notes.value\$")

        val expected = mutableMapOf<String, List<String>>()
        expected["the suffix on an ordinary read field — the control"] = emptyList()
        expected["the PLAIN form on the value-validation-free field — the control"] = emptyList()
        expected["the suffix on a value-validation-free field"] = listOf(noValueValidation)
        expected["the suffix on a field the condition reads through a STAR"] = listOf(starReference)
        // THE ROW THIS FAMILY EXISTS FOR: both gates hold, and A12 names the star one — so the order in the
        // reducer is measured, not chosen, and the other class here would be the wrong class on a model A12
        // does refuse.
        expected["the suffix where BOTH gates hold"] = listOf(starReference)
        // MEMBERSHIP pre-empts both, and `MESSAGE_PARAMETER_REFERENCE` is the family that answers it. This
        // row locks that this one adds nothing on top rather than doubling the verdict.
        expected["the suffix on a field the condition does not read at all"] = listOf("INVALID_FIELD")
        assertEquals(expected, actual, "which of the value suffix's two gates this family reports")
    }

    /**
     * What a single unstarred read does to the star gate, and the forms this family declines outright.
     *
     * The first pair is the subtraction the gate rests on: the same starred read stops mattering the moment
     * the condition also reads the field without a star, which is why the reducer collects both forms rather
     * than judging the first read it meets. The rest are message-grammar forms whose own gates answer
     * elsewhere — a suffix on one of them would claim a law nothing has measured.
     */
    @Test
    fun theFormsThisFamilyDoesNotJudge() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a starred read beside an unstarred one of the same field"] = codes(
            "$readsStarredSku And FieldFilled(Lines/Sku)",
            "see \$Lines/Sku.value\$",
        )
        actual["the suffix on a name that resolves to nothing"] =
            codes(readsReference, "see \$NoSuchField.value\$")
        actual["the suffix on a CATEGORY projection"] = codes(readsNotes, "see \$Notes -> Band.value\$")
        actual["the suffix inside an unterminated parameter"] = codes(readsNotes, "see \$Notes.value")
        actual["a suffixed parameter beside a plain one of the same field"] =
            codes(readsNotes, "see \$Notes\$ and \$Notes.value\$")

        val expected = mutableMapOf<String, List<String>>()
        // An unstarred read clears the gate for the whole declaration: A12 asks whether the field is
        // readable at a single row, and one unstarred reference says it is.
        expected["a starred read beside an unstarred one of the same field"] = listOf("MVK_NO_WILDCARD")
        // The ADDRESS is answered before either gate, by the membership family's own class.
        expected["the suffix on a name that resolves to nothing"] = listOf("INVALID_ENTITY")
        // A category suffix is a form of its own with a gate of its own, so this family declines it and the
        // category family answers — reporting a `.value` law on it would name a gate that form never meets.
        expected["the suffix on a CATEGORY projection"] = listOf("FIELD_NO_ENUM")
        // The dollar count refuses the whole text, so nothing inside it is judged as a parameter at all.
        expected["the suffix inside an unterminated parameter"] = listOf("WRONG_NUMBER_OF_DOLLARS")
        // Only the suffixed parameter carries the gates; the plain one beside it is admitted.
        expected["a suffixed parameter beside a plain one of the same field"] = listOf(noValueValidation)
        assertEquals(expected, actual, "the forms this family does not judge")
    }
}
