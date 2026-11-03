package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of the two gates `NumberOfValueInFields` runs after its string arm's type walk.
 *
 * Every row mirrors one measured against the real kernel by
 * `rulekit.validate.laws.ValueInFieldsDateConstantLawsTest`. The near-miss rows carry the risk: a reader
 * matching only the SHAPE of a date literal would report three constants A12 counts happily, which is the
 * one direction this checker may not take.
 */
class ValueInFieldsDateConstantTest {

    @Test
    fun theGatesThatRunAfterTheStringArmsTypeWalk() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a plain literal over two text declarations — the control"] = counting("""\"AGENT\"""", "Note, Memo")
        actual["a date-shaped literal over two text declarations"] = dated("Note, Memo")
        actual["the same literal over two DATE declarations"] = dated("IssuedOn, ExpiresOn")
        actual["the same literal over a list holding a field that waives value validation"] =
            dated("Note, RawText")
        actual["the same literal over enumerations that do NOT declare it"] = dated("Tier, Note")
        actual["the same literal over enumerations that DO declare it"] = dated("Channel, Origin")
        actual["a date CONSTANT rather than a literal"] = counting("Today", "Note, Memo")

        val expected = mutableMapOf<String, List<String>>()
        expected["a plain literal over two text declarations — the control"] = emptyList()
        expected["a date-shaped literal over two text declarations"] = listOf("MVK_NO_DATE_CONSTANT")
        expected["the same literal over two DATE declarations"] = listOf("MVK_NO_STRING_OR_ENUM")
        expected["the same literal over a list holding a field that waives value validation"] =
            listOf("MVK_INVALID_RAW_TYPE")
        // The enumeration's own domain class is A12's answer and belongs to another family, so this one is
        // silent rather than naming a later gate.
        expected["the same literal over enumerations that do NOT declare it"] = emptyList()
        expected["the same literal over enumerations that DO declare it"] = listOf("MVK_NO_DATE_CONSTANT")
        // `Today` takes no arm of the switch, so this family reads no constant kind at all.
        expected["a date CONSTANT rather than a literal"] = emptyList()
        assertEquals(expected, actual, "the gates that run after the string arm's type walk")
    }

    @Test
    fun whichLiteralsCountAsADate() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a year-less date, with its trailing dot"] = literal("01.02.")
        actual["a time"] = literal("10:30:00")
        actual["a dated day"] = literal("01.02.2024")
        actual["a stamp"] = literal("01.02.2024T10:30:00")
        actual["a leap day in a leap year"] = literal("29.02.2024")
        actual["the year-less date WITHOUT its trailing dot"] = literal("01.02")
        actual["a day the calendar has not"] = literal("32.01.2024")
        actual["a leap day in a common year"] = literal("29.02.2023")
        actual["the year-less LEAP day"] = literal("29.02.")
        actual["an ISO day"] = literal("2024-01-02")
        actual["a stamp spelled with a space"] = literal("01.02.2024 10:30:00")
        actual["a two-digit day and month with no separators"] = literal("0102")

        val dateShaped = listOf("MVK_NO_DATE_CONSTANT")
        val expected = mutableMapOf<String, List<String>>()
        expected["a year-less date, with its trailing dot"] = dateShaped
        expected["a time"] = dateShaped
        expected["a dated day"] = dateShaped
        expected["a stamp"] = dateShaped
        expected["a leap day in a leap year"] = dateShaped
        expected["the year-less date WITHOUT its trailing dot"] = emptyList()
        expected["a day the calendar has not"] = emptyList()
        expected["a leap day in a common year"] = emptyList()
        expected["the year-less LEAP day"] = emptyList()
        expected["an ISO day"] = emptyList()
        expected["a stamp spelled with a space"] = emptyList()
        expected["a two-digit day and month with no separators"] = emptyList()
        assertEquals(expected, actual, "which literals count as a date")
    }

    /** The finding names the constant, since respelling it is one of the two ways out. */
    @Test
    fun theFindingNamesTheConstant() {
        val finding = checkRead(ModelSource(model("""\"01.02.2024\"""", "Note, Memo")))
            .findings.single { it.code == "MVK_NO_DATE_CONSTANT" }
        assertEquals("/Endorsement/Probe", finding.at)
        assertEquals(true, finding.explain.contains("01.02.2024"), finding.explain)
    }

    private fun literal(constant: String): List<String> = counting("\\\"$constant\\\"", "Note, Memo")

    private fun dated(list: String): List<String> = counting("""\"01.02.2024\"""", list)

    /** Only this family's codes, so another family's finding cannot make a row look right. */
    private fun counting(constant: String, list: String): List<String> =
        checkRead(ModelSource(model(constant, list))).findings.filter { it.code in OWNED }.map { it.code }

    /** An insurance endorsement desk mirroring the laws test's carrier. */
    private fun model(constant: String, list: String): String = """
        {"header":{"modelVersion":"29.4.0","id":"endorsement-count","locales":[{"code":"en_US"}]},
         "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Endorsement","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Field","id":"f1","name":"Note","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Field","id":"f2","name":"Memo","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Field","id":"f3","name":"RawText","Field":{"fieldType":{"type":"StringType",
              "StringType":{"noValueValidation":true}}}},
            {"type":"Field","id":"f4","name":"IssuedOn","Field":{"fieldType":{"type":"DateType",
              "DateType":{"format":"dd.MM.yyyy"}}}},
            {"type":"Field","id":"f5","name":"ExpiresOn","Field":{"fieldType":{"type":"DateType",
              "DateType":{"format":"dd.MM.yyyy"}}}},
            {"type":"Field","id":"f6","name":"Channel","Field":{"fieldType":{"type":"EnumerationType",
              "EnumerationType":{"values":[{"value":"01.02.2024"},{"value":"AGENT"}]}}}},
            {"type":"Field","id":"f7","name":"Origin","Field":{"fieldType":{"type":"EnumerationType",
              "EnumerationType":{"values":[{"value":"01.02.2024"},{"value":"BROKER"}]}}}},
            {"type":"Field","id":"f8","name":"Tier","Field":{"fieldType":{"type":"EnumerationType",
              "EnumerationType":{"values":[{"value":"BRONZE"},{"value":"GOLD"}]}}}},
            {"type":"Rule","id":"r0","name":"Probe","Rule":{
              "errorCondition":"FieldFilled(Reference) And NumberOfValueInFields($constant In $list) > 0",
              "errorEntityRelPath":"../Reference",
              "errorCode":"C0","errorMessage":[{"locale":"en_US","text":"x"}]}}
          ]}}
         ]}}}
    """.trimIndent()

    private companion object {
        val OWNED = setOf(
            "MVK_NO_STRING_OR_ENUM",
            "MVK_NO_TYPE_YES_OR_YESNO",
            "MVK_NO_TYPEYESNO",
            "MVK_NO_NUMBER",
            "MVK_INVALID_RAW_TYPE",
            "MVK_NO_DATE_CONSTANT",
        )
    }
}
