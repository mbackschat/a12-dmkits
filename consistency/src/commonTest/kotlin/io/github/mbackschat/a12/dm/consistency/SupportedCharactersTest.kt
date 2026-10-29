package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of [KERNEL-FINDINGS KF317] — a declared enum value against the model's character set.
 *
 * Every row mirrors one measured against the real kernel by
 * `rulekit.validate.laws.SupportedCharactersDeclarationGateLawsTest`.
 *
 * **The silent rows carry the risk, and there are two kinds.** A restriction this checker cannot read as
 * singletons and ranges, and a declared string that is not an enum value: each is a model A12 accepts or has
 * never been asked about, so a finding on one would be this checker's own reading presented as A12's. A value
 * carrying a supplementary-plane character was a third until A12 was measured judging it per UTF-16 unit
 * (KERNEL-FINDINGS KF551, `adapter.laws.CheckerEnumDeclarationStreamAgreementTest`).
 *
 * **The grapheme rows name their code points rather than spelling them.** Written as literal accented
 * characters they would pass or fail on how this FILE happens to be normalized — a row that reads as a
 * grapheme test while measuring the source encoding proves nothing about the branch it points at, and the
 * first draft of the combined row did exactly that.
 */
class SupportedCharactersTest {

    /** `e` followed by U+0301 COMBINING ACUTE ACCENT — one grapheme, two code points. */
    private val combined = "e" + Char(0x0301)

    /** U+00E9 LATIN SMALL LETTER E WITH ACUTE — the same grapheme as one code point. */
    private val precomposed = Char(0x00e9).toString()

    /** U+1F600, which JSON and Kotlin both carry as a surrogate PAIR. */
    private val supplementary = "" + Char(0xd83d) + Char(0xde00)

