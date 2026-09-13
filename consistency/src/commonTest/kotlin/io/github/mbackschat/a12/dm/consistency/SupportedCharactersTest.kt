package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of [KERNEL-FINDINGS KF317] — a declared enum value against the model's character set.
 *
 * Every row mirrors one measured against the real kernel by
 * `rulekit.validate.laws.SupportedCharactersDeclarationGateLawsTest`.
 *
 * **The silent rows carry the risk, and there are three kinds.** A restriction this checker cannot read as
 * singletons and ranges, a declared string that is not an enum value, and a value carrying a surrogate: each
 * is a model A12 accepts or has never been asked about, so a finding on one would be this checker's own
 * reading presented as A12's.
 */
class SupportedCharactersTest {

    /** A model restricted to [definition], whose one enum field declares [values]. */
    private fun codes(definition: String?, vararg values: String): List<String> = check(
        ModelSource(
            """
            {"header":{"id":"shop","locales":[{"code":"en_US"}]},
             "content":{${definition?.let { """"modelConfig":{"supportedCharacters":$it},""" } ?: ""}
              "modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Shop","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Delivery","Field":{"fieldType":{
                  "type":"EnumerationType","EnumerationType":{"values":[${
                values.joinToString(",") { """{"value":"$it"}""" }
            }]}}}}
              ]}}
             ]}}}
            """.trimIndent(),
        ),
    ).findings.map { it.code }

    private val invalid = listOf("MVK_ENUM_VALUE_WITH_INVALID_CHARACTER")

    @Test
    fun anEnumValueIsJudgedPerDistinctOffendingCharacter() {
        val actual = mutableMapOf<String, List<String>>()
        actual["no restriction — the control"] = codes(null, "beta")
        actual["a conforming spelling — the control"] = codes("""["A-Z"]""", "BETA")
        actual["four offending characters"] = codes("""["A-Z"]""", "beta")
        // A repeat is folded, and the same offenders in a second value are reported again: distinctness is
        // scoped to the value, which the two rows measure separately because neither implies the other.
        actual["the same character twice"] = codes("""["A-Z"]""", "aa")
        actual["two values sharing the same two offenders"] = codes("""["A-Z"]""", "ab", "ba")
        actual["a singleton entry beside the range"] = codes("""["A-Z","b"]""", "beta")
        actual["both range ends"] = codes("""["A-Z"]""", "AZ")
        actual["one below a range's start"] = codes("""["B-Y"]""", "A")

        val expected = mutableMapOf<String, List<String>>()
        expected["no restriction — the control"] = emptyList()
        expected["a conforming spelling — the control"] = emptyList()
        expected["four offending characters"] = invalid + invalid + invalid + invalid
        expected["the same character twice"] = invalid
        expected["two values sharing the same two offenders"] = invalid + invalid + invalid + invalid
        expected["a singleton entry beside the range"] = invalid + invalid + invalid
        expected["both range ends"] = emptyList()
        expected["one below a range's start"] = invalid
        assertEquals(expected, actual, "one finding per distinct offending character of each value")
    }

    /** The finding names the character and the value, which is what an author has to change. */
    @Test
    fun theFindingNamesTheCharacterAndTheValueItSitsIn() {
        val finding = check(
            ModelSource(
                """
                {"header":{"id":"shop","locales":[{"code":"en_US"}]},
                 "content":{"modelConfig":{"supportedCharacters":["A-Z"]},
                  "modelRoot":{"rootGroups":[
                  {"type":"Group","id":"g0","name":"Shop","Group":{"elements":[
                    {"type":"Field","id":"f0","name":"Delivery","Field":{"fieldType":{
                      "type":"EnumerationType","EnumerationType":{"values":[{"value":"FASTx"}]}}}}
                  ]}}
                 ]}}}
                """.trimIndent(),
            ),
        ).findings.single()
        assertEquals("MVK_ENUM_VALUE_WITH_INVALID_CHARACTER", finding.code)
        assertEquals("/Shop/Delivery", finding.at)
        assertEquals(true, finding.explain.contains("`FASTx` carries `x`"), finding.explain)
    }

    /** Everything the family deliberately says nothing about. */
    @Test
    fun anUnreadableRestrictionOrAnUnmeasuredCarrierIsLeftToA12() {
        val actual = mutableMapOf<String, List<String>>()
        // A grapheme entry is legal and its membership test is a trie walk over code-point SEQUENCES, so a
        // per-character reading of the whole restriction would refuse values A12 admits.
        actual["a restriction carrying a grapheme entry"] = codes("""["A-Z","é"]""", "beta")
        actual["a reversed range"] = codes("""["Z-A"]""", "beta")
        actual["an empty restriction list"] = codes("""[]""", "beta")
        actual["a non-string entry"] = codes("""[7]""", "beta")
        val expected = actual.keys.associateWith { emptyList<String>() }
        assertEquals(expected, actual, "an unreadable restriction leaves the whole model unjudged")
    }
}
