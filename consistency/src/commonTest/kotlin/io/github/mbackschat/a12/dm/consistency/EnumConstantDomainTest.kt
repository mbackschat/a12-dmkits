package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of A12's value-list enum-domain check.
 *
 * Every row mirrors one measured against the real kernel by
 * `rulekit.validate.laws.EnumConstantDomainLawsTest`. The DECLINED rows outnumber the reporting ones and are
 * where the risk is: A12 answers three neighbouring shapes with three OTHER classes, and one — a string
 * field's list — it admits outright.
 */
class EnumConstantDomainTest {

    private val outsideDomain = "MVK_INVALID_STRING_CONSTANT_FOR_ENUMERATION"

    /** A policy model whose one rule carries [condition]; `Tier` declares two values and one category. */
    private fun codes(condition: String): List<String> = check(
        ModelSource(
            """
            {"header":{"id":"policy-enum-constants","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Policy","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"f1","name":"Tier","Field":{"fieldType":{"type":"EnumerationType",
                  "EnumerationType":{"values":[{"value":"BASIC"},{"value":"PLUS"}],
                  "categories":[{"name":"Band","values":["LOW","HIGH"]}]}}}},
                {"type":"Field","id":"f2","name":"Typed","Field":{"fieldType":{"type":"TypeDefType",
                  "TypeDefType":{"typeDefinitionId":"TIER_1"}}}},
                {"type":"Rule","id":"r0","name":"Probe","Rule":{
                  "errorCondition":"CONDITION_HERE","errorEntityRelPath":"../Reference",
                  "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}}
              ]}}
             ]}}}
            """.trimIndent().replace("CONDITION_HERE", condition),
        ),
    ).findings.map { it.code }

    private fun list(probe: String): List<String> =
        codes("FieldFilled(Reference) And $probe").filter { it == outsideDomain }

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

        val expected = mutableMapOf<String, List<String>>()
        expected["every constant declared — the control"] = emptyList()
        expected["one constant outside the domain"] = listOf(outsideDomain)
        expected["the NEGATED carrier, measured separately"] = listOf(outsideDomain)
        expected["two constants outside it — one finding each"] = listOf(outsideDomain, outsideDomain)

        assertEquals(expected, actual)
    }

    /**
     * The shapes it leaves alone, each because A12 answers with a different class or admits outright.
     *
     * A STRING field has no domain, so its list is legal whatever it carries — `isLikeString` is a
     * `continue` in A12's own loop, and reporting there would refuse every string value list in the estate.
     * A `TypeDefType` field may BE that Enumeration, and this checker keeps type definitions' ids alone.
     * A categorized reference and the multi-member quantifiers each answer with their own class, measured.
     */
    @Test
    fun theShapesItLeavesAlone() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a STRING field's value list"] =
            list("FieldValueIncludedInValueList(Reference, \\\"anything\\\")")
        actual["a TYPEDEF field's value list"] =
            list("FieldValueIncludedInValueList(Typed, \\\"NOPE\\\")")
        actual["a CATEGORIZED reference"] =
            list("FieldValueIncludedInValueList(Tier -> Band, \\\"NOPE\\\")")
        actual["a multi-member QUANTIFIER"] =
            list("AtLeastOneFieldValueIncludedInValueList(Tier, Tier In \\\"NOPE\\\")")

        assertEquals(
            mapOf("a STRING field's value list" to emptyList(),
                "a TYPEDEF field's value list" to emptyList(),
                "a CATEGORIZED reference" to emptyList(),
                "a multi-member QUANTIFIER" to emptyList()),
            actual,
        )
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
