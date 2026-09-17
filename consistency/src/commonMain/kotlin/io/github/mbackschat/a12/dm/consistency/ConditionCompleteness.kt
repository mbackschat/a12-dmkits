package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.incompleteInput
import io.github.mbackschat.a12.dm.consistency.syntax.lexicalFailure

/**
 * DSL text that ends before it is finished — A12's `MVK_INCOMPLETE_INPUT`.
 *
 * The one phase-2 shape this checker can report without a grammar — and the qualifier is load-bearing, because
 * the first version of this family claimed the last significant token decides the code and that is measurably
 * false ([KERNEL-FINDINGS KF300]). It reports only where the text is delimiter-BALANCED at a trailing infix
 * operator or symbolic comparison, or ends on a comma inside an open argument list; [incompleteInput] carries
 * the measurements and why the restriction is a boundary rather than a mechanism.
 *
 * **What it deliberately stays silent about**, all of it under-reporting rather than mis-reporting: text with
 * an unclosed delimiter, which A12 may answer with either code; a trailing `-` or `/`, which the tokenizer
 * cannot tell from a path step; and every wrong-token shape, which needs the grammar. Naming a real defect
 * with the wrong code sends an author to the wrong fix, and the report's gap already says the parser phases
 * are not covered.
 *
 * **A text the lexer already choked on is never this family's**, and the ordering needs no position
 * comparison: this phase fails at the LAST significant token, and a lexical failure further along would BE
 * that token — which is not an operator, so nothing fires. So whenever both can speak the lexical failure is
 * the earlier one, and A12 names it (measured, including the row where its lexer skips the character it could
 * not take and the parser then runs out of input anyway: `rulekit.validate.laws.SyntaxPhasePrecedenceLawsTest`,
 * [KERNEL-FINDINGS KF311]). Without this guard `FieldFilled('Tier) And` drew two findings with two codes, one
 * of them a code A12 does not give for that text.
 */
internal fun TrackedFactBase.conditionCompletenessFindings(element: TrackedDeclaration): List<Finding> {
    val body = element.body ?: return emptyList()
    return conditionTexts(element.kind, body).mapNotNull { (member, text) ->
        if (lexicalFailure(text) != null) {
            return@mapNotNull null
        }
        incompleteInput(text, conditionLanguage)?.let { incomplete ->
            Finding(
                FindingCategory.A12_CONSTRAINT,
                FindingSeverity.ERROR,
                "MVK_INCOMPLETE_INPUT",
                "`$member` ends on `${incomplete.spelling}` at offset ${incomplete.at}, which still needs a " +
                    "right-hand side — A12 runs out of input reading this text",
                element.path,
                TextSpan(member, incomplete.at, incomplete.at + incomplete.spelling.length),
            )
        }
    }
}
