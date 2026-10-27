package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of the enumeration's value and label grid.
 *
 * Every row mirrors one measured against the real kernel by
 * `rulekit.validate.laws.EnumValueAndLabelLawsTest`. The ADMITTED rows carry the risk: an empty token, a
 * spaced token, a 300-character token, labels written only for an undeclared locale and two labels for one
 * locale are all models A12 takes, and four `MVK_VALUE_*` codes read as though they governed them.
 */
class EnumValueLabelTest {

    private val duplicate = "MVK_INTERNAL_VALUES_DUPLICATE"
    private val countInvalid = "MVK_COUNT_OF_VALUES_INVALID"
    private val displayShort = "MVK_INTERNAL_VALUES_AND_DISPLAY_VALUES"

    /** A model declaring [locales] whose one enumeration field carries [values] as its value list. */
    private fun codes(values: String, vararg locales: String = arrayOf("en_US")): List<String> = checkRead(
        ModelSource(
            """
            {"header":{"id":"catalog","locales":[${locales.joinToString(",") { """{"code":"$it"}""" }}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Catalog","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Tier","Field":{"fieldType":{"type":"EnumerationType",
                  "EnumerationType":{"values":$values}}}}
              ]}}
             ]}}}
            """.trimIndent(),
        ),
    ).findings.map { it.code }

    private fun label(locale: String, text: String) = """"label":[{"locale":"$locale","text":"$text"}]"""

    /**
     * The label TEXTS against each other — a law within ONE language.
     *
     * Every row mirrors one measured by `rulekit.validate.laws.EnumValueAndLabelLawsTest`. The last row is
     * the family's bound: the same text in two different locales is a model A12 takes.
     */
    @Test
    fun theLabelTextsMustBeDistinctWithinOneLanguage() {
        val duplicateLabel = listOf("MVK_DISPLAY_VALUES_DUPLICATE")
        val actual = mutableMapOf<String, List<String>>()
        actual["distinct labels — the control"] = codes(
            """[{"value":"BASIC",${label("en_US", "Basic")}},{"value":"PLUS",${label("en_US", "Plus")}}]""")
        actual["two values sharing one label text"] = codes(
            """[{"value":"BASIC",${label("en_US", "Same")}},{"value":"PLUS",${label("en_US", "Same")}}]""")
        actual["the same text in two DIFFERENT languages"] = codes(
            """[{"value":"BASIC","label":[{"locale":"en_US","text":"Same"},""" +
                """{"locale":"de_DE","text":"Einfach"}]},""" +
                """{"value":"PLUS","label":[{"locale":"en_US","text":"Plus"},""" +
                """{"locale":"de_DE","text":"Same"}]}]""",
            "en_US", "de_DE",
        )

        val expected = mutableMapOf<String, List<String>>()
        expected["distinct labels — the control"] = emptyList()
        expected["two values sharing one label text"] = duplicateLabel
        expected["the same text in two DIFFERENT languages"] = emptyList()
        assertEquals(expected, actual, "an enumeration's label texts against each other")
    }

    @Test
    fun theValueTokensThemselves() {
        val actual = mutableMapOf<String, List<String>>()
        actual["two ordinary values — the control"] = codes("""[{"value":"BASIC"},{"value":"PLUS"}]""")
        actual["the same value twice"] = codes("""[{"value":"BASIC"},{"value":"BASIC"}]""")
        actual["a single value"] = codes("""[{"value":"BASIC"}]""")
        actual["an EMPTY value token"] = codes("""[{"value":""},{"value":"PLUS"}]""")
        actual["a value token with a space"] = codes("""[{"value":"TWO WORDS"},{"value":"PLUS"}]""")
        actual["a very long value token"] = codes("""[{"value":"${"X".repeat(300)}"},{"value":"PLUS"}]""")

        val expected = mutableMapOf<String, List<String>>()
        expected["two ordinary values — the control"] = emptyList()
        expected["the same value twice"] = listOf(duplicate, "RK_ENUM_VALUE_DUPLICATE")
        expected["a single value"] = emptyList()
        expected["an EMPTY value token"] = emptyList()
        expected["a value token with a space"] = emptyList()
        expected["a very long value token"] = emptyList()
        assertEquals(expected, actual, "a declared enumeration's value tokens")
    }

