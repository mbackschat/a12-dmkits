package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The checker's half of KF584, replayed on both runtimes: a blank date `format` leaves the model unbuilt, an absent
 * one is the default, and a field typed by a type definition is disclosed rather than judged. A12's answer to every
 * row was measured by `adapter.laws.CheckerBlankDateFormatAgreementTest`; this class only replays it.
 */
class BlankDateFormatKf584Test {

    private fun report(fieldType: String, typeDefinitions: String = "") = checkRead(ModelSource("""
        {"header":{"modelVersion":"28.4.0","id":"policies","locales":[{"code":"en_US"}]},"content":{"modelInfo":{"name":"model"},$typeDefinitions"modelRoot":{"rootGroups":[
         {"type":"Group","id":"/Policy","name":"Policy","Group":{"elements":[
          {"type":"Field","id":"/Policy/Start","name":"Start","Field":{"fieldType":$fieldType}}
         ]}}
        ]}}}
    """.trimIndent()))

    private fun codes(fieldType: String) = report(fieldType).findings.filter { it.justifiesRejection }.map { it.code }

    @Test
    fun aBlankFormatLeavesTheModelUnbuiltOnEachDateType() {
        val emSpace = Char(0x2003).toString()
        val noBreakSpace = Char(0x00A0).toString()
        val actual = listOf("DateType", "DateTimeType", "TimeType").associateWith { type ->
            listOf(null, "", " ", emSpace, noBreakSpace, "yyyy-MM-dd").map { format ->
                val member = format?.let { "\"format\":\"$it\"" } ?: ""
                codes("""{"type":"$type","$type":{$member}}""")
            }
        }
        val unbuilt = listOf(CK_MODEL_NOT_BUILDABLE)
        val expected = listOf("DateType", "DateTimeType", "TimeType").associateWith {
            listOf(emptyList(), unbuilt, unbuilt, unbuilt, emptyList(), emptyList())
        }
        assertEquals<Map<String, List<List<String>>>>(expected, actual)
    }

    @Test
    fun aFieldTypedByADefinitionIsDisclosedAtTheField() {
        val gaps = report(
            """{"type":"TypeDefType","TypeDefType":{"typeDefinitionId":"StartType"}}""",
            """"typeDefinitions":[{"id":"StartType","name":"StartType","revision":"1",""" +
                """"fieldType":{"type":"DateType","DateType":{"format":""}}}],""",
        ).gaps
        assertTrue(gaps.any { it.at == "/Policy/Start" && "`StartType`" in it.reason }, "$gaps")
    }
}
