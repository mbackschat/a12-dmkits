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
 * **The illegal-CHARACTER class is reported too, since 2026-09-13.** The bundle's message is "Character
 * '%s' at position %d is incorrect", and which characters draw it is now derived from
 * `Base_ConditionLexer.g4` — which has no catch-all rule, so anything outside its terminals fails to
 * tokenize — and measured against that reading row by row ([KERNEL-FINDINGS KF309]). That earlier `@@@`
 * probe was not a counterexample but a positive control: `@` IS a terminal, so it lexes and fails one phase
 * later, exactly as `$`, `,` and `ß` do here.
 *
 * **`#` is the one measured-illegal character deliberately NOT reported.** A lone one draws the lexer error,
 * but it is legal inside `#Zeile`, whose other language spellings this checker does not know — so refusing a
 * `#` it could not pair would reject text A12 may accept, and under-reporting is the only safe direction.
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

    /**
     * The illegal-CHARACTER class, with the characters A12's terminals DO spell as the live control.
     *
     * Every row puts one character in the same operand slot, so the control rows are what keep the reported
     * ones from being about the slot: a character the lexer takes lands as a token the grammar refuses one
     * phase later, and draws nothing from THIS family. The kernel measures the same grid
     * (`rulekit.validate.laws.ConditionCharacterLawsTest`, [KERNEL-FINDINGS KF309]).
     */
    @Test
    fun aCharacterNoTerminalCanBeginIsReportedAndOneTheTerminalsSpellIsNot() {
        val actual = mutableMapOf<String, List<String>>()
        for (legal in listOf("$", "@", ",", "ß")) {
            actual["`$legal`, a character the terminals spell — the control"] = codesFor(legal)
        }
        for (illegal in listOf("%", "&", "|", ";", "?", "~", "é", "€")) {
            actual["`$illegal`"] = codesFor(illegal)
        }
        // `!` is spelled only as `!=`, so a lone one reaches the fallback and is illegal — the paired row
        // proves the two-character terminal is still taken whole.
        actual["`!` alone"] = codesFor("!")
        actual["`!=` whole — the control for the row above"] = codesFor("!=")
        // `#` is measured illegal alone and deliberately NOT reported; see the class note.
        actual["`#`, measured illegal and deliberately unreported"] = codesFor("#")

        val expected = mutableMapOf<String, List<String>>()
        for (legal in listOf("$", "@", ",", "ß")) {
            expected["`$legal`, a character the terminals spell — the control"] = emptyList()
        }
        for (illegal in listOf("%", "&", "|", ";", "?", "~", "é", "€")) {
            expected["`$illegal`"] = listOf("MVK_LEXER_STANDARD_ERROR")
        }
        expected["`!` alone"] = listOf("MVK_LEXER_STANDARD_ERROR")
        expected["`!=` whole — the control for the row above"] = emptyList()
        expected["`#`, measured illegal and deliberately unreported"] = emptyList()
        assertEquals(expected, actual, "which characters the checker reports as untokenizable")
    }

    /**
     * The same characters inside a STRING LITERAL draw nothing, because A12 admits them there.
     *
     * This is the over-rejection guard for the rows above: a checker scanning raw text for a bad character
     * refuses conditions the kernel accepts, and that direction cannot be caught downstream. Measured on the
     * kernel for every one of these characters.
     */
    @Test
    fun thoseCharactersAreOrdinaryInsideALiteral() {
        val actual = mutableMapOf<String, List<String>>()
        val expected = mutableMapOf<String, List<String>>()
        for (character in listOf("%", "&", "|", ";", "?", "~", "é", "€", "!", "#")) {
            actual[character] = model("""[Price] == \"a${character}b\"""").conditionLexicalFindings()
                .map { it.code }
            expected[character] = emptyList()
        }
        assertEquals(expected, actual, "the same characters inside a string literal")
    }

    /** One illegal character draws ONE finding across the whole report, not one per syntax family. */
    @Test
    fun anIllegalCharacterIsReportedOnceAcrossTheWholeReport() {
        val codes = check(
            ModelSource(
                """
                {"header":{"id":"catalog","locales":[{"code":"en_US"}]},
                 "content":{"modelRoot":{"rootGroups":[
                  {"type":"Group","id":"g0","name":"Catalog","Group":{"elements":[
                    {"type":"Field","id":"f0","name":"Price","Field":{"fieldType":{"type":"StringType"}}},
                    {"type":"Rule","id":"r0","name":"PriceRule","Rule":{
                      "errorCondition":"FieldFilled(Price) And % FieldFilled(Price)",
                      "errorEntityRelPath":"../Price",
                      "errorCode":"P","errorMessage":[{"locale":"en_US","text":"x"}]}}
                  ]}}
                 ]}}}
                """.trimIndent(),
            ),
        ).findings.map { it.code }
        assertEquals(listOf("MVK_LEXER_STANDARD_ERROR"), codes,
            "a later syntax family must defer to the lexical one rather than add a second verdict")
    }

    private fun codesFor(character: String): List<String> =
        model("FieldFilled(Price) And $character FieldFilled(Price)")
            .conditionLexicalFindings().map { it.code }

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
