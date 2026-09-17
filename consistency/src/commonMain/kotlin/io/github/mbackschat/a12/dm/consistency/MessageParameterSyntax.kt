package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.messageParametersAreUnterminated

/**
 * That an error message closes every parameter it opens.
 *
 * A12 reads `$…$` pairs and answers `WRONG_NUMBER_OF_DOLLARS` for a text that opens one and never closes it
 * ([KERNEL-FINDINGS KF343], `rulekit.validate.laws.MessageParameterMembershipLawsTest`,
 * `…MessageMultipleDefectLawsTest`). No model resolution at all — the law is the text's own.
 *
 * **It refuses the text WHOLE, which is what makes the family worth its own constant rather than a branch in
 * a parameter reader.** `see $Premium$ and $Adjuster` draws this class and NOTHING about `Premium`, though
 * that parameter is complete and names an unread field. Every reader of this grammar already inherits that
 * silence from [messageParameterBodies]; this is the class A12 gives in its place, so the two halves are one
 * scan and cannot drift apart.
 *
 * **`$$` is the escape only OUTSIDE a parameter.** Inside one the first `$` closes it, so two parameters may
 * sit back to back sharing a dollar pair — measured, and the reason no branch here counts dollars.
 *
 * **The CHARACTER gate pre-empts it, both halves of it.** A text carrying a control character draws
 * `INVALID_CHARACTERS` and a line break draws `INVALID_LINEBREAK`, each ALONE, wherever in the text it sits
 * relative to the unterminated parameter — measured on both, the line-break arm rather than transferred from
 * the tab. So this asks [carriesAnOffendingCharacter] first, at MESSAGE scope, which is the scope that gate
 * was measured at.
 *
 * **A broken CONDITION withholds this verdict too**, as it does every class read out of a message member
 * ([declarationsWithUnsoundDsl]).
 */
internal fun TrackedFactBase.messageParameterSyntaxFindings(): List<Finding> {
    val unsound = declarationsWithUnsoundDsl()
    return declarations().filter { it.kind == "Rule" || it.kind == "Computation" }.flatMap { element ->
        val body = element.body ?: return@flatMap emptyList()
        if (element.path in unsound) {
            return@flatMap emptyList()
        }
        val messages = body.authoredMessages() as? AuthoredMessages.Read ?: return@flatMap emptyList()
        messages.entries.mapNotNull { message ->
            if (message.text.carriesAnOffendingCharacter() ||
                !messageParametersAreUnterminated(message.text)
            ) {
                return@mapNotNull null
            }
            finding(message, element)
        }
    }
}

private fun finding(message: AuthoredMessage, element: TrackedDeclaration): Finding = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    "WRONG_NUMBER_OF_DOLLARS",
    "in `errorMessage`${message.localeKey?.let { " for `" + it + "`" } ?: ""}: a `${'$'}` opens a " +
        "parameter the text never closes — A12 reads the whole text as malformed and says nothing about " +
        "the parameters it did close, so close it or write `${'$'}${'$'}` for a literal dollar",
    element.path,
)
