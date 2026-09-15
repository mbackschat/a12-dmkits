package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of the four characters A12 refuses in an enumeration's declared texts.
 *
 * Mirrors `rulekit.validate.laws.EnumValueForbiddenCharactersLawsTest` on the same insurance shape. The rows
 * that carry the risk are the ones a family keyed on "unsafe text" would get wrong: markup is admitted, a
 * line break answers to a gate that names no class, and a label for an UNDECLARED locale is admitted however
 * it is spelled.
 *
 * The second grid is this checker's own silences, which the laws twin has no reason to carry: A12 refuses a
 * category NAME too, under a different law, and this family stays out of it.
 */
class EnumTextForbiddenCharactersTest {

    private val internal = "MVK_INTERNAL_VALUE_INVALID"

    private val display = "MVK_DISPLAY_VALUE_INVALID"

    private val category = "MVK_CATEGORY_VALUE_INVALID"

    private val lineBreak = "RK_ENUM_TEXT_LINE_BREAK"

    @Test
    fun theFourCharactersAndTheCarrierThatPicksTheClass() {
        val actual = mutableMapOf<String, List<String>>()
        actual["two clean values — the liveness control"] = codes("""{"value":"BASIC"},{"value":"PLUS"}""")
        actual["a value carrying a TAB"] = codes("""{"value":"BASIC"},{"value":"PL\tUS"}""")
        actual["a value carrying a PIPE"] = codes("""{"value":"BASIC"},{"value":"PL|US"}""")
        actual["a value carrying a NEWLINE"] = codes("""{"value":"BASIC"},{"value":"PL\nUS"}""")
        actual["a value carrying a CARRIAGE RETURN"] = codes("""{"value":"BASIC"},{"value":"PL\rUS"}""")
        actual["a value carrying MARKUP — the control A12 admits"] =
            codes("""{"value":"BASIC"},{"value":"PL<US"}""")
        actual["a LABEL for a declared locale carrying a tab"] = codes(
            """{"value":"BASIC","label":[{"locale":"en_US","text":"Basic"}]},""" +
                """{"value":"PLUS","label":[{"locale":"en_US","text":"Pl\tus"}]}""",
        )
        actual["a CATEGORY token carrying a tab"] = codes(
            """{"value":"BASIC"},{"value":"PLUS"}""",
            ""","categories":[{"name":"Band","values":["LOW","HI\tGH"]}]""",
        )
        actual["one value carrying a tab AND a pipe — one text, one finding"] =
            codes("""{"value":"BASIC"},{"value":"PL\t|US"}""")
        actual["BOTH values carrying a tab"] = codes("""{"value":"BA\tSIC"},{"value":"PL\tUS"}""")
        actual["a value and its own label each carrying a tab"] = codes(
            """{"value":"BASIC","label":[{"locale":"en_US","text":"Basic"}]},""" +
                """{"value":"PL\tUS","label":[{"locale":"en_US","text":"Pl\tus"}]}""",
        )
        actual["a NEWLINE in one value and a tab in another"] =
            codes("""{"value":"BA\nSIC"},{"value":"PL\tUS"}""")
        actual["BOTH values carrying a newline — one refusal for the field"] =
            codes("""{"value":"BA\nSIC"},{"value":"PL\nUS"}""")
        actual["a value carrying a tab AND a newline — the break answers instead"] =
            codes("""{"value":"BASIC"},{"value":"PL\t\nUS"}""")

        val expected = mutableMapOf<String, List<String>>()
        expected["two clean values — the liveness control"] = emptyList()
        expected["a value carrying a TAB"] = listOf(internal)
        expected["a value carrying a PIPE"] = listOf(internal)
        expected["a value carrying a NEWLINE"] = listOf(lineBreak)
        expected["a value carrying a CARRIAGE RETURN"] = listOf(lineBreak)
        expected["a value carrying MARKUP — the control A12 admits"] = emptyList()
        expected["a LABEL for a declared locale carrying a tab"] = listOf(display)
        expected["a CATEGORY token carrying a tab"] = listOf(category)
        expected["one value carrying a tab AND a pipe — one text, one finding"] = listOf(internal)
        expected["BOTH values carrying a tab"] = listOf(internal, internal)
        expected["a value and its own label each carrying a tab"] = listOf(internal, display)
        // The break pre-empts the coded class within its OWN text only, and A12 states it first.
        expected["a NEWLINE in one value and a tab in another"] = listOf(lineBreak, internal)
        expected["BOTH values carrying a newline — one refusal for the field"] = listOf(lineBreak)
        expected["a value carrying a tab AND a newline — the break answers instead"] = listOf(lineBreak)
        assertEquals(expected, actual, "which character in which carrier draws which class")
    }