    @Test
    fun thePerLocaleLabelsBesideThem() {
        val actual = mutableMapOf<String, List<String>>()
        actual["no labels at all — the control"] = codes("""[{"value":"BASIC"},{"value":"PLUS"}]""")
        actual["every value labelled for the declared locale"] = codes(
            """[{"value":"BASIC",${label("en_US", "Basic")}},{"value":"PLUS",${label("en_US", "Plus")}}]""",
        )
        actual["only ONE value labelled"] =
            codes("""[{"value":"BASIC",${label("en_US", "Basic")}},{"value":"PLUS"}]""")
        actual["a label for an UNDECLARED locale"] = codes(
            """[{"value":"BASIC",${label("de_DE", "Einfach")}},{"value":"PLUS",${label("de_DE", "Plus")}}]""",
        )
        actual["an EMPTY label text"] =
            codes("""[{"value":"BASIC",${label("en_US", "")}},{"value":"PLUS",${label("en_US", "Plus")}}]""")
        actual["TWO labels for the same locale on one value"] = codes(
            """[{"value":"BASIC","label":[{"locale":"en_US","text":"Basic"},
               {"locale":"en_US","text":"Entry"}]},{"value":"PLUS",${label("en_US", "Plus")}}]""",
        )
        actual["an EMPTY label ARRAY on one value"] =
            codes("""[{"value":"BASIC","label":[]},{"value":"PLUS",${label("en_US", "Plus")}}]""")
        actual["every label text blank"] =
            codes("""[{"value":"BASIC",${label("en_US", "")}},{"value":"PLUS",${label("en_US", "")}}]""")

        val expected = mutableMapOf<String, List<String>>()
        expected["no labels at all — the control"] = emptyList()
        expected["every value labelled for the declared locale"] = emptyList()
        expected["only ONE value labelled"] = listOf(countInvalid)
        expected["a label for an UNDECLARED locale"] = emptyList()
        expected["an EMPTY label text"] = listOf(displayShort)
        expected["TWO labels for the same locale on one value"] = emptyList()
        expected["an EMPTY label ARRAY on one value"] = listOf(countInvalid)
        expected["every label text blank"] = listOf(displayShort)
        assertEquals(expected, actual, "a declared enumeration's per-locale labels")
    }

    @Test
    fun whichDeclaredLocalesTheLabelsMustCover() {
        val bilingual = { values: String -> codes(values, "en_US", "de_DE") }
        val actual = mutableMapOf<String, List<String>>()
        actual["both declared locales labelled"] = bilingual(
            """[{"value":"BASIC","label":[{"locale":"en_US","text":"Basic"},
               {"locale":"de_DE","text":"Einfach"}]},
               {"value":"PLUS","label":[{"locale":"en_US","text":"Plus"},
               {"locale":"de_DE","text":"Plus"}]}]""",
        )
        actual["only ONE declared locale labelled"] = bilingual(
            """[{"value":"BASIC",${label("en_US", "Basic")}},{"value":"PLUS",${label("en_US", "Plus")}}]""",
        )
        actual["one value bilingual, the other English only"] = bilingual(
            """[{"value":"BASIC","label":[{"locale":"en_US","text":"Basic"},
               {"locale":"de_DE","text":"Einfach"}]},{"value":"PLUS",${label("en_US", "Plus")}}]""",
        )

        val expected = mutableMapOf<String, List<String>>()
        expected["both declared locales labelled"] = emptyList()
        expected["only ONE declared locale labelled"] = listOf(displayShort)
        expected["one value bilingual, the other English only"] = listOf(displayShort)
        assertEquals(expected, actual, "which declared locales an enumeration's labels must cover")
    }

    /** The finding says which locale is short, since a bilingual model has two candidates. */
    @Test
    fun theFindingNamesTheUnansweredLocale() {
        val finding = checkRead(
            ModelSource(
                """
                {"header":{"id":"catalog","locales":[{"code":"en_US"},{"code":"de_DE"}]},
                 "content":{"modelRoot":{"rootGroups":[
                  {"type":"Group","id":"g0","name":"Catalog","Group":{"elements":[
                    {"type":"Field","id":"f0","name":"Tier","Field":{"fieldType":{"type":"EnumerationType",
                      "EnumerationType":{"values":[{"value":"BASIC","label":[
                        {"locale":"en_US","text":"Basic"}]}]}}}}
                  ]}}
                 ]}}}
                """.trimIndent(),
            ),
        ).findings.single()
        assertEquals(displayShort, finding.code)
        assertEquals("/Catalog/Tier", finding.at)
        assertEquals(true, finding.explain.contains("`de_DE`"), finding.explain)
    }
}
