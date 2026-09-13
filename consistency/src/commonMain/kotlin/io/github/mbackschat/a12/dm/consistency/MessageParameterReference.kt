package io.github.mbackschat.a12.dm.consistency

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
 * **Three shapes that look exactly like this failure are left to A12**, each measured to draw a different
 * class: a name resolving to nothing (`INVALID_ENTITY`), an unterminated parameter
 * (`WRONG_NUMBER_OF_DOLLARS`), and every parameter form beyond the plain one — the category suffix, the
 * semantic-index key, the row specifier, the base-year terminal. What this reader takes, and why it takes so
 * little, is [plainMessageParameters].
 */
fun PreparedModel.messageParameterFindings(): List<Finding> {
    val readBy = entitiesReadByDeclaration()
    return elements.flatMap { element ->
        val body = element.body ?: return@flatMap emptyList()
        val membership = readBy[element.path] ?: return@flatMap emptyList()
        val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
        val messages = body.authoredMessages() as? AuthoredMessages.Read ?: return@flatMap emptyList()
        messages.entries.flatMap { message ->
            plainMessageParameters(message.text).mapNotNull { name ->
                val resolved = (resolveEntity(group, name, element.origin) as? Reference.Resolved)?.path
                    ?: return@mapNotNull null
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
