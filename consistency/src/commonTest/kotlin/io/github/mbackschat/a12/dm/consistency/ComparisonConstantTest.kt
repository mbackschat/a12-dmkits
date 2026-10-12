package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The checker's half of a comparison against a written-out constant, typed as A12 types the constant.
 *
 * Every row mirrors one measured against the real kernel by
 * `adapter.laws.CheckerComparisonConstantAgreementTest`, and runs here on every target the checker ships to.
 */
class ComparisonConstantTest {

    @Test
    fun aConstantIsTypedAsA12TypesIt() {
        val rows = linkedMapOf(
            "a date ordered against a dated literal" to "[Shipped] > \"05.03.2024\"",
            "a date against a whole number" to "[Shipped] == 5",
            "a date against a day the calendar lacks" to "[Shipped] == \"29.02.2023\"",
            "a date ordered against a day the calendar lacks" to "[Shipped] > \"29.02.2023\"",
            "a yearless date against a yearless literal" to "[Birthday] == \"05.03.\"",
            "a text against a dated literal" to "[Label] == \"05.03.2024\"",
            "an enumeration against a dated literal" to "[Tier] == \"05.03.2024\"",
            "a range against a whole number" to "[Window] == 5",
            "a text ordered against Today" to "[Label] > Today",
            "Today against a whole number" to "Today == 5",
            "a whole number against Today" to "5 == Today",
        )
        val actual = rows.mapValues { (_, condition) -> codes(condition) }

        val expected = linkedMapOf(
            // A date-shaped literal is a date, so it clears the ordering gate.
            "a date ordered against a dated literal" to emptyList(),
            "a date against a whole number" to listOf(DATE),
            // A literal the calendar lacks is text: refused by the date arm, and by the ordering gate first.
            "a date against a day the calendar lacks" to listOf(DATE),
            "a date ordered against a day the calendar lacks" to listOf("MVK_INVALID_TYPE_FOR_COMPARISON"),
            "a yearless date against a yearless literal" to emptyList(),
            "a text against a dated literal" to listOf(DATE),
            // The date arm runs before the enumeration's domain question, so the domain is never asked.
            "an enumeration against a dated literal" to listOf(DATE),
            "a range against a whole number" to listOf("MVK_INVALID_COMPARE_TO_DATE_RANGE"),
            "a text ordered against Today" to listOf("MVK_INVALID_TYPE_FOR_COMPARISON"),
            // The two-constant gate exempts a pair only where its left side is a date.
            "Today against a whole number" to listOf(DATE),
            "a whole number against Today" to listOf("MVK_INVALID_COMPARE_TO_CONST"),
        )
        assertEquals(expected, actual, "what a comparison against a constant draws")
    }

    private fun codes(condition: String): List<String> =
        checkRead(ModelSource(model(condition))).findings.filter { it.justifiesRejection }.map { it.code }

    /** An order desk: one field of each kind the rows compare, and the rule under test. */
    private fun model(condition: String): String = """
        {"header":{"id":"order-constants","locales":[{"code":"en_US"}]},
         "content":{"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Label","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Field","id":"f1","name":"Tier","Field":{"fieldType":{"type":"EnumerationType",
              "EnumerationType":{"values":[{"value":"GOLD"},{"value":"SILVER"}]}}}},
            {"type":"Field","id":"f2","name":"Shipped","Field":{"fieldType":{"type":"DateType",
              "DateType":{"format":"yyyy-MM-dd"}}}},
            {"type":"Field","id":"f3","name":"Birthday","Field":{"fieldType":{"type":"DateType",
              "DateType":{"format":"MM-dd"}}}},
            {"type":"Field","id":"f4","name":"Window","Field":{"fieldType":{"type":"DateRangeType",
              "DateRangeType":{"format":"dd.MM.yyyy","rangeSeparator":"-"}}}},
            {"type":"Rule","id":"r0","name":"ProbeRule","Rule":{
              "errorCondition":${quoted("FieldFilled(Label) And $condition")},"errorEntityRelPath":"../Label",
              "errorCode":"PROBE","errorMessage":[{"locale":"en_US","text":"the probe"}]}}
          ]}}
         ]}}}
    """.trimIndent()

    private fun quoted(text: String): String = kotlinx.serialization.json.JsonPrimitive(text).toString()

    private companion object {
        const val DATE = "MVK_INVALID_COMPARE_TO_DATE"
    }

    @Test
    fun aWrittenTextConstantCarriesTheOnceDecodedValue() {
        val fixture = Json.parseToJsonElement(SyntaxFixtureData.messageParameters.first()).jsonObject
        val source = ModelSource(fixture.getValue("model").jsonPrimitive.content)
        val facts = TrackedFactBase(prepare(admit(source) as Admission.Admitted), ReadTranscript())
        val rows = listOf(
            "crlf" to ("\"A\r\nB\"" to "A\nB"),
            "repeated-cr" to ("\"A\r\r\nB\"" to "A\r\nB"),
        )
        for ((id, pair) in rows) {
            assertEquals(WrittenConstant.Text(pair.second), facts.writtenConstantOf(pair.first), id)
        }
    }

    @Test
    fun aFindingAfterADecodedLiteralStillPointsIntoTheOriginalMember() {
        val condition = "[Label] == \"A\r\r\nB\"\"C\" And FieldFilled(Missing)"
        val member = "FieldFilled(Label) And $condition"
        val finding = checkRead(ModelSource(model(condition))).findings.single { it.code == "MVK_INVALID_ENTITY" }
        val span = checkNotNull(finding.span)
        assertEquals("errorCondition", span.member)
        assertEquals(member.indexOf("Missing"), span.start)
        assertEquals(member.indexOf("Missing") + 7, span.end)
        assertEquals("Missing", member.substring(span.start, span.end))
    }
}
