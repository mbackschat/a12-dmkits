package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of everything the two scalar value-list predicates ask.
 *
 * Every row mirrors one measured against the real kernel by
 * `rulekit.validate.laws.EnumConstantDomainLawsTest` and `rulekit.validate.laws.ScalarValueListGateLawsTest`.
 * The DECLINED rows carry the risk: three neighbouring shapes answer with three OTHER classes, a string
 * subject's list A12 admits outright, and a duplicated entity pre-empts every gate here.
 */
class ScalarValueListGatesTest {

    private val outsideDomain = "MVK_INVALID_STRING_CONSTANT_FOR_ENUMERATION"
    private val outsideCategory = "MVK_INVALID_STRING_CONSTANT_FOR_ENUMERATION_CATEGORY"
    private val onlyThreeKinds = "MVK_ONLY_STRING_ENUM_NUMBER_ALLOWED"
    private val rawType = "MVK_INVALID_RAW_TYPE"
    private val varyingTypes = "MVK_VARYING_TYPES_NOT_ALLOWED"
    private val invalidTypes = "MVK_INVALID_TYPES_FOR_COMPARISON"

    /** A policy model whose one rule carries [condition]; `Tier` declares two values and one category. */
    private fun codes(condition: String): List<String> = check(
        ModelSource(
            """
            {"header":{"id":"policy-scalar-value-lists","locales":[{"code":"en_US"}]},
             "content":{"typeDefinitions":[{"id":"TIER_1","name":"TierCode",
               "fieldType":{"type":"StringType"}}],
              "modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Policy","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"f1","name":"Holder","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"f2","name":"FreeNote","Field":{"fieldType":{"type":"StringType",
                  "StringType":{"lineBreaksPermitted":true,"noValueValidation":true}}}},
                {"type":"Field","id":"f3","name":"Premium","Field":{"fieldType":{"type":"NumberType",
                  "NumberType":{"maxFractionalDigits":2}}}},
                {"type":"Field","id":"f4","name":"Tier","Field":{"fieldType":{"type":"EnumerationType",
                  "EnumerationType":{"values":[{"value":"BASIC"},{"value":"PLUS"}],
                  "categories":[{"name":"Band","values":["LOW","HIGH"]}]}}}},
                {"type":"Field","id":"f5","name":"Segment","Field":{"fieldType":{"type":"EnumerationType",
                  "EnumerationType":{"values":[{"value":"RETAIL"},{"value":"CORPORATE"}]}}}},
                {"type":"Field","id":"f6","name":"Renewed","Field":{"fieldType":{"type":"BooleanType"}}},
                {"type":"Field","id":"f7","name":"IssuedOn","Field":{"fieldType":{"type":"DateType",
                  "DateType":{"format":"yyyy-MM-dd"}}}},
                {"type":"Field","id":"f8","name":"Serial","Field":{"fieldType":{"type":"CustomFieldType",
                  "CustomFieldType":{"name":"PolicySerial"}}}},
                {"type":"Field","id":"f9","name":"Typed","Field":{"fieldType":{"type":"TypeDefType",
                  "TypeDefType":{"typeDefinitionId":"TIER_1"}}}},
                {"type":"Group","id":"g1","name":"Broker","Group":{"elements":[
                  {"type":"Field","id":"f10","name":"BrokerName","Field":{"fieldType":{"type":"StringType"}}}
                ]}},
                {"type":"Group","id":"g2","name":"Items","Group":{"repeatability":20,"elements":[
                  {"type":"Field","id":"f11","name":"ItemCode","Field":{"fieldType":{"type":"StringType"}}}
                ]}},
                {"type":"Rule","id":"r0","name":"Probe","Rule":{
                  "errorCondition":"CONDITION_HERE","errorEntityRelPath":"../Reference",
                  "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}}
              ]}}
             ]}}}
            """.trimIndent().replace("CONDITION_HERE", condition),
        ),
    ).findings.map { it.code }

    /** The probe also READS the rule's error target, so the unreferenced-target family stays out of the row. */
    private fun list(probe: String): List<String> = codes("FieldFilled(Reference) And $probe")
        .filter { it != "MVK_ERROR_FIELD_NOT_REFERENCED" }

