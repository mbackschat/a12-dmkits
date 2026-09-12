package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.incompleteInput

/**
 * DSL text that ends before it is finished — A12's `MVK_INCOMPLETE_INPUT`.
 *
 * The second syntax phase decidable without a grammar, because the answer depends only on the last
 * significant token. The measured class is the infix operators, the symbolic comparison and arithmetic
 * operators, and a trailing comma ([KERNEL-FINDINGS KF300]).
 *
 * **What it deliberately stays silent about.** A dangling PATH operator is `MVK_UNEXPECTED_TOKEN`, a code
 * this checker cannot yet decide, so it is left to the grammatical increment rather than reported under the
 * nearest available code. Silence there is correctness: naming a real defect with the wrong code sends an
 * author to the wrong fix, and the report's gap already says the parser phases are not covered.
 */
fun PreparedModel.conditionCompletenessFindings(): List<Finding> = elements.flatMap { element ->
    val body = element.body ?: return@flatMap emptyList()
    conditionTexts(element.kind, body).mapNotNull { (member, text) ->
        incompleteInput(text, conditionLanguage)?.let { incomplete ->
            Finding(
                FindingCategory.A12_CONSTRAINT,
                FindingSeverity.ERROR,
                "MVK_INCOMPLETE_INPUT",
                "`$member` ends on `${incomplete.spelling}` at offset ${incomplete.at}, which still needs a " +
                    "right-hand side — A12 runs out of input reading this text",
                element.path,
            )
        }
    }
}
