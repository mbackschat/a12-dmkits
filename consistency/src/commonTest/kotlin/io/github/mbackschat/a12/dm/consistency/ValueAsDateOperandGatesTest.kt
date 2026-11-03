package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of `ValueAsDate`'s four-gate operand chain.
 *
 * Every row mirrors one measured against the real kernel by
 * `rulekit.validate.laws.ValueAsDateOperandGateLawsTest`. The declared-type grid is the load-bearing half:
 * A12 draws one class for eleven different reasons, so a reproduction that read only the two kinds an author
 * would guess at would agree with the kernel on the rows it looked at and diverge on the rest.
 */
class ValueAsDateOperandGatesTest {

    @Test
    fun whichDeclarationsValueAsDateReads() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a date declaring DAY_OPTIONAL — the control"] = fromTheForm("ApproxDay")
        actual["a date declaring MONTH_OPTIONAL"] = fromTheForm("ApproxMonth")
        actual["a date declaring YEAR_OPTIONAL"] = fromTheForm("ApproxYear")
        actual["the control read for its LAST day"] = codes(
            model(formRule("FieldFilled(Reference) And ValueAsDate(ApproxDay, LastDay) > [Limit]"), ""),
        )
        actual["a date at the FULL default"] = fromTheForm("ExactDate")
        actual["a date fragment"] = fromTheForm("Season")
        actual["a time field"] = fromTheForm("CutOff")
        actual["a datetime field"] = fromTheForm("LoggedAt")
        actual["a date-range field"] = fromTheForm("Window")
        actual["a string field"] = fromTheForm("Note")
        actual["a number field"] = fromTheForm("ItemCount")
        actual["an enumeration field"] = fromTheForm("Grade")
        actual["a boolean field"] = fromTheForm("Expedited")
        actual["a confirm field"] = fromTheForm("Acknowledged")
        actual["a custom field"] = fromTheForm("Handler")

