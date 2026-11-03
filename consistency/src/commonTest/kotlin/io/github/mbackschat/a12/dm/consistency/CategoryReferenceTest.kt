package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of A12's `checkCategory` gate.
 *
 * Every row mirrors one measured against the real kernel by
 * `rulekit.validate.laws.CategoryReferenceGateOrderLawsTest`. The silent rows carry the risk: three other
 * classes answer ahead of these two, and each would be a wrong class rather than a harmless extra.
 */
class CategoryReferenceTest {

    private val unknown = "MVK_CATEGORY_UNKOWN"
    private val notAnEnum = "MVK_NO_ENUMERATION"

    /** A policy model whose one rule carries [condition]; `Tier` declares the single category `Band`. */
    private fun codes(condition: String): List<String> = checkRead(
        ModelSource(
            """
            {"header":{"modelVersion":"29.4.0","id":"policy-categories","locales":[{"code":"en_US"}]},
             "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Policy","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"f1","name":"Tier","Field":{"fieldType":{"type":"EnumerationType",
                  "EnumerationType":{"values":[{"value":"BASIC"},{"value":"PLUS"}],
                  "categories":[{"name":"Band","values":["LOW","HIGH"]}]}}}},
                {"type":"Group","id":"g1","name":"Insured","Group":{"elements":[
                  {"type":"Field","id":"f2","name":"InsuredName","Field":{"fieldType":{"type":"StringType"}}}
                ]}},
                {"type":"Rule","id":"r0","name":"Probe","Rule":{
                  "errorCondition":"CONDITION_HERE","errorEntityRelPath":"../Reference",
                  "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}}
              ]}}
             ]}}}
            """.trimIndent().replace("CONDITION_HERE", condition),
        ),
    ).findings.map { it.code }

    private fun arrow(probe: String): List<String> =
        codes("FieldFilled(Reference) And $probe").filter { it == unknown || it == notAnEnum }

    @Test
    fun whichCategoryAccessesDrawWhichClass() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a declared category — the control"] = arrow("[Tier -> Band] == \\\"LOW\\\"")
        actual["an UNDECLARED category name"] = arrow("[Tier -> NoSuchBand] == \\\"LOW\\\"")
        actual["an arrow on a STRING field"] = arrow("[Reference -> Band] == \\\"LOW\\\"")

        val expected = mutableMapOf<String, List<String>>()
        expected["a declared category — the control"] = emptyList()
        expected["an UNDECLARED category name"] = listOf(unknown)
        expected["an arrow on a STRING field"] = listOf(notAnEnum)

        assertEquals(expected, actual)
    }

    /**
     * The shapes it leaves alone, because A12 answers each with a class from an earlier gate or another law.
     *
     * Resolution runs ahead of `checkCategory`, so an arrow on an undeclared name is `MVK_INVALID_ENTITY`. And
     * the category's own VALUE domain is a third law with a third class — `[Tier -> Band] == "NOPE"` is
     * `MVK_INVALID_STRING_CONSTANT_FOR_ENUM_COMPARISON`, which this family deliberately does not reproduce.
     */
    @Test
    fun theShapesItLeavesAlone() {
        val actual = mutableMapOf<String, List<String>>()
        actual["an arrow on an UNRESOLVABLE name"] = arrow("[NoSuchField -> Band] == \\\"LOW\\\"")
        actual["a declared category, undeclared classification"] = arrow("[Tier -> Band] == \\\"NOPE\\\"")

        assertEquals(
            mapOf("an arrow on an UNRESOLVABLE name" to emptyList(),
                "a declared category, undeclared classification" to emptyList()),
            actual,
        )
    }

    /**
     * An arrow on a GROUP is the spec's own `checkIsField`, asked ahead of both category gates, and it is
     * named ONCE: the bracket around it asks the same question and yields to this family, which raises it
     * where A12 does ([KERNEL-FINDINGS KF482]).
     */
    @Test
    fun anArrowOnAGroupIsTheGroupClassOnce() {
        val groupClass = "MVK_NO_GROUPS_ALLOWED"
        assertEquals(
            listOf(groupClass),
            codes("FieldFilled(Reference) And [Insured -> Band] == \\\"LOW\\\"").filter { it == groupClass },
        )
    }

    /** An Enumeration declaring NO categories still answers the reference, rather than falling silent. */
    @Test
    fun anEnumerationWithNoCategoriesAtAllStillRefusesTheName() {
        val codes = checkRead(
            ModelSource(
                """
                {"header":{"modelVersion":"29.4.0","id":"policy-plain","locales":[{"code":"en_US"}]},
                 "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
                  {"type":"Group","id":"g0","name":"Policy","Group":{"elements":[
                    {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
                    {"type":"Field","id":"f1","name":"Tier","Field":{"fieldType":{"type":"EnumerationType",
                      "EnumerationType":{"values":[{"value":"BASIC"}]}}}},
                    {"type":"Rule","id":"r0","name":"Probe","Rule":{
                      "errorCondition":"FieldFilled(Reference) And [Tier -> Band] == \"LOW\"",
                      "errorEntityRelPath":"../Reference",
                      "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}}
                  ]}}
                 ]}}}
                """.trimIndent(),
            ),
        ).findings

        assertEquals(listOf(unknown), codes.map { it.code }.filter { it == unknown })
    }
}
