package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of "reference resolution runs first and wins over every syntax class".
 *
 * Mirrors `rulekit.validate.laws.ConditionMultipleDefectLawsTest`. Four families read one condition text, so
 * without the withholding a condition carrying two defects — the normal state of one being written — draws a
 * class A12 withheld. Nothing in the suite covered a text with two defects before this.
 */
class ConditionMultipleDefectTest {

    private fun codes(condition: String): List<String> =
        check(ModelSource(model(condition))).findings
            .filter { it.at == "/Policy/Probe" }.map { it.code }.distinct().sorted()

    @Test
    fun aSyntaxClassIsWithheldBehindAnUnresolvableName() {
        val actual = mutableMapOf<String, List<String>>()
        actual["sound — the control"] = codes("FieldFilled(Reference)")
        actual["one unresolvable reference"] = codes("FieldFilled(NoSuchField)")
        actual["a trailing operator alone"] = codes("FieldFilled(Reference) And")
        actual["an unresolvable reference AND a trailing operator"] = codes("FieldFilled(NoSuchField) And")
        actual["an unexpected token alone"] = codes("FieldFilled(Reference) And And FieldFilled(Reference)")
        actual["an unresolvable reference AND an unexpected token"] =
            codes("FieldFilled(NoSuchField) And And FieldFilled(Reference)")

        val expected = mutableMapOf<String, List<String>>()
        expected["sound — the control"] = emptyList()
        expected["one unresolvable reference"] = listOf("MVK_INVALID_ENTITY")
        expected["a trailing operator alone"] = listOf("MVK_INCOMPLETE_INPUT")
        // The pair of rows with teeth: each syntax class fires alone and is gone beside an unresolvable
        // name. Reporting both would name a verdict A12 withheld on the likeliest real input of all.
        expected["an unresolvable reference AND a trailing operator"] = listOf("MVK_INVALID_ENTITY")
        expected["an unexpected token alone"] = listOf("MVK_UNEXPECTED_TOKEN")
        expected["an unresolvable reference AND an unexpected token"] = listOf("MVK_INVALID_ENTITY")
        assertEquals(expected, actual, "which class a condition with two defects reports")
    }

    private fun model(condition: String): String = """
        {"header":{"id":"policy","locales":[{"code":"en_US"}]},
         "content":{"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Policy","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Rule","id":"r0","name":"Probe","Rule":{
              "errorCondition":"$condition","errorEntityRelPath":"../Reference",
              "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"see it"}]}}
          ]}}
         ]}}}
    """.trimIndent()
}
