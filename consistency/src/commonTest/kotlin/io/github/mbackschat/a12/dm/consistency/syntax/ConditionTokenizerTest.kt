package io.github.mbackschat.a12.dm.consistency.syntax

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A tokenizer faithful to A12's own condition lexer (`Base_ConditionLexer.g4`).
 *
 * The terminals that matter most are the two that can SWALLOW text a naive scan would read as structure: a
 * string literal, where an embedded quote is DOUBLED rather than backslash-escaped, and a `;;` comment. Both
 * can contain anything, including something shaped exactly like a reference — so getting either wrong makes
 * the reference extractor report names nobody wrote.
 */
class ConditionTokenizerTest {

    private fun kinds(text: String) = tokenize(text).map { it.kind }

    private fun texts(text: String) = tokenize(text).map { it.text }

    @Test
    fun aPredicateOverABareNameIsThreeTokensAndTwoParens() {
        assertEquals(
            listOf(TokenKind.NAME, TokenKind.PUNCTUATION, TokenKind.NAME, TokenKind.PUNCTUATION),
            kinds("FieldFilled(Code)"),
        )
        assertEquals(listOf("FieldFilled", "(", "Code", ")"), texts("FieldFilled(Code)"))
    }

    @Test
    fun whitespaceSeparatesButDoesNotSurvive() {
        assertEquals(
            listOf("FieldFilled", "(", "Code", ")", "And", "True"),
            texts("FieldFilled( Code ) And True"),
        )
    }

    /**
     * A12 doubles an embedded quote and treats backslash as a plain character — there are no escape
     * sequences (`StringLiteral : '"' ( '""' | ~["] )* '"'`). A tokenizer assuming C-style escaping would
     * end this literal early and read its tail as structure.
     */
    @Test
    fun aStringLiteralDoublesItsQuotesAndKeepsBackslashesLiteral() {
        val source = """"say ""hi"" \d""""

        assertEquals(listOf(TokenKind.STRING), kinds(source))
        assertEquals(listOf(source), texts(source))
    }

