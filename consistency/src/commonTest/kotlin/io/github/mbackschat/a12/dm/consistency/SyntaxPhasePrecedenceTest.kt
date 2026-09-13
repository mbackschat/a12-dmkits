package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which syntax family speaks when one text carries TWO failures — the arbitration, not either verdict.
 *
 * Three families read the same DSL text and each has its own code. A12 names exactly one failure per text, and
 * it is the one sitting EARLIEST — whichever phase owns it — because its lexer is demand-driven and never
 * reaches a character the parser has not asked for ([KERNEL-FINDINGS KF311], measured in both orders for both
 * lexical classes by `rulekit.validate.laws.SyntaxPhasePrecedenceLawsTest`).
 *
 * **Every row goes through `check()` and asserts the whole report's codes**, because the two mistakes this
 * guards against are invisible from inside any one family: reporting the wrong one of two real failures, and
 * two families each reporting their own. The second was live — `FieldFilled('Price) And` drew both
 * `MVK_LEXER_STANDARD_ERROR` and `MVK_INCOMPLETE_INPUT` until the completeness family was made to yield.
 *
 * **The rows are ordered PAIRS of the same two defects**, since order is the only variable that can move
 * without changing what the defects are. A grid that varied the defects instead could not tell position from
 * phase, and the controls are each defect on its own — without them a pair's row would be silence about the
 * loser rather than the winner speaking.
 */
class SyntaxPhasePrecedenceTest {

    private fun codes(condition: String): List<String> = check(
        ModelSource(
            """
            {"header":{"id":"catalog","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Catalog","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Price","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Rule","id":"r0","name":"PriceRule","Rule":{
                  "errorCondition":${'"'}$condition${'"'},
                  "errorEntityRelPath":"../Price",
                  "errorCode":"P","errorMessage":[{"locale":"en_US","text":"x"}]}}
              ]}}
             ]}}}
            """.trimIndent(),
        ),
    ).findings.map { it.code }

    private val lexer = listOf("MVK_LEXER_STANDARD_ERROR")
    private val token = listOf("MVK_UNEXPECTED_TOKEN")

    @Test
    fun theEarlierOfAParseAndALexicalFailureIsTheOneReported() {
        val actual = mutableMapOf<String, List<String>>()
        actual["an extra closing parenthesis alone — the control"] = codes("""FieldFilled(Price))""")
        actual["an unterminated literal alone — the control"] = codes("""FieldFilled(Price) And \"x""")
        actual["an illegal character alone — the control"] = codes("""FieldFilled(Price) And %""")
        actual["an extra closer, then an unterminated literal"] = codes("""FieldFilled(Price)) And \"x""")
        actual["an unterminated literal, then an extra closer"] = codes("""\"x And FieldFilled(Price))""")
        actual["an extra closer, then an illegal character"] = codes("""FieldFilled(Price)) And %""")
        actual["an illegal character, then an extra closer"] = codes("""% And FieldFilled(Price))""")
        actual["an extra closer immediately followed by a bad quote"] = codes("""FieldFilled(Price))\"x""")

        val expected = mutableMapOf<String, List<String>>()
        expected["an extra closing parenthesis alone — the control"] = token
        expected["an unterminated literal alone — the control"] = lexer
        expected["an illegal character alone — the control"] = lexer
        expected["an extra closer, then an unterminated literal"] = token
        expected["an unterminated literal, then an extra closer"] = lexer
        expected["an extra closer, then an illegal character"] = token
        expected["an illegal character, then an extra closer"] = lexer
        expected["an extra closer immediately followed by a bad quote"] = token
        assertEquals(expected, actual, "which of two syntax failures the checker reports")
    }

    /**
     * The completeness family against the lexical one, which needs no position comparison.
     *
     * That phase fails at the LAST significant token, so a lexical failure further along would BE that token
     * and never trigger it — whenever both can speak, the lexical one is earlier. The control is the same
     * text with the bad character removed, which is what makes a `MVK_LEXER_STANDARD_ERROR` row the lexer
     * WINNING rather than the completeness family having quietly stopped working.
     */
    @Test
    fun aTextTheLexerChokedOnIsNeverReportedAsIncomplete() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a trailing connective alone — the control"] = codes("""FieldFilled(Price) And""")
        actual["a bad quote before a trailing connective"] = codes("""FieldFilled('Price) And""")
        actual["an illegal character before a trailing connective"] = codes("""FieldFilled(%Price) And""")

        val expected = mutableMapOf<String, List<String>>()
        expected["a trailing connective alone — the control"] = listOf("MVK_INCOMPLETE_INPUT")
        expected["a bad quote before a trailing connective"] = lexer
        expected["an illegal character before a trailing connective"] = lexer
        assertEquals(expected, actual, "which family owns a text that is both unlexable and unfinished")
    }

    /**
     * A parse error sitting AFTER the lexical one, which stops the rule collapsing into "the parser wins".
     *
     * Both pairs above put the parse error first wherever the grammar family wins, so they are equally
     * consistent with a fixed phase precedence. These rows move it behind the lexical failure and keep
     * everything else; a stray `]` draws `MVK_UNEXPECTED_TOKEN` on its own and must not here.
     */
    @Test
    fun aParseErrorLaterInTheTextDoesNotOverrideTheLexer() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a stray closing bracket alone — the control"] = codes("""FieldFilled(Price) ]""")
        actual["a bad quote before a stray closing bracket"] = codes("""FieldFilled('Price) ]""")
        actual["an illegal character before a stray closing bracket"] = codes("""FieldFilled(%Price) ]""")

        val expected = mutableMapOf<String, List<String>>()
        expected["a stray closing bracket alone — the control"] = token
        expected["a bad quote before a stray closing bracket"] = lexer
        expected["an illegal character before a stray closing bracket"] = lexer
        assertEquals(expected, actual, "whether a parse error behind a lexical one takes the report")
    }
}
