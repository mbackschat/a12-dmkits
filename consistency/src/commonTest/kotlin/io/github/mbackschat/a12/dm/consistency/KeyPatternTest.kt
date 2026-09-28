package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The checker uses the shared translator's represented domain and the host's whole-value Java meaning. */
class KeyPatternTest {
    private fun matches(pattern: String, value: String): Boolean? = KeyPattern.parse(pattern)?.matches(value)

    @Test
    fun theKernelPatternPopulationAgreesOnEachPortableTarget() {
        assertEquals(90, SyntaxFixtureData.keyPatterns.size)
        for (json in SyntaxFixtureData.keyPatterns) {
            val row = Json.parseToJsonElement(json).jsonObject
            val expected = row.getValue("classes").jsonArray.map { it.jsonPrimitive.content }.toSet()
            val report = checkRead(ModelSource(row.getValue("model").jsonPrimitive.content))
            assertEquals(expected, report.findings.filter { it.justifiesRejection }.map { it.code }.toSet(),
                row.getValue("id").jsonPrimitive.content)
        }
    }

    @Test
    fun representedPatternsMatchWholeValues() {
        val rows = listOf(
            Triple(".{2}", "AB", true), Triple(".{2}", "A", false),
            Triple("\\d{2}", "12", true), Triple("\\d{2}", "1A", false),
            Triple("(AB|CD)", "CD", true), Triple("(AB|CD)", "CE", false),
            Triple("(?i:ab)", "AB", true), Triple("(?i:ab)", "AC", false),
            Triple("\\h{2}", "  ", true), Triple("\\h{2}", "A ", false),
            Triple(".", "\u0085", false), Triple(".", "\uD83D\uDE00", true),
            Triple("\\s", "\u00A0", false), Triple("\\R", "\r\n", true),
            Triple("\\S", "\u00A0", true),
            Triple("[\\s@]", "@", true), Triple("[\\s@]", "\u00A0", false),
            Triple("[a-z&&b-d]", "c", true), Triple("[a-z&&b-d]", "a", false),
            Triple("a_b c", "a_b c", true), Triple("[a-c ]+", "abd", false),
            Triple("[^0-9]", Char(0xD83D).toString(), true),
            Triple("[^0-9]{2}", Char(0xD83D) + "a", true),
            Triple(".{2}", "AB\n", false),
        )
        rows.forEach { (pattern, value, expected) -> assertEquals(expected, matches(pattern, value), "$pattern / $value") }
    }

    @Test
    fun certifiedInputsAreJudgedAndOthersRemainUnjudged() {
        val judged = listOf(
            Triple("\\N{LATIN CAPITAL LETTER B}", "B", true),
            Triple("\\N{LATIN CAPITAL LETTER B}", "A", false),
            Triple("\\N{LATIN CAPITAL LETTER C}", "C", true),
            Triple("[\\N{LATIN CAPITAL LETTER C}]", "C", true),
            Triple("\\N{LATIN SMALL LETTER A}", "a", true),
            Triple("\\x{E8}", "è", true),
            Triple("[\\x{E9}]", "é", true),
            Triple("[\\x{E9}]", "A", false),
            Triple("[\\x{E8}]", "è", true),
            Triple("[\\x{A0}]", "\u00A0", true),
            Triple("\\P{L}", "2", true),
            Triple("\\P{L}", "a", false),
            Triple("\\X", "a", true),
            Triple("\\X", "ab", false),
            Triple("\\bA\\b.", "A!", true),
            Triple("\\bA\\b.", "A_", false),
            Triple("(?iu)é", "É", true),
            Triple("(?iu)é", "e", false),
        )
        judged.forEach { (pattern, value, expected) -> assertEquals(expected, matches(pattern, value), "$pattern / $value") }
        assertEquals(null, matches("\\N{SNOWMAN}", "☃"))
        assertEquals(null, matches("\\P{L}", "é"))
        assertEquals(null, matches("\\X", "👩\u200D💻"))
        assertEquals(null, matches("\\bA\\b.", "éA!"))
        assertEquals(null, matches("(?iu)é", "Ŋ"))
        assertEquals(null, matches("(?i:\\N{LATIN CAPITAL LETTER A})", "a"))
        assertEquals(null, matches("(?i:[\\x{41}])", "a"))
        assertEquals(null, matches("(?iu)\\P{L}", "2"))
        assertEquals(null, matches("(?iu)ı", "I"))
        assertEquals(null, matches("(?iu)\\x{131}", "I"))
        assertEquals(null, matches("(?iu)[ı]", "I"))
        assertEquals(null, matches("(?iu)(?!İ)i", "i"))
        assertEquals(null, matches("(?iu)(a|(b))\\2c|ac", "ac"))
    }