    @Test
    fun whatTheSubjectFieldMustBe() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a string field — the control"] = list("FieldValueIncludedInValueList(Holder, \\\"x\\\")")
        actual["a group"] = list("FieldValueIncludedInValueList(Broker, \\\"x\\\")")
        actual["a starred field"] = list("FieldValueIncludedInValueList(Items*/ItemCode, \\\"x\\\")")
        actual["a star on the field, not on its level"] =
            list("FieldValueIncludedInValueList(Items/ItemCode*, \\\"x\\\")")
        actual["a star on a fixed group above the field"] =
            list("FieldValueIncludedInValueList(Broker*/BrokerName, \\\"x\\\")")
        actual["the same without the star — the twin"] =
            list("FieldValueIncludedInValueList(Broker/BrokerName, \\\"x\\\")")
        actual["a boolean field"] = list("FieldValueIncludedInValueList(Renewed, \\\"x\\\")")
        actual["a date field"] = list("FieldValueIncludedInValueList(IssuedOn, \\\"x\\\")")
        actual["a CUSTOM-typed field"] = list("FieldValueIncludedInValueList(Serial, \\\"x\\\")")
        actual["a string field waiving value validation"] =
            list("FieldValueIncludedInValueList(FreeNote, \\\"x\\\")")
        actual["a TYPEDEF field, whose effective type is unread"] =
            list("FieldValueIncludedInValueList(Typed, \\\"x\\\")")
        actual["the negated spelling, same subject gate"] =
            list("FieldValueNotIncludedInValueList(Renewed, \\\"x\\\")")

