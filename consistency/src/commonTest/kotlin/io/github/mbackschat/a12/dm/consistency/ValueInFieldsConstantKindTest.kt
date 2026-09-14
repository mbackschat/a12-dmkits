package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of `NumberOfValueInFields`' switch on the constant.
 *
 * Every row mirrors one measured against the real kernel by
 * `rulekit.validate.laws.ValueInFieldsConstantKindLawsTest`. The SILENT rows carry the risk: the three gates
 * that run ahead of the switch each answer with a class of their own, so reporting a type class there would
 * name the wrong class on a model A12 does refuse.
 */
class ValueInFieldsConstantKindTest {

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
                {"type":"Field","id":"f6","name":"TermsAccepted","Field":{"fieldType":{"type":"ConfirmType"}}},
                {"type":"Field","id":"f7","name":"NewsletterOptIn","Field":{"fieldType":{"type":"ConfirmType"}}},
                {"type":"Field","id":"f8","name":"PlacedOn","Field":{"fieldType":{"type":"DateType",
                  "DateType":{"format":"dd.MM.yyyy"}}}},
                {"type":"Field","id":"f9","name":"Channel","Field":{"fieldType":{"type":"EnumerationType",
                  "EnumerationType":{"values":[{"value":"WEB"},{"value":"STORE"}]}}}},
                {"type":"Group","id":"g1","name":"Packing","Group":{"elements":[
                  {"type":"Field","id":"f10","name":"Fragile","Field":{"fieldType":{"type":"BooleanType"}}},
                  {"type":"Group","id":"g2","name":"Label","Group":{"elements":[
                    {"type":"Field","id":"f11","name":"Courier","Field":{"fieldType":{"type":"StringType"}}}
                  ]}}
                ]}},
                {"type":"Rule","id":"r0","name":"Probe","Rule":{
                  "errorCondition":"CONDITION_HERE","errorEntityRelPath":"../Reference",
                  "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}}
              ]}}
             ]}}}
            """.trimIndent().replace("CONDITION_HERE", condition),
        ),
    ).findings.map { it.code }

    private fun count(constant: String, list: String): List<String> =
        codes("FieldFilled(Reference) And NumberOfValueInFields($constant In $list) > 0")

    @Test
    fun whichDeclarationsEachBooleanConstantAdmits() {
        val actual = mutableMapOf<String, List<String>>()
        actual["True over two Boolean declarations"] = count("True", "Expedited, GiftWrapped")
        actual["False over two Boolean declarations"] = count("False", "Expedited, GiftWrapped")
        actual["True over two Confirm declarations"] = count("True", "TermsAccepted, NewsletterOptIn")
        actual["False over two Confirm declarations"] = count("False", "TermsAccepted, NewsletterOptIn")
        actual["True over two text declarations"] = count("True", "GiftNote, Reference")
        actual["False over a date declaration beside a Boolean one"] = count("False", "PlacedOn, Expedited")
        actual["False over an enumeration declaration beside a Boolean one"] =
            count("False", "Channel, Expedited")

        val expected = mutableMapOf<String, List<String>>()
        expected["True over two Boolean declarations"] = emptyList()
        expected["False over two Boolean declarations"] = emptyList()
        expected["True over two Confirm declarations"] = emptyList()
        expected["False over two Confirm declarations"] = listOf("MVK_NO_TYPEYESNO")
        expected["True over two text declarations"] = listOf("MVK_NO_TYPE_YES_OR_YESNO")
        expected["False over a date declaration beside a Boolean one"] = listOf("MVK_NO_TYPEYESNO")
        expected["False over an enumeration declaration beside a Boolean one"] = listOf("MVK_NO_TYPEYESNO")
        assertEquals(expected, actual, "which declarations each boolean constant admits")
    }

    @Test
    fun whichDeclarationsTheStringAndNumberConstantsAdmit() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a string constant over two text declarations"] = count("\\\"X\\\"", "GiftNote, Reference")
        actual["a string constant over two number declarations"] = count("\\\"X\\\"", "Quantity, Backordered")
        actual["a string constant over a date declaration beside a text one"] =
            count("\\\"X\\\"", "PlacedOn, GiftNote")
        actual["a number constant over two number declarations"] = count("0", "Quantity, Backordered")
        actual["a number constant over two text declarations"] = count("0", "GiftNote, Reference")
        actual["a number constant over two Boolean declarations"] = count("0", "Expedited, GiftWrapped")

        val expected = mutableMapOf<String, List<String>>()
        expected["a string constant over two text declarations"] = emptyList()
        expected["a string constant over two number declarations"] = listOf("MVK_NO_STRING_OR_ENUM")
        expected["a string constant over a date declaration beside a text one"] =
            listOf("MVK_NO_STRING_OR_ENUM")
        expected["a number constant over two number declarations"] = emptyList()
        expected["a number constant over two text declarations"] = listOf("MVK_NO_NUMBER")
        expected["a number constant over two Boolean declarations"] = listOf("MVK_NO_NUMBER")
        assertEquals(expected, actual, "which declarations the string and number constants admit")
    }

    /** A GROUP operand is expanded to every field beneath it, through nested groups. */
    @Test
    fun aGroupOperandIsExpandedThroughItsNestedGroupsToo() {
        val actual = mutableMapOf<String, List<String>>()
        actual["False over a group whose SUBGROUP carries a text declaration"] = count("False", "Packing")
        actual["a string constant over the same group"] = count("\\\"X\\\"", "Packing")

        val expected = mutableMapOf<String, List<String>>()
        expected["False over a group whose SUBGROUP carries a text declaration"] =
            listOf("MVK_NO_TYPEYESNO")
        expected["a string constant over the same group"] = listOf("MVK_NO_STRING_OR_ENUM")
        assertEquals(expected, actual, "a group operand is expanded through its nested groups too")
    }

    /**
     * The shapes it deliberately does not report, each because A12 answers them with another class.
     *
     * The enumeration row is the subtle one: A12 walks the list field by field, so a constant that is not a
     * member of `Channel` is answered at THAT position and the number declaration behind it is never
     * reached. Reporting the type class there would name the wrong class on a correct rejection.
     */
    @Test
    fun theShapesItDeliberatelyDoesNotReport() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a repeated entity, which A12 answers as a duplicate"] = count("False", "GiftNote, GiftNote")
        actual["a lone unstarred field, which A12 answers on arity"] = count("False", "GiftNote")
        actual["a member of the enumeration, beside a text declaration"] = count("\\\"WEB\\\"", "Channel, GiftNote")
        actual["a NON-member of the enumeration, beside a number declaration"] =
            count("\\\"MAIL\\\"", "Channel, Quantity")

        val expected = mutableMapOf<String, List<String>>()
        expected["a repeated entity, which A12 answers as a duplicate"] = emptyList()
        expected["a lone unstarred field, which A12 answers on arity"] = emptyList()
        expected["a member of the enumeration, beside a text declaration"] = emptyList()
        expected["a NON-member of the enumeration, beside a number declaration"] = emptyList()
        assertEquals(expected, actual, "the shapes it deliberately does not report")
    }

    /** The finding names the field and the constant, since the fix is to change one of the two. */
    @Test
    fun theFindingNamesTheFieldAndTheConstant() {
        val finding = check(
            ModelSource(
                """
                {"header":{"id":"order","locales":[{"code":"en_US"}]},
                 "content":{"modelRoot":{"rootGroups":[
                  {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
                    {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
                    {"type":"Field","id":"f1","name":"GiftNote","Field":{"fieldType":{"type":"StringType"}}},
                    {"type":"Field","id":"f4","name":"Expedited","Field":{"fieldType":{"type":"BooleanType"}}},
                    {"type":"Rule","id":"r0","name":"Probe","Rule":{
                      "errorCondition":"FieldFilled(Reference) And NumberOfValueInFields(False In Expedited, GiftNote) > 0",
                      "errorEntityRelPath":"../Reference",
                      "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}}
                  ]}}
                 ]}}}
                """.trimIndent(),
            ),
        ).findings.single { it.code == "MVK_NO_TYPEYESNO" }
        assertEquals("/Order/Probe", finding.at)
        assertEquals(true, finding.explain.contains("/Order/GiftNote"), finding.explain)
        assertEquals(true, finding.explain.contains("False"), finding.explain)
    }
}
