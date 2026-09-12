package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionLanguage
import io.github.mbackschat.a12.dm.consistency.syntax.KeywordKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `And` and `Or` mixed inside ONE bracket pair — A12's `MVK_BRACKET_MISSING`.
 *
 * The one grammar-free phase found after two were withdrawn, and it earns that place by not depending on
 * parser state: A12 refuses the mix from a semantic ACTION on the `bedingung` context, and exactly three
 * productions open one — the entry rule, a parenthesised sub-condition, and an entity filter's `Mit`
 * ([KERNEL-FINDINGS KF301], `rulekit.validate.laws.RuleConditionCompositionLawsTest`, kernel 30.8.1,
 * 2026-09-13).
 *
 * **The rows that carry the family are the ADMITTED ones**, because two cheap implementations pass every
 * refusal row and over-reject here: looking for both spellings anywhere in the text fails the five
 * level-separated rows, and keying on a nesting DEPTH fails the sibling-call rows, where two filters one
 * parenthesis deep carry different connectives in different parenthesis pairs. Over-rejection is the one
 * direction this checker may not take, so those rows are the point of the grid rather than its garnish.
 */
class ConnectiveAgreementTest {

    private val a = "FieldFilled(Price)"
    private val b = "FieldFilled(Sku)"
    private val c = "FieldNotFilled(Price)"

    private fun model(condition: String, language: String? = null) = prepare(
        assertIs<Admission.Admitted>(
            admit(
                ModelSource(
                    """
                    {"header":{"id":"catalog","locales":[{"code":"en_US"}]},
                     "content":{${
                        language?.let { """"modelConfig":{"conditionLanguage":{"code":"$it"}},""" } ?: ""
                    }"modelRoot":{"rootGroups":[
                      {"type":"Group","id":"g0","name":"Catalog","Group":{"elements":[
                        {"type":"Field","id":"f0","name":"Price","Field":{}},
                        {"type":"Field","id":"f1","name":"Sku","Field":{}},
                        {"type":"Group","id":"g1","name":"Lines","Group":{"repeatability":20,"elements":[
                          {"type":"Field","id":"f2","name":"Name","Field":{}},
                          {"type":"Field","id":"f3","name":"Amount","Field":{}}
                        ]}},
                        {"type":"Rule","id":"r0","name":"PriceRule","Rule":{
                          "errorCondition":${'"'}$condition${'"'},"errorEntityRelPath":"../Price"}}
                      ]}}
                     ]}}}
                    """.trimIndent(),
                ),
            ),
        ),
    )

    private fun codesFor(condition: String, language: String? = null) =
        model(condition, language).connectiveAgreementFindings().map { it.code }

    @Test
    fun aMixInsideOneBracketPairIsReportedAndOneAcrossLevelsIsNot() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a single connective — the liveness control"] = codesFor("$a And $b")
        actual["four parts, one connective"] = codesFor("$a And $b And $c And $a")
        actual["And then Or at one level"] = codesFor("$a And $b Or $c")
        actual["Or then And at one level"] = codesFor("$a Or $b And $c")
        actual["a longer mix at one level"] = codesFor("$a And $b Or $c And $b")
        // Every row below carries BOTH spellings and separates them by a bracket level. A check that looked
        // for both spellings anywhere in the text would reject all five.
        actual["grouped left"] = codesFor("($a And $b) Or $c")
        actual["grouped right"] = codesFor("$a And ($b Or $c)")
        actual["both sides grouped"] = codesFor("($a And $b) Or ($c And $b)")
        actual["a grouped Or inside an And chain"] = codesFor("$a And ($b Or $c) And $b")
        actual["nested two levels deep"] = codesFor("$a Or ($b And ($c Or $a))")
        // …and a mix INSIDE a pair is still caught when the outer level is clean, so nesting does not switch
        // the check off — it scopes it.
        actual["a mix inside the parentheses"] = codesFor("$a And ($b And $c Or $a)")