    @Test
    fun unicodeCaseAgreesOnTheCompleteLatin1LiteralAlphabet() {
        // The JVM actual executes the original Pattern; Node independently checks the same finite relation.
        fun folded(code: Int): Int = when (code) {
            in 65..90, in 192..214, in 216..222 -> code + 32
            else -> code
        }
        for (literal in 0..255) {
            val pattern = "(?iu)\\x{" + literal.toString(16) + "}"
            val compiled = KeyPattern.parse(pattern)
            for (value in 0..255) {
                assertEquals(folded(literal) == folded(value), compiled?.matches(value.toChar().toString()),
                    "literal=$literal value=$value")
            }
        }
    }

    @Test
    fun classAlgebraWholeExpressionFlagsAndFinalAnchors() {
        val rows = listOf(
            Triple("[\\S]", "\u00A0", true), Triple("[\\S]", " ", false),
            Triple("[\\S@]", "\u00A0", true), Triple("[^\\S ]", "\t", true),
            Triple("[^ab]+", "ca", false), Triple("[^ab]{2}", "ca", false),
            Triple("[^ab]?a", "a", true), Triple("[^\\S]+", " a", false),
            Triple("[^\\S ]", "\u00A0", false), Triple("[\\D0]", "0", true),
            Triple("[\\D0]", "2", false), Triple("[\\H@]", "@", true),
            Triple("[\\H@]", " ", false), Triple("[^\\Vx]", "\n", true),
            Triple("[^\\Vx]", "x", false),
            Triple("(?iu:é)", "É", true), Triple("(?iu:è|é)", "È", true),
            Triple("(?iu:è|é)", "e", false),
            Triple("a$\n", "a\n", true), Triple("a$", "a\n", false),
            Triple("a$\r\n", "a\r\n", true), Triple("a$\u0085", "a\u0085", true),
            Triple("a\r$\n", "a\r\n", false), Triple("a$\n\n", "a\n\n", false),
            Triple("^a$", "a", true), Triple("b^a", "ba", false),
        )
        rows.forEach { (pattern, value, expected) -> assertEquals(expected, matches(pattern, value), "$pattern / $value") }
        assertEquals(null, matches("(?iu:é)e", "ÉE"))
        assertEquals(null, matches("(?iu:(?-i:é))", "É"))
        assertEquals(null, matches("(?m:a$)", "a"))
    }

    @Test
    fun unsupportedOrKernelRejectedPatternsStayUnjudged() {
        assertEquals(null, matches("[a[b]]", "b"))
        assertEquals(null, matches("[a-z&&[^aeiou]]", "b"))
        assertEquals(null, matches("(a|(b))\\2c", "ac"))
        assertEquals(null, matches("(a|(b))\\2c", "bbc"))
        assertEquals(null, matches("(a|(b))\\2c|ac", "ac"))
        assertEquals(null, matches("\\N{LATIN CAPITAL LETTER B}((a|(b))\\3c|ac)", "Bac"))
        assertEquals(null, matches("[\\p{Lower}]", "a"))
        assertEquals(null, matches("\\b{g}", "a"))
        assertEquals(null, matches("a++", "aaa"))
        assertEquals(null, matches("(?<=A)B", "B"))
        assertEquals(null, matches("[", "A"))
    }
}
