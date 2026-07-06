package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.ast.Cond
import io.github.mbackschat.a12.dm.interpreter.ast.Expr
import io.github.mbackschat.a12.dm.interpreter.parse.parseCondition
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The kernel's `Comment` lexer token is `;; …` to end of line, WITH a multi-line continuation form
 * (`Base_ConditionLexer.g4`: `';;' ~('\r'|'\n')* (('\r'|'\n') (ws)* ';;' ~('\r'|'\n')*)*`) — the parser grammar
 * lets it ride `before`/`after` a (sub)condition/operation (`teilBedingung`/`operation`), so it can appear at
 * the start, the end, and — as the `before` of a parenthesized inner condition — mid-expression. Real stored
 * workspace conditions carry such comments. The interpreter skips `;;` (and its continuation) wherever it skips
 * whitespace, so a commented condition parses to the same AST as its comment-free twin — while `;;` INSIDE a
 * string literal stays data. WI-P (SEMANTICS-FORMALIZATION-PROPOSAL, from Phase-0 S1; INTERPRETER-FINDINGS IF55).
 */
class CommentSkipParseTest {

    @Test
    fun leadingCommentIsSkipped() =
        assertEquals(
            parseCondition("FieldFilled(A)", "/G"),
            parseCondition(";; a leading note\nFieldFilled(A)", "/G"),
        )

    @Test
    fun trailingCommentIsSkipped() =
        assertEquals(
            parseCondition("FieldFilled(A)", "/G"),
            parseCondition("FieldFilled(A)  ;; a trailing note", "/G"),
        )

    @Test
    fun multiLineContinuationCommentIsSkipped() =
        assertEquals(
            parseCondition("FieldFilled(A) And FieldFilled(B)", "/G"),
            // a `;;` run continued across lines (each continued line re-opens with `;;`) between the operands.
            parseCondition("FieldFilled(A) ;; note line 1\r\n   ;; note line 2\r\n And FieldFilled(B)", "/G"),
        )

    @Test
    fun commentAfterAnOpeningParenIsSkipped() =
        assertEquals(
            parseCondition("(FieldFilled(A) Or FieldFilled(B))", "/G"),
            parseCondition("( ;; explains the alternative\n FieldFilled(A) Or FieldFilled(B))", "/G"),
        )

    @Test
    fun semicolonsInsideAStringLiteralAreData() {
        // ";;" inside quotes is a value, never a comment — readString does not skip.
        val cmp = parseCondition("""[A] == ";; not a comment"""", "/G") as Cond.Cmp
        assertEquals(";; not a comment", (cmp.right as Expr.StrLit).v)
    }
}
