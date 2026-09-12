package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.conditionShapeFailure
import io.github.mbackschat.a12.dm.consistency.syntax.incompleteInput
import io.github.mbackschat.a12.dm.consistency.syntax.lexicalFailure
import io.github.mbackschat.a12.dm.consistency.syntax.operationShapeFailure

/**
 * DSL text that does not FORM a condition — A12's last two parse codes.
 *
 * The syntax layer's phases run in A12's own order and the first one to speak owns the text: a member that
 * cannot be tokenized is the lexical family's, one that ends on a dangling operator is the completeness
 * family's, and only what survives both reaches the grammar. Without that ordering the same defect would draw
 * two findings with two codes, and an author would have to guess which one names the fix.
 *
 * **The parser is deliberately looser than A12's grammar** and the reason is in `conditionShapeFailure`: it
 * checks the shape the productions share rather than each operator's own argument arity, because a
 * per-operator reproduction would be a second catalog and every disagreement would refuse a condition A12
 * admits. So this family is silent about a call with the wrong number of arguments, and says so in the
 * report's gap.
 *
 * **A computation's `operation` is parsed with the expression entry rather than the condition one**, because
 * A12 parses it with `operationSnippet` rather than `bedingungSnippet` — a bare `42.00` is a legal operation
 * and not a legal condition, so running one text through the other's entry would report a defect that is not
 * there.
 */
fun PreparedModel.conditionGrammarFindings(): List<Finding> = elements.flatMap { element ->
    val body = element.body ?: return@flatMap emptyList()
    conditionTexts(element.kind, body).mapNotNull { (member, text) ->
        // An earlier phase already speaks for this text, so the grammar stays quiet about it.
        if (lexicalFailure(text) != null || incompleteInput(text, conditionLanguage) != null) {
            return@mapNotNull null
        }
        val failure = if (member.endsWith("operation")) {
            operationShapeFailure(text, conditionLanguage)
        } else {
            conditionShapeFailure(text, conditionLanguage)
        } ?: return@mapNotNull null
        Finding(
            FindingCategory.A12_CONSTRAINT,
            FindingSeverity.ERROR,
            if (failure.ranOutWithDelimiterOpen) "MVK_EXPECTED_TOKEN_NOT_FOUND" else "MVK_UNEXPECTED_TOKEN",
            if (failure.ranOutWithDelimiterOpen) {
                "`$member` ends at offset ${failure.at} with a bracket still open, so A12's parser reaches " +
                    "the end of the text still waiting for what would close it"
            } else {
                "`$member` carries `${failure.found}` at offset ${failure.at}, which A12's grammar cannot " +
                    "use where it stands"
            },
            element.path,
        )
    }
}
