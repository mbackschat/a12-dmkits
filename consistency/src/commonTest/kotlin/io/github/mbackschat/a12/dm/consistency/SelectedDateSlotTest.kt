package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of a Date slot that reads a SELECTED row (a12-kernel-lean SPEC-2026-10-06-05).
 *
 * Every row mirrors one measured against the kernel by `adapter.laws.CheckerSelectedDateValidityAgreementTest`,
 * so the JS build answers it as the JVM one does: an extract operator whose argument is keyed is still the extract
 * arm, so it names no entity for the validity gate and still meets the slot gates.
 */
class SelectedDateSlotTest {

    /** One rule carrying [condition]; the condition sits inside a JSON string, so its quotes are escaped there. */
    private fun codes(condition: String): List<String> = checkRead(
        ModelSource(
            """
            {"header":{"modelVersion":"29.4.0","id":"policy-selected-slots","locales":[{"code":"en_US"}]},
             "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Policy","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Key","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Group","id":"g1","name":"Events","Group":{"repeatability":3,"indexFieldName":"Code",
                 "elements":[
                  {"type":"Field","id":"f1","name":"Code","Field":{"fieldType":{"type":"StringType"}}},
                  {"type":"Field","id":"f2","name":"Month","Field":{"fieldType":{"type":"NumberType",
                    "NumberType":{"maxFractionalDigits":0,"minValue":"0","maxValue":"12"}}}},
                  {"type":"Field","id":"f3","name":"Occurred","Field":{"fieldType":{"type":"DateType",
                    "DateType":{"format":"yyyy-MM-dd"}}}}
                ]}},
                {"type":"Rule","id":"r0","name":"Probe","Rule":{
                  "errorCondition":"FieldFilled(Key) And ${'$'}CONDITION",
                  "errorEntityRelPath":"../Key",
                  "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}}
              ]}}
             ]}}}
            """.trimIndent().replace("\$CONDITION", condition.replace("\"", "\\\"")),
        ),
    ).findings.filter { it.justifiesRejection }.map { it.code }

    @Test
    fun anExtractorOverASelectionIsStillAnExtractor() {
        val occurred = "Events/Occurred For Key"
        val actual = linkedMapOf(
            "a selected immediate month — control" to
                codes("Valid(Date(DayFromDate($occurred), Events/Month For Key, YearFromDate($occurred)))"),
            "three extractors over a selection" to
                codes("Valid(Date(DayFromDate($occurred), MonthFromDate($occurred), YearFromDate($occurred)))"),
            "a month extractor over a selection in the day slot" to
                codes("Date(MonthFromDate($occurred), \"2\", \"2024\") == \"01.02.2024\""),
        )
        val expected = linkedMapOf(
            "a selected immediate month — control" to emptyList(),
            "three extractors over a selection" to listOf("MVK_ONLY_CONSTANTS_USED"),
            "a month extractor over a selection in the day slot" to listOf("MVK_INVALID_DATE_OR_TIME_EXTRACT_OP"),
        )
        assertEquals(expected, actual, "an extractor's keyed argument does not make its slot an entity")
    }
}
