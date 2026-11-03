package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of [KERNEL-FINDINGS KF216] — an Enumeration's categories against its values.
 *
 * Every row mirrors one measured against the real kernel by
 * `rulekit.validate.laws.EnumCategoryAlignmentLawsTest` (the law and its positional discriminator) and
 * `EnumCategoryWireShapeLawsTest` (the member names, through stored JSON).
 *
 * **Multiplicity is asserted rather than membership**, because A12 reports this law once per unpaired
 * element: two values under an empty token list draw two notifications, and a family reporting the
 * misalignment once would be reproducing a different fact.
 */
class EnumCategoriesTest {

    /** A model whose single field is an Enumeration carrying [body] verbatim. */
    private fun report(body: String) = checkRead(
        ModelSource(
            """
            {"header":{"modelVersion":"29.4.0","id":"catalog","locales":[{"code":"en_US"}]},
             "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Catalog","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Tier","Field":{"fieldType":{
                  "type":"EnumerationType","EnumerationType":$body}}}
              ]}}
             ]}}}
            """.trimIndent(),
        ),
    )

    private fun codes(body: String): List<String> = report(body).findings.map { it.code }

    private fun withTokens(tokens: String) =
        """{"values":[{"value":"BASIC"},{"value":"PLUS"}],"categories":[{"name":"Band","values":$tokens}]}"""

    private val missingCategory = listOf("MVK_MISSING_CATEGORY_VALUE")
    private val missingInternal = listOf("MVK_MISSING_INTERNAL_VALUE")

    @Test
    fun aCategorysTokensMustMatchTheEnumsValuesInNumber() {
        val actual = mutableMapOf<String, List<String>>()
        actual["two values, two tokens — the control"] = codes(withTokens("""["STANDARD","HIGH"]"""))
        // Same length, disjoint tokens: the row that says the pairing is positional rather than by name.
        actual["same length, DISJOINT tokens"] = codes(withTokens("""["X","Y"]"""))
        actual["one token short"] = codes(withTokens("""["STANDARD"]"""))
        actual["one token too many"] = codes(withTokens("""["STANDARD","HIGH","TOP"]"""))
        actual["no tokens at all"] = codes(withTokens("[]"))
        actual["no categories at all"] = codes("""{"values":[{"value":"BASIC"},{"value":"PLUS"}]}""")
        actual["two categories, one of them short"] = codes(
            """{"values":[{"value":"BASIC"},{"value":"PLUS"}],"categories":[""" +
                """{"name":"Band","values":["STANDARD","HIGH"]},{"name":"Tier","values":["ONE"]}]}""",
        )

        val expected = mutableMapOf<String, List<String>>()
        expected["two values, two tokens — the control"] = emptyList()
        expected["same length, DISJOINT tokens"] = emptyList()
        expected["one token short"] = missingCategory
        expected["one token too many"] = missingInternal
        // Once per unpaired VALUE, which is what makes this a cardinality law and not a boolean one.
        expected["no tokens at all"] = missingCategory + missingCategory
        expected["no categories at all"] = emptyList()
        expected["two categories, one of them short"] = missingCategory
        assertEquals(expected, actual, "a category's tokens pair with the enum's values by position")
    }

    /** The finding names the unpaired VALUE, which is the part an author cannot see in a long token list. */
    @Test
    fun theFindingNamesTheValueThatTookNoToken() {
        val finding = checkRead(
            ModelSource(
                """
                {"header":{"modelVersion":"29.4.0","id":"catalog","locales":[{"code":"en_US"}]},
                 "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
                  {"type":"Group","id":"g0","name":"Catalog","Group":{"elements":[
                    {"type":"Field","id":"f0","name":"Tier","Field":{"fieldType":{
                      "type":"EnumerationType","EnumerationType":{
                        "values":[{"value":"BASIC"},{"value":"PLUS"},{"value":"MAX"}],
                        "categories":[{"name":"Band","values":["STANDARD","HIGH"]}]}}}}
                  ]}}
                 ]}}}
                """.trimIndent(),
            ),
        ).findings.single()
        assertEquals("MVK_MISSING_CATEGORY_VALUE", finding.code)
        assertEquals("/Catalog/Tier", finding.at)
        // The THIRD value is the unpaired one — the two tokens were consumed left to right, which is the
        // asymmetry a name-matching account cannot produce.
        assertEquals(true, finding.explain.contains("`MAX`"), finding.explain)
    }

    /**
     * A value entry with no `value` keeps its position in the pairing (KERNEL-FINDINGS KF655): the token
     * paired with it and the token past the values each classify nothing. Measured on the kernel by
     * `adapter.laws.CheckerReaderBindingAgreementTest`; this row locks the checker's half where no kernel runs.
     */
    @Test
    fun aValueEntryWithNoTokenKeepsItsPosition() {
        assertEquals(
            listOf("MVK_MISSING_INTERNAL_VALUE", "MVK_MISSING_INTERNAL_VALUE"),
            codes("""{"values":[{}],"categories":[{"name":"Band","values":["A","B"]}]}""")
                .filter { it == "MVK_MISSING_INTERNAL_VALUE" },
        )
    }

