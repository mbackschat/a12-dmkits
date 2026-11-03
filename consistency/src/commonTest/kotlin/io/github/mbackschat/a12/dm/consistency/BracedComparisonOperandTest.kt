package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of how an Enumeration's equality gate reads a braced operand (KF407, extended 2026-10-09).
 *
 * Every row mirrors one measured against the kernel by `adapter.laws.CheckerComparisonOperandShapeAgreementTest`,
 * so the JS build answers it as the JVM one does: a braced constant still meets the value domain, and a labelled
 * Enumeration compares with a text however either side is braced, kernel 31.1.1 having no display gate (KF655).
 */
class BracedComparisonOperandTest {

    private val notAValue = "MVK_INVALID_STRING_CONSTANT_FOR_ENUM_COMPARISON"

    /** One rule carrying [comparison]; the condition sits inside a JSON string, so its quotes are escaped there. */
    private fun codes(comparison: String): List<String> = checkRead(
        ModelSource(
            """
            {"header":{"modelVersion":"29.4.0","id":"policy","locales":[{"code":"en_US"}]},
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
            "a text against a labelled one" to codes("[Holder] == [Labelled]"),
            "a braced text" to codes("{[Holder]} == [Labelled]"),
            "a braced labelled one" to codes("[Holder] == {[Labelled]}"),
            "a braced stored value" to codes("{\"BASIC\"} == [Labelled]"),
            "a twice-braced unknown value" to codes("[Labelled] == {{\"missing\"}}"),
        )
        val expected = linkedMapOf(
            "a text against a labelled one" to emptyList(),
            "a braced text" to emptyList(),
            "a braced labelled one" to emptyList(),
            "a braced stored value" to emptyList(),
            "a twice-braced unknown value" to listOf(notAValue),
        )
        assertEquals(expected, actual, "a brace read by the value-domain gate alone")
    }
}
