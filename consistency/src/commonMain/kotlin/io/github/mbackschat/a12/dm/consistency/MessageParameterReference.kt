package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.namesRuleContext
import io.github.mbackschat.a12.dm.consistency.syntax.referencesIn
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
internal fun TrackedFactBase.messageParameterFindings(): List<Finding> = declarations().flatMap { element ->
    messageContributions(element) { messageParameterFindings(element, it) }.map { it.finding }
}

internal fun TrackedFactBase.messageParameterFindings(element: TrackedDeclaration, message: AuthoredMessage): List<Finding> {
    val membership = entitiesReadBy(element)
    val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
    // A12 gates the text on its CHARACTERS before it reads a parameter out of it, so a message
    // carrying a control character draws that class and nothing about its parameters — wherever the
    // character sits, which is what makes this a gate rather than a race (KF343). Applied per
    // message, because a sibling locale's text is judged on its own.
    if (message.text.carriesAnOffendingCharacter()) {
        return emptyList()
    }
    return messageParameterBodies(message.text).mapNotNull { parameter ->
        // The TERMINALS are recognised before any lookup, and each language recognises only its own
        // spelling — so the other's is an ordinary name here and draws a reference class below.
        when (nameTerminalOf(parameter, conditionLanguage)) {
            NameTerminal.RULE_GROUP -> return@mapNotNull ruleGroupFinding(parameter, element)
            // The model's own declaration answers this one, from [messageBaseYearFindings].
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
    if (declarationAt(resolved)?.declaredKind != DeclaredKind.GROUP) {
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

/** Message membership includes fields reached through group reads and the computation's own target. */
internal fun TrackedFactBase.entitiesReadBy(element: TrackedDeclaration): Membership {
    val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
    val paths = messageConditionReferences(element).mapNotNull { written ->
        (resolveEntity(group, written.replace("*", ""), element.origin) as? Reference.Resolved)?.path
    }.flatMap { messageReferenceFootprint(it) }.toSet() +
        listOfNotNull(element.declaredReference()?.takeIf { it.role == ReferenceRole.COMPUTED_TARGET }
            ?.target?.let { (it as? Reference.Resolved)?.path })
    return Membership(paths, if (element.kind == "Rule") "errorCondition" else "computation's own members")
}

/** The rule-context token is a read too; the syntax reader deliberately returns only ordinary paths. */
internal fun TrackedFactBase.messageConditionReferences(element: TrackedDeclaration): List<String> {
    val body = element.body ?: return emptyList()
    val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
    return conditionTexts(element.kind, body).flatMap { (_, text) ->
        referencesIn(text, conditionLanguage) +
            if (namesRuleContext(text, conditionLanguage)) listOf(group) else emptyList()
    }
}

/** KF335's group-read witness applies to membership and wildcard gates, not just the plain parameter. */
internal fun TrackedFactBase.messageReferenceFootprint(path: String): List<String> =
    listOf(path) + declarationAt(path)?.let { fieldsUnder(it) }.orEmpty().map { it.path }

/**
 * The entities one declaration reads, with the member a reader would have to edit to add one.
 *
 * The member name travels with the set because the fix differs by carrier and a finding that said only
 * "this declaration never reads it" would leave an author looking through three members of a computation.
 */
internal data class Membership(private val paths: Set<String>, val memberName: String) {
    operator fun contains(path: String): Boolean = path in paths
    fun reachesGroup(group: String): Boolean = paths.any { it == group || it.startsWith("$group/") }
}
