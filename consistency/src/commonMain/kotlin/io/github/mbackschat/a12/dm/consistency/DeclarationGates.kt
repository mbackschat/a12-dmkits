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

/**
 * The rules whose error TARGET does not resolve — A12 reports that and stops examining the rule.
 *
 * **It terminates the whole declaration, not one member.** With a broken target the condition's own
 * `MVK_INVALID_ENTITY` is gone, and so are both error-text classes, leaving A12 reporting only about the
 * target ([KERNEL-FINDINGS KF339], `rulekit.validate.laws.RuleMemberVerdictWithholdingLawsTest`). So every
 * family that would report on such a declaration is naming a verdict A12 withheld.
 *
 * **A PATH does not identify a declaration, which is why the result is narrowed here.** [PreparedModel]
 * builds an element's path from its parent and its NAME with no kind in it, so a rule and a field sharing a
 * name in one group share a path — a model A12 refuses and this checker reports, so the shape is reachable
 * rather than theoretical. Withholding on that path would delete the FIELD's own findings, which A12
 * withheld nothing about, so a shared path is left alone and the rule's own over-report stays. Narrowing
 * rather than widening because the caller matches findings by path and cannot tell the two apart either.
 *
 * **How far past the DSL members the withholding reaches is not settled**: the discriminator tried for it,
 * an illegal rule name, makes the model unbuildable and so moves two variables. The caller therefore
 * withholds every finding at such a path except those A12 gives about the target itself.
 *
 * @param declared the model's declared references, passed in rather than recomputed — the caller already
 *     holds them, and two expressions of one query can drift apart while both stay green.
 */
internal fun PreparedModel.declarationsWithABrokenErrorTarget(
    declared: List<DeclaredReference>,
): Set<String> {
    val declarationsPerPath = elements.groupingBy { it.path }.eachCount()
    return declared
        .filter { it.role == ReferenceRole.ERROR_TARGET && it.target !is Reference.Resolved }
        .map { it.declaredAt }
        .filter { declarationsPerPath[it] == 1 }
        .toSet()
}

/**
 * The declarations whose condition names something that does not resolve — A12 answers with THAT and nothing
 * else about the text.
 *
 * **A12 reports the FIRST defect in the text and nothing after it**, so an unresolvable name withholds every
 * class that would have been drawn later in the same text — the syntax ones and the operand ones alike
 * ([KERNEL-FINDINGS KF342], `rulekit.validate.laws.ConditionClassWithholdingLawsTest`). It is not "parse,
 * then resolve": a text with an unclosed delimiter AND a bad name ahead of it still answers with the entity
 * class, so resolution reaches text the parser never approved.
 *
 * **The caller uses this set position-BLIND, which under-reports** where the other defect comes first — the
 * disclosed cost recorded with `CONDITION_TEXT_CODES`, since a finding carries no offset into the text.
 *
 * **This is the shape a family-per-defect-kind checker gets wrong on ordinary input.** Eleven families read
 * the same condition text and each reports what it finds, so any condition carrying two defects — the normal
 * state of one being written — draws classes A12 withheld.
 *
 * **Withheld per DECLARATION rather than per TEXT, which is measured and not merely the safe reading.** A
 * computation carries several texts, and a name that does not resolve in its PRECONDITION withholds the
 * syntax class of its OPERATION (`ComputationMemberVerdictLawsTest`). A gate applied one text at a time
 * would report that syntax class.
 */
internal fun PreparedModel.declarationsWithAnUnresolvableConditionReference(): Set<String> =
    conditionReferences()
        .filter { it.target !is Reference.Resolved }
        .map { it.declaredAt }
        .toSet()
