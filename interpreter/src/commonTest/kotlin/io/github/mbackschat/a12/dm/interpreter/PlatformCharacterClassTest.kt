package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.SingleRowDoc
import io.github.mbackschat.a12.dm.interpreter.eval.renderMessage
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.parse.parseCondition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * IF415 — the characters the interpreter classifies read the same on the JVM and in Node. Each row is one a
 * platform's own Unicode table answered differently, or one A12 answers differently from that table: a value's
 * digits follow the JVM's `Character.digit`, which A12's Document JSON ingress converts with (KF564), while the
 * condition and message-parameter lexers keep the kernel grammars' ASCII digit, name and whitespace sets
 * (`MVK_LEXER_STANDARD_ERROR` for `[Quantity] > ٣` and for a no-break space inside `$…$`, `rule check`
 * 2026-10-21). `DecimalDigitsJvmOracleTest` holds the digit table to `Character` over the whole BMP.
 */
class PlatformCharacterClassTest {

    @Test
    fun aValueDigitIsWhatTheJvmReadsAsOne() {
        val rows = mapOf(
            '7' to 7, '٣' to 3, '５' to 5, '߉' to 9, '༩' to 9, '꯰' to 0,
            '²' to -1, '٪' to -1, '\u065F' to -1, '༪' to -1, '᧚' to -1, Char(0xD835) to -1, 'a' to -1,
        )
        assertEquals(rows, rows.mapValues { (c, _) -> DecimalDigits.valueOf(c) })
    }

    @Test
    fun aConditionNumberIsAsciiDigitsOnly() {
        parseCondition("[Quantity] > 3", "/Order")
        for (literal in listOf("٣", "５", "1٣", "3.٥")) {
            assertFailsWith<IllegalArgumentException>(literal) { parseCondition("[Quantity] > $literal", "/Order") }
        }
    }

    @Test
    fun aConditionNameIsAsciiOrAGermanLetter() {
        assertEquals(parseCondition("FieldFilled('Prämie')", "/G"), parseCondition("FieldFilled(Prämie)", "/G"))
        assertFailsWith<IllegalArgumentException> { parseCondition("FieldFilled(Café)", "/G") }
    }

    @Test
    fun aMessageTokenGapIsTheKernelsAsciiWhitespace() {
        val ctx = SingleRowDoc("/G", mapOf("/G/N" to "4"), mapOf("/G/N" to Kind.NUMBER))
        assertEquals("n=4", renderMessage("n=\$ N.value\$", "/G", ctx))
        assertEquals("n=\$\u00A0N.value\$", renderMessage("n=\$\u00A0N.value\$", "/G", ctx))
        assertEquals("n=\$\u2003N.value\$", renderMessage("n=\$\u2003N.value\$", "/G", ctx))
    }
}
