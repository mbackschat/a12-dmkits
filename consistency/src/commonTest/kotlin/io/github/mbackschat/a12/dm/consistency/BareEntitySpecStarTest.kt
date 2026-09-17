package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.TokenKind
import io.github.mbackschat.a12.dm.consistency.syntax.tokenize
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A starred spelling is NOT a bare entity spec, and the tokenizer is why.
 *
 * This locks a correction rather than a feature. The accepted-punctuation set listed `"*"` behind a comment
 * promising that a spec "may carry … a repetition star", and it could never admit one: a star arrives as
 * `OPERATOR_SYMBOL`, which the token test refuses before punctuation is consulted. The dead entry made the
 * silence look deliberate, so the next reader would have taken a dozen families' quietness on starred
 * operands for a measured decision instead of an unmeasured one.
 *
 * Both halves are asserted, because either alone can drift: that the star is not punctuation (the
 * mechanism), and that the reader refuses it while admitting the same path unstarred (the consequence).
 *
 * That the entry really was DEAD is itself measured: a mutant putting `"*"` back into the accepted set
 * survives this class and every other (2026-09-17), which is what "dead data" means and is why no test could
 * have caught it. The claim it carried had to be corrected by reading, not by a failing build.
 */
class BareEntitySpecStarTest {

    @Test
    fun theStarArrivesAsAnOperatorRatherThanAsPunctuation() {
        assertEquals(
            listOf(TokenKind.NAME, TokenKind.OPERATOR_SYMBOL, TokenKind.PUNCTUATION, TokenKind.NAME),
            tokenize("Claims*/Amount").map { it.kind },
            "the star is the operator symbol, which is what keeps it out of an entity spec",
        )
    }

    @Test
    fun aStarredPathIsRefusedWhereTheSamePathUnstarredIsAdmitted() {
        assertEquals(
            mapOf("Claims/Amount" to true, "Claims*/Amount" to false, "[Claims*/Amount]" to false),
            listOf("Claims/Amount", "Claims*/Amount", "[Claims*/Amount]")
                .associateWith { isBareEntitySpec(it) },
            "the unstarred control is what makes this a statement about the STAR and not about the path",
        )
    }
}
