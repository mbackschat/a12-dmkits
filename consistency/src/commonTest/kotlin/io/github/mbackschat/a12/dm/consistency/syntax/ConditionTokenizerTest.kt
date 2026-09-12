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

    /** An unterminated literal is reported rather than silently swallowing the rest of the condition. */
    @Test
    fun anUnterminatedStringLiteralIsItsOwnKind() {
        assertEquals(listOf(TokenKind.NAME, TokenKind.UNTERMINATED_STRING), kinds("""Code "oops"""))
    }

    /** A quoted name escapes a keyword collision; A12 strips the quotes, so the text is the bare name. */
    @Test
    fun aSingleQuotedNameIsAName() {
        assertEquals(listOf(TokenKind.NAME), kinds("'And'"))
        assertEquals(listOf("And"), texts("'And'"))
    }
}
