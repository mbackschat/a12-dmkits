package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.NameTerminal
import io.github.mbackschat.a12.dm.consistency.syntax.formLineParameterName
import io.github.mbackschat.a12.dm.consistency.syntax.messageParameterBodies
import io.github.mbackschat.a12.dm.consistency.syntax.nameTerminalOf
import io.github.mbackschat.a12.dm.consistency.syntax.plainParameterName

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
internal fun TrackedFactBase.messageParameterFindings(): List<Finding> {
    val readBy = entitiesReadByDeclaration()
    val unsound = declarationsWithUnsoundDsl()
    return declarations().flatMap { element ->
        val body = element.body ?: return@flatMap emptyList()
        if (element.path in unsound) {
            return@flatMap emptyList()
        }
        val membership = readBy[element.path] ?: return@flatMap emptyList()
        val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
        val messages = body.authoredMessages() as? AuthoredMessages.Read ?: return@flatMap emptyList()
        messages.entries.flatMap { message ->
            // A12 gates the text on its CHARACTERS before it reads a parameter out of it, so a message
            // carrying a control character draws that class and nothing about its parameters — wherever the
            // character sits, which is what makes this a gate rather than a race (KF343). Applied per
            // message, because a sibling locale's text is judged on its own.
            if (message.text.carriesAnOffendingCharacter()) {
                return@flatMap emptyList()
            }
            messageParameterBodies(message.text).mapNotNull { parameter ->
                // The TERMINALS are recognised before any lookup, and each language recognises only its own
                // spelling — so the other's is an ordinary name here and draws a reference class below.
                when (nameTerminalOf(parameter, conditionLanguage)) {
                    NameTerminal.RULE_GROUP -> return@mapNotNull ruleGroupFinding(parameter, element)
                    // The model's own declaration answers this one, from [baseYearRequirementFindings].
                    NameTerminal.BASE_YEAR -> return@mapNotNull null
                    null -> Unit
                }
                formLineParameterName(parameter, conditionLanguage)?.let { addressed ->
                    return@mapNotNull formLineDefect(addressed, group, element, parameter)
                }
                val name = plainParameterName(parameter, conditionLanguage) ?: return@mapNotNull null
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
 * The one gate a FORM-LINE parameter still meets: what it addresses has to be a FIELD.
 *
 * `$X.Vordruckzeile$` and `$X.Vordruckname$` skip the membership check the plain form goes through, which is
 * why this family reads them separately — but `checkValidFieldName` still refuses a GROUP with
 * `INVALID_FIELD_IN_PARAMETER`, measured on one condition against the admitted field twin.
 *
 * **Silent on an address that does not resolve**, deliberately: the kernel reaches that shape through
 * `getEntityRef`, which RAISES past every typed channel rather than composing a class ([KF259]), so there is
 * no verdict to reproduce.
 */
private fun TrackedFactBase.formLineDefect(
    addressed: String,
    group: String,
    element: TrackedDeclaration,
    parameter: String,
): Finding? {
    val resolved = (resolveEntity(group, addressed, element.origin) as? Reference.Resolved)?.path
        ?: return null
    if (declarations().firstOrNull { it.path == resolved }?.declaredKind != DeclaredKind.GROUP) {
        return null
    }
    return Finding(
        FindingCategory.A12_CONSTRAINT,
        FindingSeverity.ERROR,
        "INVALID_FIELD_IN_PARAMETER",
        "in `errorMessage`: `${'$'}$parameter${'$'}` puts a form-line suffix on `$resolved`, which is a " +
            "GROUP — the suffix reads a field's own form position, so it takes a field",
        element.path,
    )
}

/**
 * The rule-group shorthand, which this position RECOGNISES and refuses rather than looking up.
 *
 * `$RuleGroup$` / `$RegelKontext$` name a group, and a rule-owned parameter takes a field — so A12 answers
 * `INVALID_PARAMETER` ("recognised, refused here") where the other language's spelling draws `INVALID_ENTITY`
 * instead. That pair is the instrument the language grid uses, and reporting the reference class on the
 * own-language spelling would name the wrong one of the two.
 */
private fun ruleGroupFinding(parameter: String, element: TrackedDeclaration): Finding = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    "INVALID_PARAMETER",
    "in `errorMessage`: `$parameter` is the rule-group shorthand, which names a GROUP — this position takes " +
        "a field, and the shorthand belongs in the `${'$'}#…${'$'}` group position instead",
    element.path,
)

/**
 * Which entities each rule and computation READS, keyed by the declaration's path.
 *
 * Built once for the whole model rather than per declaration, because [conditionReferences] walks every
 * element's texts and asking it per declaration would rescan the model for each message.
 */
internal fun TrackedFactBase.entitiesReadByDeclaration(): Map<String, Membership> {
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
    return declarations().filter { it.kind == "Rule" || it.kind == "Computation" }.associate { element ->
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
internal data class Membership(private val paths: Set<String>, val memberName: String) {
    operator fun contains(path: String): Boolean = path in paths
}
