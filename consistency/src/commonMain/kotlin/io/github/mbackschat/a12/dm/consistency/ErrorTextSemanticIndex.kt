package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.SEMANTIC_INDEX
import io.github.mbackschat.a12.dm.consistency.syntax.SemanticIndexUse
import io.github.mbackschat.a12.dm.consistency.syntax.semanticIndexUsesIn
import io.github.mbackschat.a12.dm.consistency.syntax.MessageKey
import io.github.mbackschat.a12.dm.consistency.syntax.MessageCompletion

/** A key binds the condition's field/key pair and its index field, not every sibling (KF412). */
internal fun TrackedFactBase.messageKeyDefect(path: String, key: MessageKey, element: TrackedDeclaration): MessageCompletion {
    if (key.entity?.ruleGroup == true) return MessageCompletion.Refused("MVK_INTERNAL_ERROR")
    val keyword = CONDITION_OPERATOR_KEYWORDS[conditionLanguage]?.get(SEMANTIC_INDEX) ?: return MessageCompletion.Declined
    val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
    val spec = key.literal?.let { "\"$it\"" } ?: key.entity?.let {
        (resolveMessageEntity(group, it.written.replace("'", ""), element.origin) as? Reference.Resolved)?.path
    } ?: return MessageCompletion.Declined
    val written = element.body?.let { conditionTexts(element.kind, it) }.orEmpty()
        .flatMap { (_, text) -> semanticIndexUsesIn(text, conditionLanguage, keyword) }
        .mapNotNull { boundPair(it, group, element) }.toSet()
    val asked = BoundPair(path, spec)
    return if (asked in written || ridesAlong(asked, written)) MessageCompletion.Accepted
        else MessageCompletion.Refused("MVK_INDEX_FOR_ERROR_TEXT_INVALID")
}

/** An indexed read reduced to what `tryGetFor` keys on: the resolved value path and the key's SPEC. */
private data class BoundPair(val valuePath: String, val keySpec: String)

/**
 * The pair this use asks for, or null where either half is a shape this family may not reduce.
 *
 * A NESTED index, a category-projected key and a key this reader cannot resolve all return null, because
 * each carries a structural class of its own that answers before this membership question is asked.
 */
private fun TrackedFactBase.boundPair(
    use: SemanticIndexUse,
    declaringGroup: String,
    element: TrackedDeclaration,
): BoundPair? {
    if (use.nested || use.keyCategory != null) {
        return null
    }
    val valuePath = use.valuePath?.let { resolvedPath(it, declaringGroup, element) } ?: return null
    val keySpec = use.keyLiteral?.let { literal -> "\"$literal\"" }
        ?: use.keyPath?.let { path -> resolvedPath(path, declaringGroup, element) }
        ?: return null
    return BoundPair(valuePath, keySpec)
}

/**
 * Whether this pair is the INDEX FIELD of a group the condition already bound at the same key.
 *
 * Measured, and not derivable from the membership rule: A12 lets the field that NAMES the key be read for
 * that key without the condition referencing it, while every other field of the same group at the same key
 * is refused.
 */
private fun TrackedFactBase.ridesAlong(asked: BoundPair, written: Set<BoundPair>): Boolean {
    val groupPath = asked.valuePath.substringBeforeLast('/', missingDelimiterValue = "")
    val boundAtTheSameKey = written.any { bound ->
        bound.keySpec == asked.keySpec &&
            bound.valuePath.substringBeforeLast('/', missingDelimiterValue = "") == groupPath
    }
    if (!boundAtTheSameKey) {
        return false
    }
    val group = declarationAt(groupPath) ?: return false
    return asked.valuePath == "$groupPath/${indexFieldNameOf(group) ?: return false}"
}

/**
 * The model path this written reference names, or null when it does not resolve.
 *
 * The same resolver both sides use, which is what makes the two halves comparable: a path is multi-segment
 * on either surface (`Shipments/Weight`), and reducing one of them with a bare-name reader would leave every
 * nested read unjudged. An address that does not resolve is a class the reference families already own.
 */
private fun TrackedFactBase.resolvedPath(
    written: String,
    declaringGroup: String,
    element: TrackedDeclaration,
): String? = (resolveEntity(declaringGroup, written, element.origin) as? Reference.Resolved)?.path