    /** A model restricted to [definition], whose one enum field declares [values]. */
    private fun report(definition: String?, vararg values: String) = checkRead(
        ModelSource(
            """
            {"header":{"modelVersion":"28.4.0","id":"shop","locales":[{"code":"en_US"}]},
             "content":{"modelInfo":{"name":"model"},${definition?.let { """"modelConfig":{"supportedCharacters":$it},""" } ?: ""}
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
    )

    /**
     * This family's codes only.
     *
     * A fixture here declares the SAME offending value twice to measure the character fold's multiplicity,
     * and that model is separately refused for the repeat (`MVK_INTERNAL_VALUES_DUPLICATE`, its own family).
     * Counting every code would make this test's subject depend on which unrelated family lands next.
     */
    private fun codes(definition: String?, vararg values: String): List<String> =
        report(definition, *values).findings.map { it.code }
            .filter { it == "MVK_ENUM_VALUE_WITH_INVALID_CHARACTER" }

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
        // …and the scope is the value's SPELLING rather than its position, because A12 reports on the
        // triple (field, value, character) and folds the repeat.
        actual["the same offending value declared twice"] = codes("""["A-Z"]""", "ab", "ab")
        actual["a singleton entry beside the range"] = codes("""["A-Z","b"]""", "beta")
        // A PRECOMPOSED accented letter is ONE code point, which A12 parses as a range of length 1 — so it
        // is read here, and the silent row below is about the COMBINED spelling rather than about accents.
        actual["a precomposed single-character entry"] = codes("""["A-Z","$precomposed"]""", "beta")
        actual["both range ends"] = codes("""["A-Z"]""", "AZ")
        actual["one below a range's start"] = codes("""["B-Y"]""", "A")
        // A supplementary-plane character is two UTF-16 units, and A12 judges each.
        actual["a value carrying a surrogate pair"] = codes("""["A-Z"]""", supplementary)

        val expected = mutableMapOf<String, List<String>>()
        expected["no restriction — the control"] = emptyList()
        expected["a conforming spelling — the control"] = emptyList()
        expected["four offending characters"] = invalid + invalid + invalid + invalid
        expected["the same character twice"] = invalid
        expected["two values sharing the same two offenders"] = invalid + invalid + invalid + invalid
        expected["the same offending value declared twice"] = invalid + invalid
        expected["a singleton entry beside the range"] = invalid + invalid + invalid
        expected["a precomposed single-character entry"] = invalid + invalid + invalid + invalid
        expected["both range ends"] = emptyList()
        expected["one below a range's start"] = invalid
        expected["a value carrying a surrogate pair"] = invalid + invalid
        assertEquals(expected, actual, "one finding per distinct offending character of each value")
    }

    /** The finding names the character and the value, which is what an author has to change. */
    @Test
    fun theFindingNamesTheCharacterAndTheValueItSitsIn() {
        val finding = checkRead(
            ModelSource(
                """
                {"header":{"modelVersion":"28.4.0","id":"shop","locales":[{"code":"en_US"}]},
                 "content":{"modelInfo":{"name":"model"},"modelConfig":{"supportedCharacters":["A-Z"]},
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

    /**
     * A supplementary character's high unit is refused even under a range spanning the surrogate block, while its
     * low unit is judged by the ranges (KERNEL-FINDINGS KF560, `adapter.laws.CheckerEnumDeclarationStreamAgreementTest`).
     */
    @Test
    fun aSupplementaryCharactersHighUnitIsNeverAdmitted() {
        val spanning = """["\u0020-\uFFFD"]"""
        val second = "" + Char(0xd83d) + Char(0xde4f)
        assertEquals(invalid, codes(spanning, "BETA", supplementary))
        assertEquals(invalid, codes(spanning, "BETA", supplementary + second))
        assertEquals(invalid + invalid, codes(spanning, supplementary, second))
        assertEquals(invalid + invalid, codes("""["A-Z"]""", "BETA", supplementary))
    }

    /** An entry holding a surrogate unit leaves A12 unable to build the model; the family judges nothing against it. */
    @Test
    fun aSurrogateInTheRestrictionIsReportedAsUnbuildable() {
        // The lone halves are built at run time (CheckerFixtureReadGuardTest.noMultiplatformSourceSpellsALoneSurrogate).
        val high = Char(0xD83D)
        val lastHigh = Char(0xDBFF)
        // A whole pair as one entry too: two units, and still unbuilt (adapter.laws.CheckerEnumDeclarationStreamAgreementTest).
        for (definition in listOf("""["A-Z","$high"]""", """["A-Z","\uD83D\uDED2"]""", """["A-Z","\u0100-$lastHigh"]""",
                """["\uD83D\uDE00-\uD83D\uDE4F"]""")) {
            val report = report(definition, "beta", supplementary)
            assertEquals(listOf(CK_MODEL_NOT_BUILDABLE), report.findings.map { it.code }
                .filter { it == CK_MODEL_NOT_BUILDABLE || it == "MVK_ENUM_VALUE_WITH_INVALID_CHARACTER" }, definition)
            assertEquals(emptyList(), report.gaps.filter { it.reason.contains("NO enum value was judged") }, definition)
        }
    }

    /** Everything the family deliberately says nothing about. */
    @Test
    fun anUnreadableRestrictionOrAnUnmeasuredCarrierIsLeftToA12() {
        val actual = mutableMapOf<String, List<String>>()
        // A COMBINED grapheme — more than one code point — is a legal entry that A12 tests by walking
        // code-point SEQUENCES, so a per-character reading of it would refuse values A12 admits. The
        // precomposed row above pins the other side of that boundary, where one code point IS read.
        actual["a restriction carrying a combined grapheme"] = codes("""["A-Z","$combined"]""", "beta")
        actual["a reversed range"] = codes("""["Z-A"]""", "beta")
        actual["an empty restriction list"] = codes("""[]""", "beta")
        actual["a non-string entry"] = codes("""[7]""", "beta")
        val expected = actual.keys.associateWith { emptyList<String>() }
        assertEquals(expected, actual, "an unreadable restriction leaves the whole model unjudged")
    }

    /**
     * The silence is PUBLISHED, because the same report names this family as completed.
     *
     * Without the gap a consumer reads "examined, nothing found" for a model the family declined to judge —
     * the one direction an under-reporting checker can still mislead in. The unreadable restriction is a
     * MODEL-level gap because it silences every declaration at once.
     */
    @Test
    fun theSilenceIsPublishedAsACoverageGap() {
        val unreadable = report("""["A-Z","$combined"]""", "beta").gaps
        assertEquals(
            listOf<String?>(null),
            unreadable.filter { it.reason.contains("NO enum value was judged") }.map { it.at },
            unreadable.joinToString("\n") { "${it.at}: ${it.reason}" },
        )

        // The control: a model this family fully judged publishes no gap, so the row above is answering to
        // the declaration rather than to a gap the report always carries.
        val judged = report("""["A-Z"]""", "beta").gaps
        assertEquals(
            emptyList(),
            judged.filter { it.reason.contains("NO enum value was judged") },
            judged.joinToString("\n") { "${it.at}: ${it.reason}" },
        )
    }
}
