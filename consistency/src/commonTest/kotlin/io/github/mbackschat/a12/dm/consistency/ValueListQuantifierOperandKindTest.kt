package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of the value-list quantifiers' two operand questions.
 *
 * Every row mirrors one measured against the real kernel by
 * `rulekit.validate.laws.ValueListQuantifierOperandKindLawsTest`. The SILENT rows carry the risk: a repeated
 * entity is answered as a duplicate parameter, and the date-range quantifier does not ask these questions at
 * all — reporting a kind class on either would name the wrong class on a model A12 refuses for another
 * reason, or on one it admits.
 */
class ValueListQuantifierOperandKindTest {

    private fun codes(condition: String): List<String> = check(
        ModelSource(
            """
            {"header":{"id":"order","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"f1","name":"GiftNote","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"f2","name":"Quantity","Field":{"fieldType":{"type":"NumberType"}}},
                {"type":"Field","id":"f3","name":"Backordered","Field":{"fieldType":{"type":"NumberType"}}},
                {"type":"Field","id":"f4","name":"Expedited","Field":{"fieldType":{"type":"BooleanType"}}},
                {"type":"Field","id":"f5","name":"GiftWrapped","Field":{"fieldType":{"type":"BooleanType"}}},
                {"type":"Field","id":"f6","name":"PlacedOn","Field":{"fieldType":{"type":"DateType",
                  "DateType":{"format":"dd.MM.yyyy"}}}},
                {"type":"Field","id":"f7","name":"ShippedOn","Field":{"fieldType":{"type":"DateType",
                  "DateType":{"format":"dd.MM.yyyy"}}}},
                {"type":"Field","id":"f8","name":"Channel","Field":{"fieldType":{"type":"EnumerationType",
                  "EnumerationType":{"values":[{"value":"WEB"},{"value":"STORE"}]}}}},
                {"type":"Field","id":"f9","name":"Origin","Field":{"fieldType":{"type":"EnumerationType",
                  "EnumerationType":{"values":[{"value":"WEB"},{"value":"PARTNER"}]}}}},
                {"type":"Rule","id":"r0","name":"Probe","Rule":{
                  "errorCondition":"CONDITION_HERE","errorEntityRelPath":"../Reference",
                  "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}}
              ]}}
             ]}}}
            """.trimIndent().replace("CONDITION_HERE", condition),
        ),
    ).findings.map { it.code }

    private fun quantifier(call: String): List<String> = codes("FieldFilled(Reference) And $call")

    @Test
    fun whatTheFieldListMayCarry() {
        val actual = mutableMapOf<String, List<String>>()
        actual["two text declarations"] =
            quantifier("AtLeastOneFieldValueIncludedInValueList(GiftNote, Reference In \\\"A\\\", \\\"B\\\")")
        actual["a text declaration beside a number one"] =
            quantifier("AtLeastOneFieldValueIncludedInValueList(GiftNote, Quantity In \\\"A\\\", \\\"B\\\")")
        actual["two number declarations, against a literal list"] =
            quantifier("AtLeastOneFieldValueIncludedInValueList(Quantity, Backordered In \\\"1\\\", \\\"2\\\")")
        actual["two Boolean declarations"] =
            quantifier("AtLeastOneFieldValueIncludedInValueList(Expedited, GiftWrapped In \\\"A\\\", \\\"B\\\")")
        actual["two date declarations"] =
            quantifier("AtLeastOneFieldValueIncludedInValueList(PlacedOn, ShippedOn In \\\"A\\\", \\\"B\\\")")
        actual["two enumeration declarations"] =
            quantifier("AtLeastOneFieldValueIncludedInValueList(Channel, Origin In \\\"WEB\\\")")
        actual["a text declaration beside a number one, under another quantifier"] =
            quantifier("NoFieldValueIncludedInValueList(GiftNote, Quantity In \\\"A\\\")")

        val expected = mutableMapOf<String, List<String>>()
        expected["two text declarations"] = emptyList()
        expected["a text declaration beside a number one"] = listOf("MVK_VARYING_TYPES_NOT_ALLOWED")
        expected["two number declarations, against a literal list"] =
            listOf("MVK_INVALID_TYPES_FOR_COMPARISON")
        expected["two Boolean declarations"] = listOf("MVK_ONLY_STRING_ENUM_NUMBER_ALLOWED")
        expected["two date declarations"] = listOf("MVK_ONLY_STRING_ENUM_NUMBER_ALLOWED")
        expected["two enumeration declarations"] = emptyList()
        expected["a text declaration beside a number one, under another quantifier"] =
            listOf("MVK_VARYING_TYPES_NOT_ALLOWED")
        assertEquals(expected, actual, "what the field list may carry")
    }