        val expected = mutableMapOf<String, List<String>>()
        expected["a single connective — the liveness control"] = emptyList()
        expected["four parts, one connective"] = emptyList()
        expected["And then Or at one level"] = listOf("MVK_BRACKET_MISSING")
        expected["Or then And at one level"] = listOf("MVK_BRACKET_MISSING")
        expected["a longer mix at one level"] = listOf("MVK_BRACKET_MISSING")
        expected["grouped left"] = emptyList()
        expected["grouped right"] = emptyList()
        expected["both sides grouped"] = emptyList()
        expected["a grouped Or inside an And chain"] = emptyList()
        expected["nested two levels deep"] = emptyList()
        expected["a mix inside the parentheses"] = listOf("MVK_BRACKET_MISSING")
        assertEquals(expected, actual, "which connective mixes the checker reports")
    }

    /**
     * A level is a bracket PAIR, not a depth — the rows a depth counter gets wrong.
     *
     * A filter's condition is not parenthesised in its own right: the grammar attaches it to the entity spec
     * and the enclosing call's `)` ends it. So a checker that opened a frame only at a delimiter would merge
     * two filters sharing an argument list, and one that counted DEPTH would merge two filters in different
     * calls. Both merges reject text A12 admits.
     */
    @Test
    fun twoCallsAtOneDepthDoNotShareALevel() {
        val filterWithAnd = """NumberOfFilledFields(Lines*/Name Having [Lines/Name] == \"x\" """ +
            """And [Lines/Amount] > 1) > 0"""
        val filterWithOr = """NumberOfFilledFields(Lines*/Name Having [Lines/Name] == \"y\" """ +
            """Or [Lines/Amount] > 2) > 0"""

        val actual = mutableMapOf<String, List<String>>()
        actual["a filter's And with an Or outside it"] = codesFor("$filterWithAnd Or $a")
        actual["a filter's And with an And outside it"] = codesFor("$filterWithAnd And $a")
        actual["two sibling filters, one And and one Or"] = codesFor("$filterWithAnd Or $filterWithOr Or $a")
        // The control that keeps the three rows above from being "the checker gave up inside a call": the
        // same pair with the OUTER level mixed is still reported.
        actual["…and the same pair with the outer level mixed"] = codesFor("$filterWithAnd Or $filterWithOr And $a")

        val expected = mutableMapOf<String, List<String>>()
        expected["a filter's And with an Or outside it"] = emptyList()
        expected["a filter's And with an And outside it"] = emptyList()
        expected["two sibling filters, one And and one Or"] = emptyList()
        expected["…and the same pair with the outer level mixed"] = listOf("MVK_BRACKET_MISSING")
        assertEquals(expected, actual, "whether two calls at one depth share a connective level")
    }

    /**
     * The German connectives, because the keyword table is per language and a rule keyed on English spellings
     * would silently check nothing in a German model.
     *
     * The last row is the one that makes this a language claim rather than a spelling one: `Und` is an
     * ordinary field name in an English model, so reading it as a connective there would be the merged-table
     * defect the keyword table was split to avoid.
     */
    @Test
    fun theGermanConnectivesAreJudgedInAGermanModelOnly() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a German mix in a German model"] = codesFor("$a Und $b Oder $c", language = "de_DE")
        actual["a German single connective — the liveness control"] = codesFor("$a Und $b", language = "de_DE")
        actual["an English mix in an English model"] = codesFor("$a And $b Or $c")
        actual["a German mix in an ENGLISH model"] = codesFor("$a Und $b Oder $c")

        val expected = mutableMapOf<String, List<String>>()
        expected["a German mix in a German model"] = listOf("MVK_BRACKET_MISSING")
        expected["a German single connective — the liveness control"] = emptyList()
        expected["an English mix in an English model"] = listOf("MVK_BRACKET_MISSING")
        // In an English model `Und` and `Oder` are names, not connectives — so there is no mix to report.
        expected["a German mix in an ENGLISH model"] = emptyList()
        assertEquals(expected, actual, "which language's connectives are judged")
    }

    /**
     * The four spellings this family hard-codes are pinned against the GENERATED keyword table.
     *
     * The table carries a [KeywordKind] and nothing finer, so it cannot say which operators JOIN two
     * conditions — `PatternMatched` is an `OPERATOR` too. The four connectives are therefore written out by
     * hand, and this is what keeps that hand-written pair honest: a regeneration that renamed or reclassified
     * one would otherwise switch the family off in silence, with every row above still green because they
     * spell the same words.
     */
    @Test
    fun theConnectiveSpellingsAreTheKeywordTablesOwn() {
        val actual = mutableMapOf<String, KeywordKind?>()
        for ((language, spellings) in mapOf(
            ConditionLanguage.EN to listOf("And", "Or"),
            ConditionLanguage.DE to listOf("Und", "Oder"),
        )) {
            for (spelling in spellings) {
                actual["$language $spelling"] = CONDITION_KEYWORDS.getValue(language)[spelling]
            }
        }
        // `Having`/`Mit` too: the family opens a frame at the filter keyword, so a rename there would merge
        // a filter's condition with the text around it and start rejecting conditions A12 admits.
        actual["EN Having"] = CONDITION_KEYWORDS.getValue(ConditionLanguage.EN)["Having"]
        actual["DE Mit"] = CONDITION_KEYWORDS.getValue(ConditionLanguage.DE)["Mit"]

        val expected = mutableMapOf<String, KeywordKind?>()
        expected["EN And"] = KeywordKind.OPERATOR
        expected["EN Or"] = KeywordKind.OPERATOR
        expected["DE Und"] = KeywordKind.OPERATOR
        expected["DE Oder"] = KeywordKind.OPERATOR
        expected["EN Having"] = KeywordKind.PATH_OP
        expected["DE Mit"] = KeywordKind.PATH_OP
        assertEquals(
            expected,
            actual,
            "the spellings this family hard-codes must still be the ones the generated table carries",
        )
    }

    /** The finding names both spellings and the offset, so an author can see which pair to bracket. */
    @Test
    fun theFindingNamesBothSpellingsAndWhereTheSecondSits() {
        val finding = model("$a And $b Or $c").connectiveAgreementFindings().single()
        assertEquals("/Catalog/PriceRule", finding.at)
        assertTrue(finding.explain.contains("And") && finding.explain.contains("Or"), finding.explain)
        assertTrue(finding.explain.contains("${"$a And $b ".length}"), finding.explain)
    }
}