    /**
     * What this family declines to say, each for a measured reason rather than an omission.
     *
     * The undeclared-locale row is the over-rejection guard: A12 ADMITS that text, so a family judging every
     * label present would refuse a model it takes. The category-name row is the opposite direction — A12
     * refuses it, under a NAME alphabet whose sentence names no class — so reporting one of this family's
     * codes there would state the wrong law, and the silence is a disclosed under-report.
     */
    @Test
    fun theTwoNeighbouringTextsThisFamilyStaysOutOf() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a label for an UNDECLARED locale carrying a tab"] = codes(
            """{"value":"BASIC","label":[{"locale":"de_DE","text":"Ba\tsis"}]},""" +
                """{"value":"PLUS","label":[{"locale":"de_DE","text":"Plus"}]}""",
        )
        actual["a category NAME carrying a tab"] = codes(
            """{"value":"BASIC"},{"value":"PLUS"}""",
            ""","categories":[{"name":"Ba\tnd","values":["LOW","HIGH"]}]""",
        )

        val expected = mutableMapOf<String, List<String>>()
        expected["a label for an UNDECLARED locale carrying a tab"] = emptyList()
        expected["a category NAME carrying a tab"] = emptyList()
        assertEquals(expected, actual, "the neighbouring texts this family stays out of")
    }

    /**
     * The shapes it could not read, published so silence is never taken for agreement.
     *
     * The control matters as much as the two rows: without it they would be compatible with the report always
     * carrying such a gap.
     */
    @Test
    fun theUnreadableShapesArePublishedAsCoverageGaps() {
        val mine = { source: String ->
            check(ModelSource(source)).gaps
                .filter { it.reason.contains("the characters A12 refuses in one") }
                .map { it.at to it.reason.substringAfterLast(": ") }
        }
        assertEquals(
            listOf<Pair<String?, String>>("/Policy/Tier" to "a `values` entry spells no string `value`"),
            mine(policy("""{"value":"BASIC"},{"nope":"PLUS"}""")),
        )
        assertEquals(
            listOf<Pair<String?, String>>("/Policy/Tier" to "a category token is not a string"),
            mine(
                policy(
                    """{"value":"BASIC"},{"value":"PLUS"}""",
                    ""","categories":[{"name":"Band","values":["LOW",7]}]""",
                ),
            ),
        )
        assertEquals(emptyList(), mine(policy("""{"value":"BASIC"},{"value":"PLUS"}""")))
    }

    private fun codes(values: String, categories: String = ""): List<String> =
        check(ModelSource(policy(values, categories))).findings
            .filter { it.code in setOf(internal, display, category, lineBreak) }
            .map { it.code }

    /** The policy sheet with one enumeration whose body the row spells. */
    private fun policy(values: String, categories: String = ""): String = """
        {"header":{"id":"policy-enum-characters","locales":[{"code":"en_US"}]},
         "content":{"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Policy","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Field","id":"f1","name":"Tier","Field":{"fieldType":{
              "type":"EnumerationType","EnumerationType":{"values":[$values]$categories}}}}
          ]}}
         ]}}}
    """.trimIndent()
}