    @Test
    fun whatTheValueListMayCarry() {
        val actual = mutableMapOf<String, List<String>>()
        actual["an entity value list of the same base type"] =
            quantifier("AtLeastOneFieldValueIncludedInValueList(GiftNote In Reference)")
        actual["an entity value list of a different base type"] =
            quantifier("AtLeastOneFieldValueIncludedInValueList(GiftNote In Quantity)")

        val expected = mutableMapOf<String, List<String>>()
        expected["an entity value list of the same base type"] = emptyList()
        expected["an entity value list of a different base type"] =
            listOf("MVK_VARYING_TYPES_NOT_ALLOWED")
        assertEquals(expected, actual, "what the value list may carry")
    }

    /**
     * The shapes it deliberately does not report.
     *
     * A repeated entity is `MVK_DUPLICATE_PARAM1` to A12, whichever list the repeat sits in, and the
     * date-range quantifier's arm of the same method skips these questions entirely.
     */
    @Test
    fun theShapesItDeliberatelyDoesNotReport() {
        val actual = mutableMapOf<String, List<String>>()
        actual["the same declaration listed twice in the field list"] =
            quantifier("AtLeastOneFieldValueIncludedInValueList(GiftNote, GiftNote In \\\"A\\\")")
        actual["a declaration repeated across the field and value lists"] =
            quantifier("AtLeastOneFieldValueIncludedInValueList(GiftNote In GiftNote)")
        actual["the date-range quantifier over the same mixed list"] =
            quantifier("AtLeastOneDateRangeOverlaps(GiftNote, Quantity In Reference)")

        val expected = mutableMapOf<String, List<String>>()
        expected["the same declaration listed twice in the field list"] = emptyList()
        expected["a declaration repeated across the field and value lists"] = emptyList()
        expected["the date-range quantifier over the same mixed list"] = emptyList()
        assertEquals(expected, actual, "the shapes it deliberately does not report")
    }

    /** The finding names the offending field and its declared type, since the fix is to split the list. */
    @Test
    fun theFindingNamesTheOffendingFieldAndItsType() {
        val finding = check(
            ModelSource(
                """
                {"header":{"id":"order","locales":[{"code":"en_US"}]},
                 "content":{"modelRoot":{"rootGroups":[
                  {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
                    {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
                    {"type":"Field","id":"f2","name":"Quantity","Field":{"fieldType":{"type":"NumberType"}}},
                    {"type":"Rule","id":"r0","name":"Probe","Rule":{
                      "errorCondition":"FieldFilled(Reference) And AtLeastOneFieldValueIncludedInValueList(Reference, Quantity In \"A\")",
                      "errorEntityRelPath":"../Reference",
                      "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}}
                  ]}}
                 ]}}}
                """.trimIndent(),
            ),
        ).findings.single { it.code == "MVK_VARYING_TYPES_NOT_ALLOWED" }
        assertEquals("/Order/Probe", finding.at)
        assertEquals(true, finding.explain.contains("/Order/Quantity"), finding.explain)
        assertEquals(true, finding.explain.contains("NumberType"), finding.explain)
    }
}
