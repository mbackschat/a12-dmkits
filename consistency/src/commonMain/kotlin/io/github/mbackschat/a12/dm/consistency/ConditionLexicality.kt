package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.lexicalFailure

/**
 * The first SYNTAX family: DSL text A12's lexer cannot tokenize.
 *
 * A12 reports `MVK_LEXER_STANDARD_ERROR` for it, one of four phase-specific parse codes and the only one
 * decidable without a grammar ([KERNEL-FINDINGS KF300], measured through `checkConsistency` by
 * `rulekit.validate.laws.RuleConditionSyntaxLawsTest`). That is why it lands before the rest of the syntax
 * layer rather than with it: a lexical failure follows from the characters alone, so reporting it cannot
 * reject text A12 would have accepted — the one direction this checker must never take.
 *
 * It covers every DSL-bearing member, not only a rule's condition, by reusing the enumeration the reference
 * family already walks. A second copy of that list would be the member the two families disagreed about.
 */
fun PreparedModel.conditionLexicalFindings(): List<Finding> = elements.flatMap { element ->
    val body = element.body ?: return@flatMap emptyList()
    conditionTexts(element.kind, body).mapNotNull { (member, text) ->
        lexicalFailure(text)?.let { failure ->
            Finding(
                FindingCategory.A12_CONSTRAINT,
                FindingSeverity.ERROR,
                "MVK_LEXER_STANDARD_ERROR",
                "`$member` opens a string literal at offset ${failure.at} that is never closed, so A12's " +
                    "lexer cannot read this text at all. A12 escapes a quote inside a literal by DOUBLING " +
                    "it (`\"\"`), not with a backslash",
                element.path,
            )
        }
    }
}