    /**
     * Shapes nobody has put to A12, each deliberately silent: shapes that READ and that no grid covers. A verdict
     * on one would be this checker's own reading dressed as A12's.
     */
    @Test
    fun anUnmeasuredShapeIsLeftToA12() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a value entry whose `value` is a number"] = codes(
            """{"values":[{"value":5}],"categories":[{"name":"Band","values":["A","B"]}]}""",
        )
        actual["a category that is not an object"] = codes(
            """{"values":[{"value":"BASIC"}],"categories":["Band"]}""",
        )
        actual["a category whose tokens are not an array"] = codes(
            """{"values":[{"value":"BASIC"}],"categories":[{"name":"Band","values":"STANDARD"}]}""",
        )

        val expected = actual.keys.associateWith { emptyList<String>() }
        assertEquals(expected, actual, "an unmeasured shape draws nothing")
    }

    /**
     * Three shapes that were left to A12 until kernel 31.1.1 measured them, each mirroring a row of
     * `adapter.laws.CheckerEmptyMemberAgreementTest`: a nameless category is A12's `MVK_NAME_MISSING` and is paired
     * with nothing, an absent token list is the empty one, and an enumeration with no values is not built.
     */
    @Test
    fun theShapesKernel31MeasuredAreJudged() {
        val actual = linkedMapOf(
            "a category with no `name`" to codes("""{"values":[{"value":"BASIC"}],"categories":[{"values":[]}]}"""),
            "categories with no `values` member at all" to
                codes("""{"values":[{"value":"BASIC"}],"categories":[{"name":"Band"}]}"""),
            "no values at all, with tokens" to
                codes("""{"values":[],"categories":[{"name":"Band","values":["X","Y"]}]}"""),
        )
        val expected = linkedMapOf(
            "a category with no `name`" to listOf("MVK_NAME_MISSING"),
            "categories with no `values` member at all" to listOf("MVK_MISSING_CATEGORY_VALUE"),
            "no values at all, with tokens" to listOf(CK_MODEL_NOT_BUILDABLE),
        )
        assertEquals(expected, actual, "the shapes A12 31.1.1 answers")
    }

    /**
     * Each of those silences is PUBLISHED against the declaration it concerns.
     *
     * The report names this family in `checksCompleted` whether or not it could read the body, so without
     * the gap a consumer reads "paired, aligned" for an enumeration that was never paired at all.
     */
    @Test
    fun anUnmeasuredShapeIsPublishedAsACoverageGap() {
        val actual = mutableMapOf<String, List<String?>>()
        actual["a value entry whose `value` is a number"] = gapPaths(
            """{"values":[{"value":5}],"categories":[{"name":"Band","values":["A","B"]}]}""",
        )
        actual["a category that is not an object"] = gapPaths(
            """{"values":[{"value":"BASIC"}],"categories":["Band"]}""",
        )
        actual["no values at all, with tokens"] = gapPaths(
            """{"values":[],"categories":[{"name":"Band","values":["X","Y"]}]}""",
        )
        // The controls: a judged pairing and an enum with no categories at all say nothing. The second
        // matters most — categories are optional, so a gap there would describe every ordinary enum.
        actual["an aligned pairing — the control"] = gapPaths(withTokens("""["S","P"]"""))
        actual["no categories at all — the control"] =
            gapPaths("""{"values":[{"value":"BASIC"}]}""")

        val expected = mutableMapOf<String, List<String?>>()
        expected["a value entry whose `value` is a number"] = listOf("/Catalog/Tier")
        expected["a category that is not an object"] = listOf("/Catalog/Tier")
        expected["no values at all, with tokens"] = listOf("/Catalog/Tier")
        expected["an aligned pairing — the control"] = emptyList()
        expected["no categories at all — the control"] = emptyList()
        assertEquals(expected, actual, "a shape this family could not pair is disclosed, not simply dropped")
    }

    /**
     * Two surplus tokens draw two findings that a consumer can tell apart.
     *
     * A12 raises one per surplus token, so the multiplicity is the law — and findings identical in code,
     * path and text would be folded by any consumer deduplicating on those three, losing exactly what this
     * family reproduces. The offending token is what makes them distinct, as it already does on the other
     * side of the pairing.
     */
    @Test
    fun eachSurplusTokenNamesItself() {
        val findings = report(withTokens("""["S","P","TOP","MAX"]""")).findings
        assertEquals(missingInternal + missingInternal, findings.map { it.code })
        assertEquals(2, findings.map { it.explain }.distinct().size, findings.joinToString("\n") { it.explain })
        assertEquals(true, findings.any { it.explain.contains("`TOP`") }, findings.first().explain)
        assertEquals(true, findings.any { it.explain.contains("`MAX`") }, findings.last().explain)
    }

    private fun gapPaths(body: String): List<String?> =
        report(body).gaps.filter { it.reason.contains("were not paired with its values") }.map { it.at }
}
