package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of "a field type reports every defect it carries".
 *
 * Mirrors `rulekit.validate.laws.FieldTypeMultipleDefectLawsTest`, and it is the boundary row for the
 * withholding families: a rule's members and a condition's text both stop at the first failure, and a field
 * type does not. Without a row here the checker's field-type families would be one careless generalisation
 * away from falling silent on input A12 answers in full.
 */
class FieldTypeMultipleDefectTest {

    private fun codes(stringType: String): List<String> =
        checkRead(ModelSource(model(stringType))).findings
            .filter { it.at == "/Policy/Reference" }.map { it.code }.distinct().sorted()

    @Test
    fun aFieldTypeReportsEveryDefectItCarries() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a plain string — the control"] = codes("{}")
        actual["an invalid pattern alone"] = codes("""{"pattern":"a++"}""")
        actual["a string-parameter conflict alone"] = codes("""{"noValueValidation":true}""")
        actual["BOTH on one field"] = codes("""{"pattern":"a++","noValueValidation":true}""")

        val expected = mutableMapOf<String, List<String>>()
        expected["a plain string — the control"] = emptyList()
        expected["an invalid pattern alone"] =
            listOf("MVK_ERROR_MESSAGE_AND_PATTERN_INVALID", "MVK_PATTERN_INVALID")
        expected["a string-parameter conflict alone"] = listOf("MVK_STRING_TYPE_NO_VAL_AND_NO_LINE_BREAK")
        // All four, matching A12 class for class — including the one neither row alone produces.
        expected["BOTH on one field"] = listOf(
            "MVK_ERROR_MESSAGE_AND_PATTERN_INVALID",
            "MVK_PATTERN_INVALID",
            "MVK_STRING_TYPE_NO_VAL_AND_NO_LINE_BREAK",
            "MVK_STRING_TYPE_PARAMS_INCONSISTENT",
        )
        assertEquals(expected, actual, "what a field type carrying two defects reports")
    }

    private fun model(stringType: String): String = """
        {"header":{"modelVersion":"28.4.0","id":"policy","locales":[{"code":"en_US"}]},
         "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Policy","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{
              "type":"StringType","StringType":$stringType}}}
          ]}}
         ]}}}
    """.trimIndent()
}
