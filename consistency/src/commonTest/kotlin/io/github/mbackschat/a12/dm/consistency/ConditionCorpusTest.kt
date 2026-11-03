package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.TokenKind
import io.github.mbackschat.a12.dm.consistency.syntax.tokenize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The OVER-REJECTION gate: every syntax family must be silent on DSL the real Kernel accepts.
 *
 * The unit tests beside this one are written by the same hand as the code they check, so they can agree with
 * it and both be wrong. This population cannot: the catalog's examples are executed against the real Kernel
 * in CI, so a finding reported here is a condition A12 admits and this checker refuses — the one direction it
 * must never take. It is also the gate the grammatical increment inherits, standing now against the families
 * that already exist rather than arriving with the parser.
 *
 * **The `invalidExample` texts are part of the ACCEPT population**, which the name actively hides. They are
 * grammatically valid and fail on MEANING — `[CustomerName] <= [ProductCode]` parses perfectly and is a type
 * error — so a checker built to reject them would refuse 52 correct conditions.
 */
class ConditionCorpusTest {

    /** The declaration the text rides: a rule's condition, or a computation's operation. */
    private fun declaration(text: String, member: String) = when (member) {
        "Rule" -> """{"type":"Rule","id":"r0","name":"Probe","Rule":{
              "errorCondition":"$text","errorEntityRelPath":"../Anchor",
              "errorCode":"P","errorMessage":[{"locale":"en_US","text":"x"}]}}"""
        else -> """{"type":"Computation","id":"c0","name":"Probe","Computation":{
              "computedFieldRelPath":"../Anchor",
              "computationAlternatives":[{"operation":"$text"}]}}"""
    }

    private fun report(text: String, member: String, language: String?) = checkRead(
        ModelSource(
            """
            {"header":{"modelVersion":"29.4.0","id":"corpus","locales":[{"code":"en_US"}]},
             "content":{"modelInfo":{"name":"model"},${
                language?.let { """"modelConfig":{"conditionLanguage":{"code":"$it"}},""" } ?: ""
            }"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Catalog","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Anchor","Field":{"fieldType":{"type":"StringType"}}},
                ${declaration(text, member)}
              ]}}
             ]}}}
            """.trimIndent(),
        ),
    )

    /** Only the syntax families' verdicts: the corpus names entities this model does not declare. */
    private fun syntaxCodes(text: String, member: String, language: String?) =
        report(text, member, language).findings
            .filter { it.code in SYNTAX_CODES }
            .map { it.code }

    @Test
    fun noSyntaxFamilyReportsAnythingAboutDslTheKernelAccepts() {
        val refused = mutableMapOf<String, List<String>>()
        // A text whose model never reached the syntax layer would be silent for the wrong reason — an
        // unadmitted source, a declaration the preparation dropped — and would read exactly like a pass. So
        // every row is required to SAY it ran the three families, not merely to report nothing.
        val unchecked = mutableListOf<String>()
        fun sweep(id: String, text: String, member: String, language: String?) {
            val report = report(text.escaped(), member, language)
            if (!report.checksCompleted.containsAll(SYNTAX_FAMILIES)) {
                unchecked += id
            }
            report.findings.map { it.code }.filter { it in SYNTAX_CODES }
                .ifNotEmpty { refused[id] = it }
        }
        for (row in ConditionCorpus.conditions()) {
            sweep("EN ${row.id}", row.en, "Rule", null)
            sweep("DE ${row.id}", row.de, "Rule", "de_DE")
        }
        for (row in ConditionCorpus.operations()) {
            sweep("EN ${row.id}", row.en, "Computation", null)
            sweep("DE ${row.id}", row.de, "Computation", "de_DE")
        }
        assertEquals(emptyList(), unchecked, "these rows were silent because nothing checked them")
        assertEquals(emptyMap(), refused, "every one of these texts is DSL the Kernel accepts")
    }

    /**
     * The corpus is big enough to be a gate, stated as a floor rather than an exact count.
     *
     * A floor answers the question this guard actually has — did the generator run and find the catalog —
     * while an exact count would have to be edited every time an operator gains an example, which is how a
     * derived figure becomes a chore and then a lie. Measured 2026-09-13: 154 conditions, 8 operations.
     */
    @Test
    fun theCorpusIsPopulated() {
        assertTrue(ConditionCorpus.conditions().size >= 140, "${ConditionCorpus.conditions().size} conditions")
        assertTrue(ConditionCorpus.operations().size >= 5, "${ConditionCorpus.operations().size} operations")
    }

