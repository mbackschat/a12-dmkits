package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The first syntax family: condition text A12's LEXER cannot tokenize at all.
 *
 * This is the phase before any grammar question, and it is deliberately the whole of this slice. A12 answers
 * an unterminated string literal with `MVK_LEXER_STANDARD_ERROR` — a distinct code from the three parser-phase
 * ones ([KERNEL-FINDINGS KF300], `rulekit.validate.laws.RuleConditionSyntaxLawsTest`, kernel 30.8.1,
 * 2026-09-12) — and the checker's tokenizer already recognizes the shape, so reporting it needs no grammar
 * and cannot over-reject: a lexical failure is decided by the text alone.
 *
 * **What this deliberately does NOT claim.** The bundle's message is "Character '%s' at position %d is
 * incorrect", so A12 has a notion of an illegal CHARACTER too, and which characters those are is unmeasured —
 * `@@@` is lexically fine and fails one phase later as `MVK_UNEXPECTED_TOKEN`. Only the measured shape is
 * reported here, and the report carries a gap for the rest.
 */
class ConditionLexicalityTest {

    private fun model(condition: String, operation: String = "42.00") = prepare(
        assertIs<Admission.Admitted>(
            admit(
                ModelSource(
                    """
                    {"header":{"id":"catalog","locales":[{"code":"en_US"}]},
                     "content":{"modelRoot":{"rootGroups":[
                      {"type":"Group","id":"g0","name":"Catalog","Group":{"elements":[
                        {"type":"Field","id":"f0","name":"Price","Field":{"fieldType":{"type":"StringType"}}},
                        {"type":"Rule","id":"r0","name":"PriceRule","Rule":{
                          "errorCondition":${'"'}$condition${'"'},"errorEntityRelPath":"../Price"}},
                        {"type":"Computation","id":"c0","name":"PriceCalc","Computation":{
                          "computationAlternatives":[{"operation":${'"'}$operation${'"'}}]}}
                      ]}}
                     ]}}}
                    """.trimIndent(),
                ),
            ),
        ),
    )

    @Test
    fun anUnterminatedStringLiteralIsReportedAsTheLexerErrorA12Raises() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a well-formed condition — the control"] =
            model("FieldFilled(Price)").conditionLexicalFindings().map { it.code }
        actual["a well-formed string literal"] =
            model("""FieldValueIncludedInValueList(Price, \"A\")""").conditionLexicalFindings().map { it.code }
        actual["an unterminated literal"] =
            model("""FieldFilled(\"Price)""").conditionLexicalFindings().map { it.code }
        // A12's own escape is a DOUBLED quote, not a backslash, so this literal is closed and legal. A lexer
        // that read the C-style escape would end it early and report a failure that is not there.
        actual["a doubled quote inside a literal"] =
            model("""FieldValueIncludedInValueList(Price, \"A\"\"B\")""").conditionLexicalFindings().map { it.code }
        actual["punctuation that is lexically fine but ungrammatical"] =
            model("@@@").conditionLexicalFindings().map { it.code }

        val expected = mutableMapOf<String, List<String>>()
        expected["a well-formed condition — the control"] = emptyList()
        expected["a well-formed string literal"] = emptyList()
        expected["an unterminated literal"] = listOf("MVK_LEXER_STANDARD_ERROR")
        expected["a doubled quote inside a literal"] = emptyList()
        // The row that keeps this family in its lane: `@@@` IS lexable and A12 fails it one phase later, so
        // reporting it here would claim a lexer error the Kernel does not raise.
        expected["punctuation that is lexically fine but ungrammatical"] = emptyList()
        assertEquals(expected, actual, "which condition text the checker reports as lexically unreadable")
    }

    /** Every DSL-bearing member is covered, not only a rule's condition. */
    @Test
    fun aComputationsOperationIsLexedToo() {
        val findings = model("FieldFilled(Price)", operation = """\"unterminated""").conditionLexicalFindings()
        assertEquals(listOf("MVK_LEXER_STANDARD_ERROR"), findings.map { it.code })
        assertEquals("/Catalog/PriceCalc", findings.single().at)
        assertTrue(
            findings.single().explain.contains("computationAlternatives[0].operation"),
            "the finding must name the member, since a computation carries several: ${findings.single().explain}",
        )
    }

    /**
     * The OTHER quoting form: a `'…'` name whose terminal cannot match, which A12 answers the same way.
     *
     * The last two rows are closed and still refused, because the terminal is `'` STR (STR|DIGIT)* `'` and
     * takes neither an empty name nor a digit-leading one. All four are measured `MVK_LEXER_STANDARD_ERROR`
     * (`rulekit.validate.laws.RuleConditionSyntaxLawsTest`). The controls carry the real risk: quoting is an
     * escape rather than a declaration, so a closed quoted name must pass — reporting one would reject text
     * A12 admits, the one direction this checker may not take.
     */
    @Test
    fun aQuotedNameTheTerminalCannotMatchIsReportedAsTheSameLexerError() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a closed quoted name — the control"] =
            model("""FieldFilled('Price')""").conditionLexicalFindings().map { it.code }
        actual["a closed quoted name spelling a keyword"] =
            model("""FieldFilled('And') Or FieldFilled(Price)""").conditionLexicalFindings().map { it.code }
        actual["unclosed at the end"] = model("""FieldFilled('Price""").conditionLexicalFindings().map { it.code }
        actual["unclosed with structure after it"] =
            model("""FieldFilled('Price) And FieldFilled(Price)""").conditionLexicalFindings().map { it.code }
        actual["an empty quoted name"] = model("""FieldFilled('')""").conditionLexicalFindings().map { it.code }
        actual["a digit-leading quoted name"] =
            model("""FieldFilled('1City')""").conditionLexicalFindings().map { it.code }

        val expected = mutableMapOf<String, List<String>>()
        expected["a closed quoted name — the control"] = emptyList()
        expected["a closed quoted name spelling a keyword"] = emptyList()
        expected["unclosed at the end"] = listOf("MVK_LEXER_STANDARD_ERROR")
        expected["unclosed with structure after it"] = listOf("MVK_LEXER_STANDARD_ERROR")
        expected["an empty quoted name"] = listOf("MVK_LEXER_STANDARD_ERROR")
        expected["a digit-leading quoted name"] = listOf("MVK_LEXER_STANDARD_ERROR")
        assertEquals(expected, actual, "which quoted names the checker reports as lexically unreadable")
    }

    /** The finding names the offset, so an author can find the quote in a long condition. */
    @Test
    fun theFindingLocatesTheUnterminatedQuote() {
        val finding = model("""FieldFilled(\"Price)""").conditionLexicalFindings().single()
        assertTrue(
            finding.explain.contains("12"),
            "the explain must carry the quote's offset: ${finding.explain}",
        )
    }
}
