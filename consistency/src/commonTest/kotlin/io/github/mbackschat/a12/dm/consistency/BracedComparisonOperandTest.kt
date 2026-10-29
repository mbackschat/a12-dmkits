package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of how an Enumeration's equality gates read a braced operand (KF407, extended 2026-10-09).
 *
 * Every row mirrors one measured against the kernel by `adapter.laws.CheckerComparisonOperandShapeAgreementTest`,
 * so the JS build answers it as the JVM one does: a braced text partner is outside the display gate, a braced
 * Enumeration keeps its type, and a braced constant still meets the value domain.
 */
class BracedComparisonOperandTest {

    private val toString = "MVK_INVALID_COMPARE_ENUM_TO_STRING"
    private val notAValue = "MVK_INVALID_STRING_CONSTANT_FOR_ENUM_COMPARISON"

    /** One rule carrying [comparison]; the condition sits inside a JSON string, so its quotes are escaped there. */
    private fun codes(comparison: String): List<String> = checkRead(
        ModelSource(
            """
            {"header":{"modelVersion":"28.4.0","id":"policy","locales":[{"code":"en_US"}]},
             "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Policy","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Holder","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"f1","name":"Labelled","Field":{"fieldType":{
                  "type":"EnumerationType","EnumerationType":{"values":[
                    {"value":"BASIC","label":[{"locale":"en_US","text":"Basic cover"}]},
                    {"value":"PLUS","label":[{"locale":"en_US","text":"Plus cover"}]}]}}}},
                {"type":"Rule","id":"r0","name":"Probe","Rule":{
                  "errorCondition":"FieldFilled(Holder) And ${'$'}COMPARISON",
                  "errorEntityRelPath":"../Holder",
                  "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}}
              ]}}
             ]}}}
            """.trimIndent().replace("\$COMPARISON", comparison.replace("\"", "\\\"")),
        ),
    ).findings.map { it.code }

    @Test
    fun aBraceIsReadOnceForEachGate() {
        val actual = linkedMapOf(
            "a text against a labelled one — the control" to codes("[Holder] == [Labelled]"),
            "a braced text" to codes("{[Holder]} == [Labelled]"),
            "a braced labelled one" to codes("[Holder] == {[Labelled]}"),
            "a braced stored value" to codes("{\"BASIC\"} == [Labelled]"),
            "a twice-braced unknown value" to codes("[Labelled] == {{\"missing\"}}"),
        )
        val expected = linkedMapOf(
            "a text against a labelled one — the control" to listOf(toString),
            "a braced text" to emptyList(),
            "a braced labelled one" to listOf(toString),
            "a braced stored value" to emptyList(),
            "a twice-braced unknown value" to listOf(notAValue),
        )
        assertEquals(expected, actual, "one brace, three readings")
    }
}
