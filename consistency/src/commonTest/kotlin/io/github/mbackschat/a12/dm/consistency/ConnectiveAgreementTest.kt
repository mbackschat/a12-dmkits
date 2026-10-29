package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionLanguage
import io.github.mbackschat.a12.dm.consistency.syntax.KeywordKind
import io.github.mbackschat.a12.dm.consistency.syntax.SyntaxEntry
import io.github.mbackschat.a12.dm.consistency.syntax.shapeFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `And` and `Or` mixed inside ONE bracket pair — A12's `MVK_BRACKET_MISSING`.
 *
 * The recognizer records the semantic action in each condition context: the entry, a parenthesized
 * subcondition or an entity filter (KF301, RuleConditionCompositionLawsTest). Only reached actions
 * contribute; sibling contexts cannot share their connective state.
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
                    {"header":{"modelVersion":"28.4.0","id":"catalog","locales":[{"code":"en_US"}]},
                     "content":{"modelInfo":{"name":"model"},${
                        language?.let { """"modelConfig":{"conditionLanguage":{"code":"$it"}},""" } ?: ""
                    }"modelRoot":{"rootGroups":[
                      {"type":"Group","id":"g0","name":"Catalog","Group":{"elements":[
                        {"type":"Field","id":"f0","name":"Price","Field":{"fieldType":{"type":"StringType"}}},
                        {"type":"Field","id":"f1","name":"Sku","Field":{"fieldType":{"type":"StringType"}}},
                        {"type":"Group","id":"g1","name":"Lines","Group":{"repeatability":20,"elements":[
                          {"type":"Field","id":"f2","name":"Name","Field":{"fieldType":{"type":"StringType"}}},
                          {"type":"Field","id":"f3","name":"Amount","Field":{"fieldType":{"type":"StringType"}}}
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
        model(condition, language).agreementFindings().map { it.code }

    /** The family over the whole fixture — it is per-declaration, so the dispatcher asks it once each. */
    private fun PreparedModel.agreementFindings(): List<Finding> =
        tracked().everyDeclaration(TrackedFactBase::connectiveAgreementFindings)

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

        // The row that is a claim about THIS CHECKER rather than about A12: two filters sharing ONE argument
        // list. The kernel's verdict for it is unmeasured — `SumOfProducts` is the only operator whose
        // argument list takes two entity specs, and it refuses a `Having` on either for a wildcard reason of
        // its own — so the checker opens a frame at the filter keyword and stays silent, which under-reports
        // if A12 merges them and over-rejects if it does not. Only the second of those is forbidden.
        actual["two filters sharing one argument list"] = codesFor(
            """SumOfProducts(Lines*/Amount Having [Lines/Name] == \"x\" And [Lines/Amount] > 1, """ +
                """Lines*/Amount Having [Lines/Name] == \"y\" Or [Lines/Amount] > 2) > 0""",
        )

        val expected = mutableMapOf<String, List<String>>()
        expected["a filter's And with an Or outside it"] = emptyList()
        expected["a filter's And with an And outside it"] = emptyList()
        expected["two sibling filters, one And and one Or"] = emptyList()
        expected["…and the same pair with the outer level mixed"] = listOf("MVK_BRACKET_MISSING")
        expected["two filters sharing one argument list"] = emptyList()
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
        val words = CONDITION_OPERATOR_KEYWORDS.getValue(ConditionLanguage.DE)
        val germanA = "${words.getValue("FieldFilled")}(Price)"
        val germanB = "${words.getValue("FieldFilled")}(Sku)"
        val germanC = "${words.getValue("FieldNotFilled")}(Price)"
        val mixed = "$germanA Und $germanB Oder $germanC"
        val single = "$germanA Und $germanB"
        assertNull(shapeFailure(mixed, ConditionLanguage.DE, SyntaxEntry.CONDITION))
        assertNull(shapeFailure(single, ConditionLanguage.DE, SyntaxEntry.CONDITION))
        assertNotNull(shapeFailure("$a Und $b Oder $c", ConditionLanguage.DE, SyntaxEntry.CONDITION))
        val actual = mutableMapOf<String, List<String>>()
        actual["a German mix in a German model"] = codesFor(mixed, language = "de_DE")
        actual["a German single connective — the liveness control"] = codesFor(single, language = "de_DE")
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
     * Every CASE the lexer admits counts, and no other case does.
     *
     * A12's lexer resolves three spellings per connective — `And`, `AND`, `and` — so a mix written in any of
     * them is the same `MVK_BRACKET_MISSING` to A12. This family used to carry the canonical spelling alone
     * and missed the other two, which was under-reporting and was on the report as a coverage gap.
     *
     * **The last row is the one that decides the mechanism.** Matching case-insensitively on its own would
     * also catch `aNd`, which A12's lexer does NOT resolve — it is an ordinary field name there, so treating
     * it as a connective would invent a rejection A12 does not make. Recognition requires membership in the
     * generated terminal table before comparing the reached connective spellings.
     */
    @Test
    fun everyCaseTheLexerAdmitsIsAConnectiveAndNoOtherCaseIs() {
        val actual = mutableMapOf<String, List<String>>()
        actual["the canonical mix — the control"] = codesFor("(FieldFilled(A) And FieldFilled(B) Or FieldFilled(A))")
        actual["upper-case AND"] = codesFor("(FieldFilled(A) AND FieldFilled(B) Or FieldFilled(A))")
        actual["lower-case and"] = codesFor("(FieldFilled(A) and FieldFilled(B) Or FieldFilled(A))")
        actual["upper-case OR as the second"] = codesFor("(FieldFilled(A) And FieldFilled(B) OR FieldFilled(A))")
        actual["both in non-canonical case"] = codesFor("(FieldFilled(A) and FieldFilled(B) OR FieldFilled(A))")
        actual["a case the lexer does NOT resolve"] =
            codesFor("(FieldFilled(A) aNd FieldFilled(B) Or FieldFilled(A))")
        val filled = CONDITION_OPERATOR_KEYWORDS.getValue(ConditionLanguage.DE).getValue("FieldFilled")
        val german = "($filled(A) Und $filled(B) ODER $filled(A))"
        assertNull(shapeFailure(german, ConditionLanguage.DE, SyntaxEntry.CONDITION))
        assertNotNull(shapeFailure("(FeldGefuellt(A) Und FeldGefuellt(B) ODER FeldGefuellt(A))",
            ConditionLanguage.DE, SyntaxEntry.CONDITION))
        actual["German ODER against Und"] = codesFor(german, "de_DE")

        val expected = mutableMapOf<String, List<String>>()
        expected["the canonical mix — the control"] = listOf("MVK_BRACKET_MISSING")
        expected["upper-case AND"] = listOf("MVK_BRACKET_MISSING")
        expected["lower-case and"] = listOf("MVK_BRACKET_MISSING")
        expected["upper-case OR as the second"] = listOf("MVK_BRACKET_MISSING")
        expected["both in non-canonical case"] = listOf("MVK_BRACKET_MISSING")
        // Not a terminal, so A12 reads it as a field name and this family must say nothing about the text.
        expected["a case the lexer does NOT resolve"] = emptyList()
        expected["German ODER against Und"] = listOf("MVK_BRACKET_MISSING")
        assertEquals(expected, actual, "which connective spellings this family sees")
    }

    /**
     * The recognizer's connective identities must remain operators in both generated terminal tables.
     * This catches a renamed or reclassified terminal even if the literal fixtures still agree with each other.
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
        val finding = model("$a And $b Or $c").agreementFindings().single()
        assertEquals("/Catalog/PriceRule", finding.at)
        assertTrue(finding.explain.contains("And") && finding.explain.contains("Or"), finding.explain)
        assertTrue(finding.explain.contains("${"$a And $b ".length}"), finding.explain)
    }
}
