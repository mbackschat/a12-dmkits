package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of everything the four value-list conditions ask of their two lists.
 *
 * Every row mirrors one measured against the real kernel by
 * `rulekit.validate.laws.ValueListQuantifierOperandKindLawsTest` and
 * `rulekit.validate.laws.ValueListConditionOperandLawsTest`. The SILENT rows carry the risk: a repeated
 * entity is answered as a duplicate parameter, so reporting a kind class there would name the wrong class on
 * a model A12 refuses for another reason.
 */
class ValueListConditionGatesTest {

    private fun codes(condition: String): List<String> = checkRead(
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
                {"type":"Field","id":"f10","name":"BandedChannel","Field":{"fieldType":{
                  "type":"EnumerationType","EnumerationType":{"values":[{"value":"WEB"},{"value":"STORE"}],
                  "categories":[{"name":"Band","values":["X","Y"]}]}}}},
                {"type":"Field","id":"f11","name":"CoverWindow","Field":{"fieldType":{"type":"DateRangeType",
                  "DateRangeType":{"format":"yyyy-MM-dd","rangeSeparator":"/"}}}},
                {"type":"Field","id":"f12","name":"ClaimWindow","Field":{"fieldType":{"type":"DateRangeType",
                  "DateRangeType":{"format":"yyyy-MM-dd","rangeSeparator":"/"}}}},
                {"type":"Group","id":"g2","name":"Banded","Group":{"elements":[
                  {"type":"Field","id":"f14","name":"InnerChannel","Field":{"fieldType":{
                    "type":"EnumerationType","EnumerationType":{"values":[{"value":"WEB"},{"value":"STORE"}],
                    "categories":[{"name":"Band","values":["X","Y"]}]}}}}
                ]}},
                {"type":"Field","id":"f15","name":"InternalMemo","Field":{"fieldType":{"type":"StringType",
                  "StringType":{"lineBreaksPermitted":true,"noValueValidation":true}}}},
                {"type":"Group","id":"g3","name":"Recipient","Group":{"elements":[
                  {"type":"Field","id":"f16","name":"RecipientName","Field":{"fieldType":{"type":"StringType"}}}
                ]}},
                {"type":"Group","id":"g1","name":"Broker","Group":{"elements":[
                  {"type":"Field","id":"f13","name":"BrokerWindow","Field":{"fieldType":{
                    "type":"DateRangeType","DateRangeType":{"format":"yyyy-MM-dd","rangeSeparator":"/"}}}}
                ]}},
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
     * The shapes it deliberately does not report, and the one that turned out not to be such a shape.
     *
     * A repeated entity is `MVK_DUPLICATE_PARAM1` to A12, whichever list the repeat sits in. The date-range
     * row used to sit here as a silence; the same-type gate is measured to run for ALL FOUR operators ahead
     * of the branch on the operator token, so it is a locked HANDOVER rather than an exclusion.
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
        // A12's duplicate class, which the entity-list pre-gates name; this family's kind gates stay silent.
        expected["the same declaration listed twice in the field list"] = listOf("MVK_DUPLICATE_PARAM1")
        expected["a declaration repeated across the field and value lists"] = emptyList()
        expected["the date-range quantifier over the same mixed list"] =
            listOf("MVK_VARYING_TYPES_NOT_ALLOWED")
        assertEquals(expected, actual, "the shapes it deliberately does not report")
    }

    /**
     * Where a repeat is asked, and where value validation is.
     *
     * A12 builds the field list first and refuses a repeat in it ahead of every gate; it builds the value list
     * only after the field list's type and validation gates, and asks a repeat ACROSS the two after the value
     * list's own base type and ahead of its validation. So a repeat silences exactly the gates behind it, and
     * the rows that repeat and fail a gate at once are the ones that separate the positions. The silent rows
     * are A12's duplicate classes, which this family does not name — never a class A12 does not give.
     */
    @Test
    fun whereARepeatIsAskedAndWhereValueValidationIs() {
        val rows = linkedMapOf(
            "Reference, Reference, Quantity In \\\"x\\\"" to listOf("MVK_DUPLICATE_PARAM1"),
            "Reference In Quantity, Quantity" to emptyList(),
            "Reference, Quantity In Reference" to listOf("MVK_VARYING_TYPES_NOT_ALLOWED"),
            "Reference In Reference, Quantity" to listOf("MVK_VARYING_TYPES_NOT_ALLOWED"),
            "PlacedOn In PlacedOn" to listOf("MVK_ONLY_STRING_ENUM_NUMBER_ALLOWED"),
            "InternalMemo In InternalMemo" to listOf("MVK_INVALID_RAW_TYPE"),
            "Reference In Reference, InternalMemo" to emptyList(),
            "InternalMemo In Quantity" to listOf("MVK_INVALID_RAW_TYPE"),
            "Reference In InternalMemo" to listOf("MVK_INVALID_RAW_TYPE"),
            "InternalMemo In \\\"x\\\"" to listOf("MVK_INVALID_RAW_TYPE"),
            "Recipient In Recipient/RecipientName" to emptyList(),
        )
        val actual = rows.keys.associateWith { quantifier("AtLeastOneFieldValueIncludedInValueList($it)") }
        assertEquals<Map<String, List<String>>>(rows, actual, "where a repeat is asked, and where value validation is")
    }