        val expected = mutableMapOf<String, List<String>>()
        expected["a date declaring DAY_OPTIONAL — the control"] = emptyList()
        expected["a date declaring MONTH_OPTIONAL"] = emptyList()
        expected["a date declaring YEAR_OPTIONAL"] = emptyList()
        expected["the control read for its LAST day"] = emptyList()
        expected["a date at the FULL default"] = wrongKind
        expected["a date fragment"] = wrongKind
        expected["a time field"] = wrongKind
        expected["a datetime field"] = wrongKind
        expected["a date-range field"] = wrongKind
        expected["a string field"] = wrongKind
        expected["a number field"] = wrongKind
        expected["an enumeration field"] = wrongKind
        expected["a boolean field"] = wrongKind
        expected["a confirm field"] = wrongKind
        expected["a custom field"] = wrongKind
        assertEquals(expected, actual, "which declarations ValueAsDate reads")
    }

    @Test
    fun theOrderAmongTheOperandGates() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a non-repeating GROUP"] = fromTheForm("Desk")
        actual["a repeating group carrying its star"] = fromTheForm("Lines*")
        actual["a STAR over a field whose precision is right"] = fromTheForm("Lines*/LineApprox")
        actual["a STAR over a field whose precision is wrong"] = fromTheForm("Lines*/LineCode")
        actual["a STAR over a category access"] = fromTheForm("Lines*/LineGrade -> Band")
        actual["a CATEGORY access"] = fromTheForm("Grade -> Band")
        actual["an unstarred read into the repetition, precision right"] = fromTheForm("Lines/LineApprox")
        actual["an unstarred read into the repetition, precision wrong"] = fromTheForm("Lines/LineCode")
        actual["the ROOT group"] = fromTheForm("/Returns")
        actual["a name nothing declares"] = fromTheForm("Nowhere")
        actual["the row's own field read from the row's rule"] = fromARow("LineApprox")
        actual["the row's wrong-precision field read from the row's rule"] = fromARow("LineCode")

        val expected = mutableMapOf<String, List<String>>()
        expected["a non-repeating GROUP"] = listOf("MVK_NO_GROUPS_ALLOWED")
        expected["a repeating group carrying its star"] = listOf("MVK_NO_GROUPS_ALLOWED")
        expected["a STAR over a field whose precision is right"] = listOf("MVK_NO_WILDCARDS_ALLOWED")
        expected["a STAR over a field whose precision is wrong"] = listOf("MVK_NO_WILDCARDS_ALLOWED")
        expected["a STAR over a category access"] = listOf("MVK_NO_WILDCARDS_ALLOWED")
        expected["a CATEGORY access"] = listOf("MVK_CATEGORY_NOT_ALLOWED")
        // A12 answers MVK_NO_WILDCARD from the reference reader outside this method, and it pre-empts all
        // four classes — so the silence is what keeps the checker from naming the precision on a field whose
        // precision A12 never looked at.
        expected["an unstarred read into the repetition, precision right"] = emptyList()
        expected["an unstarred read into the repetition, precision wrong"] = emptyList()
        expected["the ROOT group"] = listOf("MVK_NO_GROUPS_ALLOWED")
        // Unresolvable, so the reference family names it and this one says nothing.
        expected["a name nothing declares"] = emptyList()
        expected["the row's own field read from the row's rule"] = emptyList()
        expected["the row's wrong-precision field read from the row's rule"] = wrongKind
        assertEquals(expected, actual, "the order among the operand gates")
    }

    private fun fromTheForm(operand: String): List<String> = codes(
        model(formRule("FieldFilled(Reference) And ValueAsDate($operand, FirstDay) > [Limit]"), ""),
    )

    private fun fromARow(operand: String): List<String> = codes(
        model(
            "",
            rowRule("FieldFilled(LineCode) And ValueAsDate($operand, FirstDay) > [/Returns/Limit]"),
        ),
    )

    /** Only this family's codes, so another family's finding cannot make a row look right. */
    private fun codes(json: String): List<String> =
        checkRead(ModelSource(json)).findings.filter { it.code in OWNED }.map { it.code }

    private fun formRule(condition: String): String =
        ruleElement("r0", condition, "../Reference")

    private fun rowRule(condition: String): String =
        ruleElement("r1", condition, "../LineCode")

    private fun ruleElement(id: String, condition: String, target: String): String = """
        ,{"type":"Rule","id":"$id","name":"Probe","Rule":{
          "errorCondition":"$condition","errorEntityRelPath":"$target",
          "errorCode":"C0","errorMessage":[{"locale":"en_US","text":"x"}]}}
    """.trimIndent()

    /** An e-commerce returns desk mirroring the laws test's carrier. */
    private fun model(formRule: String, rowRule: String): String = """
        {"header":{"modelVersion":"29.4.0","id":"returns-valueasdate","locales":[{"code":"en_US"}]},
         "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Returns","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Field","id":"f1","name":"Limit","Field":{"fieldType":{"type":"DateType",
              "DateType":{"format":"yyyy-MM-dd"}}}},
            {"type":"Field","id":"f2","name":"ApproxDay","Field":{"fieldType":{"type":"DateType",
              "DateType":{"format":"yyyy-MM-dd","datePrecision":"DAY_OPTIONAL"}}}},
            {"type":"Field","id":"f3","name":"ApproxMonth","Field":{"fieldType":{"type":"DateType",
              "DateType":{"format":"dd.MM.yyyy","datePrecision":"MONTH_OPTIONAL"}}}},
            {"type":"Field","id":"f4","name":"ApproxYear","Field":{"fieldType":{"type":"DateType",
              "DateType":{"format":"yyyyMMdd","datePrecision":"YEAR_OPTIONAL"}}}},
            {"type":"Field","id":"f5","name":"ExactDate","Field":{"fieldType":{"type":"DateType",
              "DateType":{"format":"yyyy-MM-dd"}}}},
            {"type":"Field","id":"f6","name":"Season","Field":{"fieldType":{"type":"DateFragmentType",
              "DateFragmentType":{"formatOfFragment":"MM-dd"}}}},
            {"type":"Field","id":"f7","name":"CutOff","Field":{"fieldType":{"type":"TimeType",
              "TimeType":{"format":"HH:mm:ss"}}}},
            {"type":"Field","id":"f8","name":"LoggedAt","Field":{"fieldType":{"type":"DateTimeType",
              "DateTimeType":{"format":"yyyy-MM-dd'T'HH:mm:ss"}}}},
            {"type":"Field","id":"f9","name":"Window","Field":{"fieldType":{"type":"DateRangeType",
              "DateRangeType":{"format":"dd.MM.yyyy","rangeSeparator":"-"}}}},
            {"type":"Field","id":"f10","name":"Note","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Field","id":"f11","name":"ItemCount","Field":{"fieldType":{"type":"NumberType"}}},
            {"type":"Field","id":"f12","name":"Grade","Field":{"fieldType":{"type":"EnumerationType",
              "EnumerationType":{"values":[{"value":"RESALE"},{"value":"SCRAP"}],
              "categories":[{"name":"Band","values":["LOW","HIGH"]}]}}}},
            {"type":"Field","id":"f13","name":"Expedited","Field":{"fieldType":{"type":"BooleanType"}}},
            {"type":"Field","id":"f14","name":"Acknowledged","Field":{"fieldType":{"type":"ConfirmType"}}},
            {"type":"Field","id":"f15","name":"Handler","Field":{"fieldType":{"type":"CustomFieldType",
              "CustomFieldType":{"name":"HandlerCode"}}}},
            {"type":"Group","id":"g1","name":"Desk","Group":{"elements":[
              {"type":"Field","id":"f16","name":"DeskCode","Field":{"fieldType":{"type":"StringType"}}}
            ]}},
            {"type":"Group","id":"g2","name":"Lines","Group":{"repeatability":10,"elements":[
              {"type":"Field","id":"f17","name":"LineCode","Field":{"fieldType":{"type":"StringType"}}},
              {"type":"Field","id":"f18","name":"LineApprox","Field":{"fieldType":{"type":"DateType",
                "DateType":{"format":"yyyy-MM-dd","datePrecision":"DAY_OPTIONAL"}}}},
              {"type":"Field","id":"f19","name":"LineGrade","Field":{"fieldType":{"type":"EnumerationType",
                "EnumerationType":{"values":[{"value":"KEEP"},{"value":"SEND"}],
                "categories":[{"name":"Band","values":["LOW","HIGH"]}]}}}}$rowRule
            ]}}$formRule
          ]}}
         ]}}}
    """.trimIndent()

    private companion object {
        val OWNED = setOf(
            "MVK_NO_GROUPS_ALLOWED",
            "MVK_NO_WILDCARDS_ALLOWED",
            "MVK_CATEGORY_NOT_ALLOWED",
            "MVK_INVALID_DATE_TYPE_FOR_OP",
        )

        val wrongKind = listOf("MVK_INVALID_DATE_TYPE_FOR_OP")
    }
}