        val expected = mutableMapOf<String, List<String>>()
        expected["a string field — the control"] = emptyList()
        expected["a group"] = listOf("MVK_NO_GROUPS_ALLOWED")
        expected["a starred field"] = listOf("MVK_NO_WILDCARDS_ALLOWED")
        expected["a star on the field, not on its level"] = listOf("MVK_INVALID_WILDCARD")
        expected["a star on a fixed group above the field"] = listOf("MVK_INVALID_WILDCARD")
        expected["the same without the star — the twin"] = emptyList()
        expected["a boolean field"] = listOf(onlyThreeKinds)
        expected["a date field"] = listOf(onlyThreeKinds)
        // `isLikeString` admits a PREDEFINED type beside a string, so the gate takes a declaration whose
        // name it does not mention.
        expected["a CUSTOM-typed field"] = emptyList()
        expected["a string field waiving value validation"] = listOf(rawType)
        expected["a TYPEDEF field, whose effective type is unread"] = emptyList()
        expected["the negated spelling, same subject gate"] = listOf(onlyThreeKinds)
        assertEquals(expected, actual, "what the subject field must be")
    }

    @Test
    fun whatTheWrittenOutListMustBe() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a number field, numbers"] = list("FieldValueIncludedInValueList(Premium, 1, 2)")
        actual["a number field, strings"] = list("FieldValueIncludedInValueList(Premium, \\\"1\\\")")
        actual["a string field, numbers"] = list("FieldValueIncludedInValueList(Holder, 1)")
        actual["an enumeration, numbers"] = list("FieldValueIncludedInValueList(Tier, 1)")
        actual["a categorized enumeration, numbers"] =
            list("FieldValueIncludedInValueList(Tier -> Band, 1)")
        actual["a categorized enumeration, a classification"] =
            list("FieldValueIncludedInValueList(Tier -> Band, \\\"LOW\\\")")
        actual["a categorized enumeration, the enumeration's OWN value"] =
            list("FieldValueIncludedInValueList(Tier -> Band, \\\"BASIC\\\")")
        actual["a categorized enumeration, a category it does not declare"] =
            list("FieldValueIncludedInValueList(Tier -> Nope, \\\"LOW\\\")")

        val expected = mutableMapOf<String, List<String>>()
        expected["a number field, numbers"] = emptyList()
        expected["a number field, strings"] = listOf(invalidTypes)
        expected["a string field, numbers"] = listOf(invalidTypes)
        expected["an enumeration, numbers"] = listOf(invalidTypes)
        // The kind gate is asked ahead of the domain one, so the category is never reached here.
        expected["a categorized enumeration, numbers"] = listOf(invalidTypes)
        expected["a categorized enumeration, a classification"] = emptyList()
        expected["a categorized enumeration, the enumeration's OWN value"] = listOf(outsideCategory)
        // An undeclared CATEGORY is the reference family's class, and this one abandons the lookup rather
        // than shortening the domain — a short domain refuses constants A12 takes.
        expected["a categorized enumeration, a category it does not declare"] = listOf("MVK_CATEGORY_UNKOWN")
        assertEquals(expected, actual, "what the written-out list must be")
    }

    @Test
    fun whatAListOfFieldEntriesMustBe() {
        val actual = mutableMapOf<String, List<String>>()
        actual["another string field"] = list("FieldValueIncludedInValueList(Holder, [Reference])")
        actual["a number against a string"] = list("FieldValueIncludedInValueList(Holder, [Premium])")
        actual["an enumeration against a string"] = list("FieldValueIncludedInValueList(Holder, [Tier])")
        actual["a custom-typed field against a string"] =
            list("FieldValueIncludedInValueList(Holder, [Serial])")
        actual["another enumeration against an enumeration"] =
            list("FieldValueIncludedInValueList(Tier, [Segment])")
        actual["the SUBJECT itself in its own list"] =
            list("FieldValueIncludedInValueList(Holder, [Holder])")
        actual["one entry written twice"] =
            list("FieldValueIncludedInValueList(Holder, [Reference], [Reference])")
        actual["a subject the head gate refuses"] = list("FieldValueIncludedInValueList(Renewed, [Tier])")
        actual["an entry waiving value validation"] =
            list("FieldValueIncludedInValueList(Holder, [FreeNote])")

        val expected = mutableMapOf<String, List<String>>()
        expected["another string field"] = emptyList()
        expected["a number against a string"] = listOf(varyingTypes)
        expected["an enumeration against a string"] = listOf(varyingTypes)
        // `matchBaseType` gives a custom declaration the TEXT base type, so it matches where the enumeration
        // above does not — the row that separates this walk from the subject gate's own grouping.
        expected["a custom-typed field against a string"] = emptyList()
        expected["another enumeration against an enumeration"] = emptyList()
        // The duplicate walk takes the subject together with the entries, and A12 answers it as a duplicate
        // parameter — so naming a type class on either row would be the wrong class on a correct rejection.
        expected["the SUBJECT itself in its own list"] = emptyList()
        expected["one entry written twice"] = emptyList()
        // The subject gates run on this list form too, which is what makes the two spellings one method.
        expected["a subject the head gate refuses"] = listOf(onlyThreeKinds)
        expected["an entry waiving value validation"] = listOf(rawType)
        assertEquals(expected, actual, "what a list of field entries must be")
    }

    @Test
    fun whichConstantsAreOutsideTheDeclaredDomain() {
        val actual = mutableMapOf<String, List<String>>()
        actual["every constant declared — the control"] =
            list("FieldValueIncludedInValueList(Tier, \\\"BASIC\\\", \\\"PLUS\\\")")
        actual["one constant outside the domain"] =
            list("FieldValueIncludedInValueList(Tier, \\\"BASIC\\\", \\\"NOPE\\\")")
        actual["the NEGATED carrier, measured separately"] =
            list("FieldValueNotIncludedInValueList(Tier, \\\"NOPE\\\")")
        actual["two constants outside it — one finding each"] =
            list("FieldValueIncludedInValueList(Tier, \\\"NOPE\\\", \\\"ALSO\\\")")
        actual["a STRING field's value list"] =
            list("FieldValueIncludedInValueList(Reference, \\\"anything\\\")")
        actual["a multi-member QUANTIFIER, whose class is another family's"] =
            list("AtLeastOneFieldValueIncludedInValueList(Tier, Segment In \\\"NOPE\\\")")

        val expected = mutableMapOf<String, List<String>>()
        expected["every constant declared — the control"] = emptyList()
        expected["one constant outside the domain"] = listOf(outsideDomain)
        expected["the NEGATED carrier, measured separately"] = listOf(outsideDomain)
        // A12 stops at its first offending constant; saying more about one call costs an author nothing and
        // is the multiplicity this family has always had.
        expected["two constants outside it — one finding each"] = listOf(outsideDomain, outsideDomain)
        // A string field has no domain, so its list is admitted whatever it carries — `isLikeString` is a
        // `continue` in A12's own loop, and reporting here would refuse every string value list.
        expected["a STRING field's value list"] = emptyList()
        expected["a multi-member QUANTIFIER, whose class is another family's"] =
            listOf("MVK_INVALID_STRING_CONSTANT_FOR_ENUMERATION_OR_CATEGORY")
        assertEquals(expected, actual, "which constants are outside the declared domain")
    }

    /** A quote inside a constant is A12's doubled `""`, and the domain comparison is on the VALUE. */
    @Test
    fun anEmbeddedQuoteIsComparedAsOneCharacter() {
        val codes = check(
            ModelSource(
                """
                {"header":{"id":"policy-quoted","locales":[{"code":"en_US"}]},
                 "content":{"modelRoot":{"rootGroups":[
                  {"type":"Group","id":"g0","name":"Policy","Group":{"elements":[
                    {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
                    {"type":"Field","id":"f1","name":"Tier","Field":{"fieldType":{"type":"EnumerationType",
                      "EnumerationType":{"values":[{"value":"A\"B"}]}}}},
                    {"type":"Rule","id":"r0","name":"Probe","Rule":{
                      "errorCondition":"FieldFilled(Reference) And FieldValueIncludedInValueList(Tier, \"A\"\"B\")",
                      "errorEntityRelPath":"../Reference",
                      "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}}
                  ]}}
                 ]}}}
                """.trimIndent(),
            ),
        ).findings.map { it.code }

        assertEquals(emptyList(), codes.filter { it == outsideDomain })
    }
}
