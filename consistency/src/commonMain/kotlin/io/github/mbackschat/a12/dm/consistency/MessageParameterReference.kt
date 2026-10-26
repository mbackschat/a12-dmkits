package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.SEMANTIC_INDEX
import io.github.mbackschat.a12.dm.consistency.syntax.namesRuleContext
import io.github.mbackschat.a12.dm.consistency.syntax.semanticIndexUsesIn
import io.github.mbackschat.a12.dm.consistency.syntax.referencesIn
import io.github.mbackschat.a12.dm.consistency.syntax.MessageCompletion
import io.github.mbackschat.a12.dm.consistency.syntax.MessageParameter
import io.github.mbackschat.a12.dm.consistency.syntax.MessageSuffix

/** Membership precedes value/category checks; form-position suffixes ask only for a field (KF335, KF501). */
internal fun TrackedFactBase.completedFieldDefect(parameter: MessageParameter.Field, element: TrackedDeclaration): MessageCompletion {
    if (parameter.entity.ruleGroup) return MessageCompletion.Refused("INVALID_PARAMETER")
    val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
    val path = (resolveMessageEntity(group, parameter.entity.written.replace("'", ""), element.origin)
        as? Reference.Resolved)?.path ?: return MessageCompletion.Declined
    if (parameter.suffix == MessageSuffix.FORM_LINE || parameter.suffix == MessageSuffix.FORM_NAME) {
        return if (declarationAt(path)?.declaredKind == DeclaredKind.GROUP)
            MessageCompletion.Refused("INVALID_FIELD_IN_PARAMETER") else MessageCompletion.Accepted
    }
    // A keyed GROUP in the field position is refused by the key's own class, whatever the condition reads (KF562).
    if (parameter.key != null && declarationAt(path)?.declaredKind == DeclaredKind.GROUP) {
        return MessageCompletion.Refused("MVK_INDEX_FOR_ERROR_TEXT_INVALID")
    }
    val membership = parameter.key?.let { messageKeyDefect(path, it, element) }
        ?: if (parameter.key == null && path !in entitiesReadBy(element)) MessageCompletion.Refused("INVALID_FIELD")
        else MessageCompletion.Accepted
    if (membership != MessageCompletion.Accepted) return membership
    val code = when (parameter.suffix) {
        MessageSuffix.VALUE -> messageValueDefect(path, element)
        MessageSuffix.CATEGORY -> messageCategoryDefect(path, parameter.category)
        else -> null
    }
    return code?.let { MessageCompletion.Refused(it) } ?: MessageCompletion.Accepted
}

/**
 * Message membership includes fields reached through group reads and the computation's own target.
 *
 * A keyed read is no member of an unkeyed parameter, a keyed group read none of its fields: each keyed occurrence is
 * taken out once, so the same path read plainly elsewhere still counts (KERNEL-FINDINGS KF562). The star gate reads
 * [messageConditionReferences] whole, because a keyed read does clear it.
 */
internal fun TrackedFactBase.entitiesReadBy(element: TrackedDeclaration): Membership {
    val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
    val read = messageConditionReferences(element).mapNotNull { written ->
        resolvedPathOf(written, group, element)
    }.toMutableList()
    keyedReadPaths(element, group).forEach { read.remove(it) }
    val paths = read.flatMap { messageReferenceFootprint(it) }.toSet() +
        listOfNotNull(element.declaredReference()?.takeIf { it.role == ReferenceRole.COMPUTED_TARGET }
            ?.target?.let { (it as? Reference.Resolved)?.path })
    return Membership(paths, if (element.kind == "Rule") "errorCondition" else "computation's own members")
}

/** The value path of every keyed read in the declaration's conditions, once per occurrence. */
private fun TrackedFactBase.keyedReadPaths(element: TrackedDeclaration, group: String): List<String> {
    val body = element.body ?: return emptyList()
    val keyword = CONDITION_OPERATOR_KEYWORDS[conditionLanguage]?.get(SEMANTIC_INDEX) ?: return emptyList()
    return conditionTexts(element.kind, body).flatMap { (_, text) -> semanticIndexUsesIn(text, conditionLanguage, keyword) }
        .mapNotNull { use -> use.valuePath?.let { resolvedPathOf(it, group, element) } }
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
