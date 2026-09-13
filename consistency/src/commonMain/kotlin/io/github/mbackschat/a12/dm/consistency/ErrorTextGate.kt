package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.delimitersBalanced

/**
 * The declarations whose own DSL is not sound, and whose error text is therefore not ours to judge.
 *
 * **Named for the DSL rather than the condition, because a COMPUTATION has none and behaves identically** —
 * its broken member is an operation, and both error-text verdicts give way to that operation's class exactly
 * as a rule's give way to its condition's. Calling this a condition gate would have been the rule carrier's
 * accident.
 *
 * **A12 withholds the error-text verdict entirely when the declaration's DSL is broken** — it has a class saying so
 * (`FehlertextErrorType.INVALID_CONDITION`) and, measured, simply reports the condition's own class and
 * nothing about the message ([KERNEL-FINDINGS KF338],
 * `rulekit.validate.laws.MessageParameterConditionDependencyLawsTest`). Both kinds of unsoundness withhold
 * it: a reference naming nothing draws `MVK_INVALID_ENTITY` alone, and a text that does not parse draws
 * `MVK_INCOMPLETE_INPUT` alone.
 *
 * **Unbalanced delimiters stand in for "does not parse", deliberately under-covering it.** This layer holds
 * no grammar, so the only sound test available is a NECESSARY condition; text that balances and is still
 * refused keeps its parameters judged, which is the direction that can only miss a finding. Withholding is
 * safe in every case here, because the verdict A12 gives is the condition's and saying nothing about the
 * message is exactly what A12 does.
 *
 * **It gates the WHOLE error-text checker, not one family.** The character law is withheld by the same
 * broken condition as the parameter law — `INVALID_CHARACTERS` gives way to `MVK_INVALID_ENTITY` exactly as
 * `INVALID_FIELD` does — so this is A12 declining to examine the message member at all, and every family
 * reading that member answers to it.
 *
 * Without this, those families report on every model that has a broken condition and a message beside it,
 * which is not a rare shape: a half-typed condition is the ordinary state of a model being edited, and it is
 * exactly when a checker is asked.
 */
internal fun PreparedModel.declarationsWithUnsoundDsl(): Set<String> {
    val unresolved = conditionReferences()
        .filter { it.target !is Reference.Resolved }
        .map { it.declaredAt }
    val unparseable = elements.mapNotNull { element ->
        val body = element.body ?: return@mapNotNull null
        val broken = conditionTexts(element.kind, body).any { (_, text) -> !delimitersBalanced(text) }
        element.path.takeIf { broken }
    }
    return (unresolved + unparseable).toSet()
}
