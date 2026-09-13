package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.delimitersBalanced
import io.github.mbackschat.a12.dm.consistency.syntax.plainMessageParameters

/**
 * That an error message only names entities its own declaration already reads.
 *
 * A12 interpolates a message parameter from the values the declaration has in hand, so a parameter naming
 * something the declaration never touches has nothing to interpolate and is refused with `INVALID_FIELD`
 * ([KERNEL-FINDINGS KF335], `rulekit.validate.laws.MessageParameterMembershipLawsTest`).
 *
 * **The membership set is the CARRIER's, which is why this is one family and not two.** A rule's is the
 * entities its `errorCondition` references — and the error target is necessarily among them, since A12
 * refuses a rule whose error field the condition does not name, so there is no second term. A computation
 * has no condition to be a member of and answers to its own members instead: its computed target, plus
 * whatever its preconditions and operations read. Both measured; a family skipping computations under-
 * reports on a carrier A12 gates just as tightly.
 *
 * **A starred condition reference satisfies the plain form**, which is why matching resolved paths is
 * enough — a condition reading the flatten of `Claims*` then `Severity` admits the plain parameter naming
 * that field (`RuleMessageParameterLawsTest.onlyTheValueSuffixNeedsANonStarredReference`). The `.value`
 * suffix over a starred reference is refused, but under a class of its own, so it is not this family's.
 *
 * **A parameter that does not RESOLVE is reported too, under A12's own error-text vocabulary.** The address
 * is asked before membership, and its four outcomes draw unprefixed `FehlertextErrorType` members rather
 * than the `MVK_*` codes the same outcomes draw inside a condition ([KF336], [ReferenceRole.MESSAGE_PARAMETER]).
 * The short-name fallback is what makes that worth reporting: it is LOCAL-FIRST and then model-wide, so a
 * bare name the declaring group does not own can match several fields and A12 refuses the address itself —
 * the shape a checker silently resolving to one candidate would judge for membership and report wrongly.
 *
 * **Two shapes are still left to A12**: an unterminated parameter (`WRONG_NUMBER_OF_DOLLARS`) and every
 * parameter form beyond the plain one — the category suffix, the semantic-index key, the row specifier, the
 * base-year terminal. What this reader takes, and why it takes so little, is [plainMessageParameters].
 */
fun PreparedModel.messageParameterFindings(): List<Finding> {
    val readBy = entitiesReadByDeclaration()
    val unsound = declarationsWithAnUnsoundCondition()
    return elements.flatMap { element ->
        val body = element.body ?: return@flatMap emptyList()
        if (element.path in unsound) {
            return@flatMap emptyList()
        }
        val membership = readBy[element.path] ?: return@flatMap emptyList()
        val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
        val messages = body.authoredMessages() as? AuthoredMessages.Read ?: return@flatMap emptyList()
        messages.entries.flatMap { message ->
            plainMessageParameters(message.text).mapNotNull { name ->
                val address = resolveEntity(group, name, element.origin)
                val resolved = (address as? Reference.Resolved)?.path
                    ?: return@mapNotNull address.asFinding(ReferenceRole.MESSAGE_PARAMETER, element.path,
                        "errorMessage")
                if (resolved in membership) {
                    return@mapNotNull null
                }
                Finding(
                    FindingCategory.A12_CONSTRAINT,
                    FindingSeverity.ERROR,
                    "INVALID_FIELD",
                    "in `errorMessage`: `$name` resolves to `$resolved`, which this declaration never " +
                        "reads — A12 interpolates a parameter from what the declaration has in hand, so " +
                        "either name it in the `${membership.memberName}` or drop the parameter",
                    element.path,
                )
            }
        }
    }
}

/**
 * The declarations whose own condition text is not sound, and whose parameters are therefore not this
 * family's to judge.
 *
 * **A12 withholds the parameter verdict entirely when the condition is broken** — it has a class saying so
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
 * Without this the family reports on every model that has a broken condition and a message beside it, which
 * is not a rare shape — a half-typed condition is the ordinary state of a model being edited, and it is
 * exactly when a checker is asked.
 */
private fun PreparedModel.declarationsWithAnUnsoundCondition(): Set<String> {
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
 * Which entities each rule and computation READS, keyed by the declaration's path.
 *
 * Built once for the whole model rather than per declaration, because [conditionReferences] walks every
 * element's texts and asking it per declaration would rescan the model for each message.
 */
private fun PreparedModel.entitiesReadByDeclaration(): Map<String, Membership> {
    val computedTarget = declaredReferences()
        .filter { it.role == ReferenceRole.COMPUTED_TARGET }
        .mapNotNull { d -> (d.target as? Reference.Resolved)?.path?.let { d.declaredAt to it } }
        .toMap()
    val read = mutableMapOf<String, MutableSet<String>>()
    conditionReferences().forEach { reference ->
        (reference.target as? Reference.Resolved)?.path?.let {
            read.getOrPut(reference.declaredAt) { mutableSetOf() }.add(it)
        }
    }
    return elements.filter { it.kind == "Rule" || it.kind == "Computation" }.associate { element ->
        val paths = read[element.path].orEmpty() + listOfNotNull(computedTarget[element.path])
        val memberName = if (element.kind == "Rule") "errorCondition" else "computation's own members"
        element.path to Membership(paths, memberName)
    }
}

/**
 * The entities one declaration reads, with the member a reader would have to edit to add one.
 *
 * The member name travels with the set because the fix differs by carrier and a finding that said only
 * "this declaration never reads it" would leave an author looking through three members of a computation.
 */
private data class Membership(private val paths: Set<String>, val memberName: String) {
    operator fun contains(path: String): Boolean = path in paths
}
