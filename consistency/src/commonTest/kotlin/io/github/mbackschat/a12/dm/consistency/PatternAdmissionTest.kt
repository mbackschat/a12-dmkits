package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of [KERNEL-FINDINGS KF165]'s declared-pattern gate.
 *
 * Every row mirrors one measured against the real kernel by
 * `rulekit.validate.laws.DeclaredPatternGateLawsTest`, and the two are the same grid on purpose: this gate
 * REFUSES models, so an admitted pattern reported here is the one direction the checker may never take.
 *
 * **The escaped rows carry the risk.** The gate reads source TEXT, so `a\*+` is refused and `a\+\+` is not
 * — an implementation that consumed escapes while scanning would get both wrong in the safe direction and
 * `\P{L}` wrong in the unsafe one, which is exactly what the dmkits' Java preflight did for two months.
 */
class PatternAdmissionTest {

    /** The message A12 requires beside any declared pattern, so no fixture here measures two laws at once. */
    private val patternMessage = """"errorMessage":[{"locale":"en_US","text":"the reference is malformed"}]"""

    /**
     * A model whose one string field declares [pattern], or declares none when it is null.
     *
     * The error message rides along with every pattern because A12 requires the pair — a pattern without one
     * draws `MVK_ERROR_MESSAGE_AND_PATTERN_INVALID` from [stringTypeMessageFindings], and a fixture missing
     * it would measure that law here instead of this one.
     */
    private fun codes(pattern: String?): List<String> = checkRead(
        ModelSource(
            """
            {"header":{"id":"catalog","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Catalog","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"${
                pattern?.let { ""","StringType":{"pattern":"${it.replace("\\", "\\\\")}",$patternMessage}""" } ?: ""
            }}}}
              ]}}
             ]}}}
            """.trimIndent(),
        ),
    ).findings.map { it.code }

    private val refused = listOf("MVK_PATTERN_INVALID")

    @Test
    fun aDeclaredPatternIsJudgedOnItsSourceTextAgainstA12sBlacklist() {
        val actual = mutableMapOf<String, List<String>>()
        actual["no pattern at all — the control"] = codes(null)
        actual["an ordinary pattern — the control"] = codes("[A-Z]{2}[0-9]+")

        // The blacklist's four alternatives, one witness each.
        actual["a possessive quantifier"] = codes("a++")
        actual["a possessive bounded quantifier"] = codes("a{2}+")
        actual["a lookbehind"] = codes("(?<=a)b")
        actual["a NAMED capture group"] = codes("(?<name>a)b")
        actual["an atomic group"] = codes("(?>a)b")
        actual["an input-start escape"] = codes("\\Aabc")
        actual["a lowercase property escape"] = codes("\\p{L}")
        actual["a nested unescaped bracket"] = codes("[a[b]")

        // The rows that say the gate reads TEXT rather than the expression the text spells.
        actual["an ESCAPED star followed by a quantifier"] = codes("a\\*+")
        actual["escaped brackets, one after another"] = codes("\\[a\\[b")
        actual["two escaped plus signs"] = codes("a\\+\\+")

        // Uppercase \P is NOT listed — the refusal of `\P{L}+` is the `}+` at its end, not the escape.
        actual["an UPPERCASE property escape, unquantified"] = codes("\\P{L}")
        actual["an UPPERCASE property escape, quantified"] = codes("\\P{L}+")

        val expected = mutableMapOf<String, List<String>>()
        expected["no pattern at all — the control"] = emptyList()
        expected["an ordinary pattern — the control"] = emptyList()
        expected["a possessive quantifier"] = refused
        expected["a possessive bounded quantifier"] = refused
        expected["a lookbehind"] = refused
        expected["a NAMED capture group"] = refused
        expected["an atomic group"] = refused
        expected["an input-start escape"] = refused
        expected["a lowercase property escape"] = refused
        expected["a nested unescaped bracket"] = refused
        expected["an ESCAPED star followed by a quantifier"] = refused
        expected["escaped brackets, one after another"] = emptyList()
        expected["two escaped plus signs"] = emptyList()
        expected["an UPPERCASE property escape, unquantified"] = emptyList()
        expected["an UPPERCASE property escape, quantified"] = refused
        assertEquals(expected, actual, "the declared-pattern gate, judged on source text")
    }

    /** The finding names the construct, because the pattern alone does not say which adjacency was listed. */
    @Test
    fun theFindingNamesTheListedConstruct() {
        val finding = checkRead(
            ModelSource(
                """
                {"header":{"id":"catalog","locales":[{"code":"en_US"}]},
                 "content":{"modelRoot":{"rootGroups":[
                  {"type":"Group","id":"g0","name":"Catalog","Group":{"elements":[
                    {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType",
                      "StringType":{"pattern":"a\\*+",$patternMessage}}}}
                  ]}}
                 ]}}}
                """.trimIndent(),
            ),
        ).findings.single()
        assertEquals("MVK_PATTERN_INVALID", finding.code)
        assertEquals("/Catalog/Reference", finding.at)
        assertEquals(true, finding.explain.contains("possessive quantifier"), finding.explain)
        assertEquals(true, finding.explain.contains("SOURCE TEXT"), finding.explain)
    }

    /**
     * The half this checker cannot answer is PUBLISHED, and only when the model has a pattern at all.
     *
     * Java regex compilability belongs to one engine; `Regex` here accepts and refuses a different set, so
     * agreeing with A12 would be coincidence. Saying nothing would let a consumer read an admitted pattern
     * as fully checked — and raising it unconditionally would describe every model.
     */
    @Test
    fun theUndecidableHalfIsPublishedAsACoverageGap() {
        val compilability = { pattern: String? ->
            checkRead(
                ModelSource(
                    """
                    {"header":{"id":"catalog","locales":[{"code":"en_US"}]},
                     "content":{"modelRoot":{"rootGroups":[
                      {"type":"Group","id":"g0","name":"Catalog","Group":{"elements":[
                        {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"${
                        pattern?.let { ""","StringType":{"pattern":"$it",$patternMessage}""" } ?: ""
                    }}}}
                      ]}}
                     ]}}}
                    """.trimIndent(),
                ),
            ).gaps.count { it.reason.contains("compiles as a JAVA regular expression") }
        }
        assertEquals(1, compilability("[A-Z]+"), "a model with a pattern discloses the half left to A12")
        assertEquals(0, compilability(null), "a model with no pattern has nothing to disclose")
    }
}