    /** The finding names the offending field and its declared type, since the fix is to split the list. */
    @Test
    fun theFindingNamesTheOffendingFieldAndItsType() {
        val finding = checkRead(
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

    /** The date-range operator's own two branches, neither of which the other three take. */
    @Test
    fun whatTheDateRangeOperatorDemandsOfEachList() {
        val actual = mutableMapOf<String, List<String>>()
        actual["one plain date-range field"] =
            quantifier("AtLeastOneDateRangeOverlaps(CoverWindow In ClaimWindow)")
        actual["two date-range fields"] =
            quantifier("AtLeastOneDateRangeOverlaps(CoverWindow, ClaimWindow In Broker/BrokerWindow)")
        actual["a group, which the other three would expand"] =
            quantifier("AtLeastOneDateRangeOverlaps(Broker In CoverWindow)")
        actual["a written-out value list"] =
            quantifier("AtLeastOneDateRangeOverlaps(CoverWindow In \\\"A\\\")")

        val expected = mutableMapOf<String, List<String>>()
        expected["one plain date-range field"] = emptyList()
        expected["two date-range fields"] = listOf("MVK_INVALID_PARAMETER_FOR_DATE_RANGE_COMPARISON")
        // The gate reads the REFERENCE list, so a group holding one field is refused even though it expands
        // to one — which is the row that separates the two readings of "exactly one".
        expected["a group, which the other three would expand"] =
            listOf("MVK_INVALID_PARAMETER_FOR_DATE_RANGE_COMPARISON")
        expected["a written-out value list"] = listOf("MVK_NO_CONST")
        assertEquals(expected, actual, "the date-range operator's two branches on the operator token")
    }

    /** The enum domain a written-out value list is looked up in: the union, and what a category does to it. */
    @Test
    fun theEnumDomainIsTheUnionAndACategoryAccessSwapsIt() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a declared value"] = quantifier("NoFieldValueIncludedInValueList(Channel In \\\"WEB\\\")")
        actual["an undeclared value"] = quantifier("NoFieldValueIncludedInValueList(Channel In \\\"GOLD\\\")")
        actual["a value declared only by the SECOND field"] =
            quantifier("NoFieldValueIncludedInValueList(Channel, Origin In \\\"PARTNER\\\")")
        actual["a value declared by neither"] =
            quantifier("NoFieldValueIncludedInValueList(Channel, Origin In \\\"GOLD\\\")")
        actual["a category token under a category access"] =
            quantifier("NoFieldValueIncludedInValueList(BandedChannel -> Band In \\\"X\\\")")
        actual["the enumeration's OWN value under a category access"] =
            quantifier("NoFieldValueIncludedInValueList(BandedChannel -> Band In \\\"WEB\\\")")
        actual["any value over a text declaration"] =
            quantifier("NoFieldValueIncludedInValueList(GiftNote In \\\"GOLD\\\")")

        val expected = mutableMapOf<String, List<String>>()
        expected["a declared value"] = emptyList()
        expected["an undeclared value"] = listOf("MVK_INVALID_STRING_CONSTANT_FOR_ENUMERATION_OR_CATEGORY")
        // The union is what decides, so a value no single field admits on its own still passes.
        expected["a value declared only by the SECOND field"] = emptyList()
        expected["a value declared by neither"] =
            listOf("MVK_INVALID_STRING_CONSTANT_FOR_ENUMERATION_OR_CATEGORY")
        expected["a category token under a category access"] = emptyList()
        // The access SWAPS the contribution rather than widening it, which these two rows separate.
        expected["the enumeration's OWN value under a category access"] =
            listOf("MVK_INVALID_STRING_CONSTANT_FOR_ENUMERATION_OR_CATEGORY")
        expected["any value over a text declaration"] = emptyList()
        assertEquals(expected, actual, "the enum domain a written-out list is looked up in")
    }

    /**
     * A category access demands a FIELD, so a group under one is refused before any kind or domain gate.
     *
     * Mirrors `rulekit.validate.laws.ValueListConditionOperandLawsTest`, whose grid was built to separate
     * the two answers a reader expects — the access reaches the fields the group expands into, or it is
     * ignored — and measured a third that neither produces. The access turns out to DEMAND an enumeration
     * that declares the named category, and each way of failing that has a class of its own: a group, a
     * field that is no enumeration, an enumeration without that category. The controls are the same
     * operands minus the access, which is what charges each refusal to the access rather than to the operand.
     */
    @Test
    fun aCategoryAccessDemandsAnEnumerationDeclaringThatCategory() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a group under an access, category token"] =
            quantifier("NoFieldValueIncludedInValueList(Banded -> Band In \\\"X\\\")")
        actual["a group under an access, the enumeration's own value"] =
            quantifier("NoFieldValueIncludedInValueList(Banded -> Band In \\\"WEB\\\")")
        actual["a group whose field the operator would refuse anyway"] =
            quantifier("NoFieldValueIncludedInValueList(Broker -> Band In \\\"x\\\")")
        actual["a group under an access beside a number field"] =
            quantifier("NoFieldValueIncludedInValueList(Banded -> Band, Quantity In \\\"X\\\")")
        actual["the access on a group in the VALUE list"] =
            quantifier("NoFieldValueIncludedInValueList(Channel In Banded -> Band)")
        actual["the same group with NO access, category token"] =
            quantifier("NoFieldValueIncludedInValueList(Banded In \\\"X\\\")")
        actual["the same group with NO access, declared value"] =
            quantifier("NoFieldValueIncludedInValueList(Banded In \\\"WEB\\\")")
        actual["the access on a FIELD in the value list"] =
            quantifier("NoFieldValueIncludedInValueList(Channel In BandedChannel -> Band)")
        actual["a TEXT field under an access"] =
            quantifier("NoFieldValueIncludedInValueList(GiftNote -> Band In \\\"X\\\")")
        actual["a NUMBER field under an access"] =
            quantifier("NoFieldValueIncludedInValueList(Quantity -> Band In \\\"1\\\")")
        actual["an ENUMERATION declaring no such category"] =
            quantifier("NoFieldValueIncludedInValueList(Channel -> Band In \\\"X\\\")")

        val expected = mutableMapOf<String, List<String>>()
        expected["a group under an access, category token"] = listOf(NO_GROUPS)
        expected["a group under an access, the enumeration's own value"] = listOf(NO_GROUPS)
        // Before the kind gate: without the access this group's date-range field draws its own class.
        expected["a group whose field the operator would refuse anyway"] = listOf(NO_GROUPS)
        // …and before the varying-types gate, measured in both operand orders on the kernel.
        expected["a group under an access beside a number field"] = listOf(NO_GROUPS)
        expected["the access on a group in the VALUE list"] = listOf(NO_GROUPS)
        expected["the same group with NO access, category token"] =
            listOf("MVK_INVALID_STRING_CONSTANT_FOR_ENUMERATION_OR_CATEGORY")
        expected["the same group with NO access, declared value"] = emptyList()
        expected["the access on a FIELD in the value list"] = emptyList()
        expected["a TEXT field under an access"] = listOf(NO_ENUMERATION)
        expected["a NUMBER field under an access"] = listOf(NO_ENUMERATION)
        expected["an ENUMERATION declaring no such category"] = listOf(CATEGORY_UNKNOWN)
        assertEquals(expected, actual, "a category access demands a field, on either side of the comparison")
    }

    private companion object {
        private const val NO_GROUPS = "MVK_NO_GROUPS_ALLOWED"
        private const val NO_ENUMERATION = "MVK_NO_ENUMERATION"

        /** The kernel's own spelling, typo included. */
        private const val CATEGORY_UNKNOWN = "MVK_CATEGORY_UNKOWN"
    }
}