    /**
     * The German half polices itself, because its localizer is a SECOND implementation.
     *
     * The runtime one lives in `:adapter` and this module may not depend on it, so the corpus is localized at
     * build time by a separate substitution — and a substitution is exactly the thing that goes wrong
     * invisibly: leaking into a string literal, eating a delimiter, splitting an identifier. Tokenizing both
     * halves catches all three, because a corrupted text changes its token KINDS or its literals while a
     * correct localization changes only the text of NAME tokens.
     */
    @Test
    fun everyGermanTextHasItsEnglishOriginalsTokenShape() {
        val mismatched = mutableMapOf<String, String>()
        for (row in ConditionCorpus.conditions() + ConditionCorpus.operations()) {
            val english = tokenize(row.en)
            val german = tokenize(row.de)
            if (english.map { it.kind } != german.map { it.kind }) {
                mismatched[row.id] = "kinds: ${english.map { it.kind }} vs ${german.map { it.kind }}"
                continue
            }
            val literals = english.zip(german)
                .filter { (e, g) -> e.kind != TokenKind.NAME && e.text != g.text }
            if (literals.isNotEmpty()) {
                mismatched[row.id] = "a non-name token changed: ${literals.first()}"
            }
        }
        assertEquals(emptyMap(), mismatched, "a build-time substitution must not change a text's shape")
    }

    /**
     * Every bilingual keyword pair is exercised by some text, so the German arm cannot cover nothing.
     *
     * `RuleGroup` is the one exception and it is not a catalog operator at all — it is the rule-context
     * entity, paired here because a German model spells it `RegelKontext`. No catalog example uses it, so it
     * gets its own row rather than being dropped from the pair list, where its absence would stop being
     * visible.
     */
    @Test
    fun theOnlyUnexercisedKeywordPairIsTheRuleContextEntity() {
        assertEquals(listOf("RuleGroup"), ConditionCorpus.keywordsNoExampleLocalizes())
        assertEquals(emptyList(), syntaxCodes("GroupFilled(RegelKontext)", "Rule", "de_DE"))
    }

    /**
     * The gate is not vacuous: a text A12 refuses IS reported, in both languages.
     *
     * Without this the grid above would pass just as well with every family switched off, which is the
     * failure mode an all-silent expectation invites.
     */
    @Test
    fun thePositiveControlsAreStillReported() {
        val actual = mutableMapOf<String, List<String>>()
        actual["EN a mixed connective"] =
            syntaxCodes("FieldFilled(Anchor) And FieldFilled(Anchor) Or FieldFilled(Anchor)", "Rule", null)
        actual["DE a mixed connective"] =
            syntaxCodes("FeldAngegeben(Anchor) Und FeldAngegeben(Anchor) Oder FeldAngegeben(Anchor)",
                "Rule", "de_DE")
        actual["EN an unterminated literal"] = syntaxCodes("""FieldFilled(\"Anchor)""", "Rule", null)
        actual["EN text that ends early"] = syntaxCodes("FieldFilled(Anchor) And", "Rule", null)

        val expected = mutableMapOf<String, List<String>>()
        expected["EN a mixed connective"] = listOf("MVK_BRACKET_MISSING")
        expected["DE a mixed connective"] = listOf("MVK_BRACKET_MISSING")
        expected["EN an unterminated literal"] = listOf("MVK_LEXER_STANDARD_ERROR")
        expected["EN text that ends early"] = listOf("MVK_INCOMPLETE_INPUT")
        assertEquals(expected, actual, "the corpus gate must still be able to fail")
    }

    private fun List<String>.ifNotEmpty(action: (List<String>) -> Unit) {
        if (isNotEmpty()) action(this)
    }

    /** The corpus carries raw DSL; the probe model embeds it in JSON, where a quote has to be escaped. */
    private fun String.escaped() = replace("\\", "\\\\").replace("\"", "\\\"")

    private companion object {
        /** The codes the three syntax families raise, so a reference or slot verdict cannot mask a silence. */
        val SYNTAX_FAMILIES = setOf(
            CheckFamily.CONDITION_LEXICALITY,
            CheckFamily.CONDITION_COMPLETENESS,
            CheckFamily.CONNECTIVE_AGREEMENT,
        )

        val SYNTAX_CODES = setOf(
            "MVK_LEXER_STANDARD_ERROR",
            "MVK_INCOMPLETE_INPUT",
            "MVK_BRACKET_MISSING",
        )
    }
}