    /** The case the extractor depends on: a literal that LOOKS like a condition contributes no names. */
    @Test
    fun aStringLiteralIsOneTokenEvenWhenItContainsACondition() {
        assertEquals(
            listOf(
                TokenKind.NAME, TokenKind.PUNCTUATION, TokenKind.NAME, TokenKind.PUNCTUATION,
                TokenKind.OPERATOR_SYMBOL, TokenKind.STRING,
            ),
            kinds("""FieldValueAsString(Status) == "FieldFilled(Secret)""""),
        )
    }

    @Test
    fun aCommentRunsToTheEndOfItsLine() {
        assertEquals(
            listOf(
                TokenKind.COMMENT, TokenKind.NAME, TokenKind.PUNCTUATION, TokenKind.NAME,
                TokenKind.PUNCTUATION,
            ),
            kinds(";; FieldFilled(Secret) is not here\nFieldFilled(Code)"),
        )
    }

    @Test
    fun numbersAndPathStepsTokenizeAsTheirOwnKinds() {
        assertEquals(
            listOf(
                TokenKind.PUNCTUATION, TokenKind.NAME, TokenKind.PUNCTUATION, TokenKind.OPERATOR_SYMBOL,
                TokenKind.NUMBER,
            ),
            kinds("[Qty] > 5"),
        )
        assertEquals(listOf("..", "/", "Warehouse", "/", "Sku"), texts("../Warehouse/Sku"))
    }

    /**
     * An unterminated literal is reported rather than silently swallowing the rest of the condition.
     *
     * The last two rows are the ones a passing test used to miss. Completeness was re-derived from the final
     * CHARACTER, so a literal that ran off the end while happening to end on a quote read as terminated —
     * and under A12's doubling rule (`""` is one escaped quote, never an empty literal followed by another)
     * neither of these closes. The first row cannot catch it: it ends on `s`.
     */
    @Test
    fun anUnterminatedStringLiteralIsItsOwnKind() {
        assertEquals(listOf(TokenKind.NAME, TokenKind.UNTERMINATED_STRING), kinds("""Code "oops"""))
        assertEquals(listOf(TokenKind.UNTERMINATED_STRING), kinds("\"A\"\""), "an escaped quote then nothing")
        assertEquals(listOf(TokenKind.UNTERMINATED_STRING), kinds("\"\"\""), "three quotes close nothing")
        // The controls: these DO close, so the fix cannot have made every literal unterminated.
        assertEquals(listOf(TokenKind.STRING), kinds("\"A\""), "an ordinary literal")
        assertEquals(listOf(TokenKind.STRING), kinds("\"\""), "the empty literal")
        assertEquals(listOf(TokenKind.STRING), kinds("\"A\"\"B\""), "a literal carrying an escaped quote")
    }

    /**
     * A digit run followed by a letter is ONE name (`NameOhnePunktZifferAmAnfang: ZiffernFolge STR …`), not
     * a number and then a name. A pure digit run stays a number, which is the control.
     */
    @Test
    fun aDigitLeadingNameIsOneNameToken() {
        assertEquals(listOf(TokenKind.NAME), kinds("1City"))
        assertEquals(listOf("1City"), texts("1City"))
        assertEquals(listOf(TokenKind.NUMBER), kinds("12"))
    }

    /**
     * `/` and `-` are classified as structure rather than operators, because each is a path form as well as
     * an arithmetic one and only position tells them apart. `*` and `>` have no path reading in that
     * position and stay operators — the control that keeps this from being "everything is punctuation".
     */
    @Test
    fun aPathSeparatorIsNotClassifiedAsAnOperator() {
        assertEquals(listOf(TokenKind.PUNCTUATION), kinds("/"))
        assertEquals(listOf(TokenKind.PUNCTUATION), kinds("-"))
        assertEquals(listOf(TokenKind.OPERATOR_SYMBOL), kinds("*"))
        assertEquals(listOf(TokenKind.OPERATOR_SYMBOL), kinds(">="))
    }

    /**
     * A quoted name escapes a keyword collision, so it carries the bare name AND the fact that it was
     * quoted. The kernel keeps both too, in the type rather than the text: `NameOhnePunktMitQuotes` strips
     * the quotes (`setTextUnquotedInLexer`) and is a *different terminal* from the keyword the name spells,
     * which is the only reason `'And'` can be a field. Collapsing it onto [TokenKind.NAME] would leave a
     * consumer unable to tell `'And'` from `And` and so unable to reproduce the escape.
     */
    @Test
    fun aSingleQuotedNameKeepsItsNameAndItsQuoting() {
        assertEquals(listOf(TokenKind.QUOTED_NAME), kinds("'And'"))
        assertEquals(listOf("And"), texts("'And'"))
        assertEquals(listOf(TokenKind.NAME), kinds("And"))
        assertEquals(listOf(TokenKind.QUOTED_NAME), kinds("'A1:b_ö'"), "every STR and DIGIT the terminal takes")
    }

    /**
     * A `'…'` that the name terminal cannot match, and the structure after it SURVIVES.
     *
     * The closer is the quote that follows an unbroken run of name characters, not the next quote anywhere —
     * the naive reading paired the two quotes in `'Tier) And 'x'` and handed the extractor the field name
     * `Tier) And `, which nobody wrote. The last two rows are closed and still fail, because the terminal is
     * `'` STR (STR|DIGIT)* `'`: it takes neither an empty name nor a digit-leading one. All four draw
     * `MVK_LEXER_STANDARD_ERROR` from A12 (`RuleConditionSyntaxLawsTest`).
     */
    @Test
    fun aQuotedNameTheTerminalCannotMatchIsItsOwnKindAndStopsThere() {
        assertEquals(
            listOf(
                TokenKind.NAME, TokenKind.PUNCTUATION, TokenKind.UNTERMINATED_QUOTED_NAME,
                TokenKind.PUNCTUATION, TokenKind.NAME, TokenKind.QUOTED_NAME,
            ),
            kinds("FieldFilled('Tier) And 'x'"),
            "the run stops at `)`, so the And and the closed name after it stay visible",
        )
        assertEquals(listOf("'Tier"), texts("'Tier"), "unclosed at the end of the text")
        assertEquals(listOf(TokenKind.UNTERMINATED_QUOTED_NAME), kinds("''"), "the empty name")
        assertEquals(listOf(TokenKind.UNTERMINATED_QUOTED_NAME), kinds("'1City'"), "a digit-leading name")
    }
}
