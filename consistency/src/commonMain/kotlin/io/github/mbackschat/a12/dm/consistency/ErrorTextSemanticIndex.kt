package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.SEMANTIC_INDEX
import io.github.mbackschat.a12.dm.consistency.syntax.SemanticIndexUse
import io.github.mbackschat.a12.dm.consistency.syntax.semanticIndexUsesIn
import io.github.mbackschat.a12.dm.consistency.syntax.MessageKey
import io.github.mbackschat.a12.dm.consistency.syntax.MessageCompletion
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionRecognizer
import io.github.mbackschat.a12.dm.consistency.syntax.SyntaxEntry

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
    return if (asked in written || ridesAlong(asked, written) || underKeyedGroup(asked, written)) MessageCompletion.Accepted
        else MessageCompletion.Refused("MVK_INDEX_FOR_ERROR_TEXT_INVALID")
}

/** Matching a keyed group is membership; firing its row token separately requires positive presence. */
internal fun TrackedFactBase.keyedMessageGroupDefect(
    named: String,
    key: MessageKey,
    element: TrackedDeclaration,
): MessageCompletion {
    if (element.kind != "Rule") return MessageCompletion.Declined
    val text = element.body?.string("errorCondition") ?: return MessageCompletion.Declined
    val keyword = CONDITION_OPERATOR_KEYWORDS[conditionLanguage]?.get(SEMANTIC_INDEX)
        ?: return MessageCompletion.Declined
    val uses = semanticIndexUsesIn(text, conditionLanguage, keyword)
    if (uses.isEmpty()) return MessageCompletion.Refused("INVALID_GROUP")
    val group = element.path.substringBeforeLast('/')
    val spec = key.literal?.let { "\"$it\"" } ?: key.entity?.let {
        (resolveMessageEntity(group, it.written.replace("'", ""), element.origin) as? Reference.Resolved)?.path
    } ?: return MessageCompletion.Declined
    fun matches(pair: BoundPair) = pair.keySpec == spec &&
        (pair.valuePath == named || pair.valuePath.startsWith("$named/"))
    val pairs = uses.map { boundPair(it, group, element) ?: return MessageCompletion.Declined }
    if (pairs.none(::matches)) return MessageCompletion.Refused("INVALID_GROUP")
    val positive = setOf("FieldFilled", "GroupFilled", "AllFieldsFilled", "AllGroupsFilled")
        .mapNotNull { CONDITION_OPERATOR_KEYWORDS[conditionLanguage]?.get(it) }.toSet()
    val syntax = ConditionRecognizer(text, conditionLanguage) { call ->
        call.operator in positive && call.operands.any { tokens ->
            val operand = call.source.substring(tokens.first().start, tokens.last().end)
            semanticIndexUsesIn(operand, conditionLanguage, keyword).any { use ->
                boundPair(use, group, element)?.let(::matches) == true
            }
        }
    }.recognize(SyntaxEntry.CONDITION)
    if (syntax.failure != null) return MessageCompletion.Declined
    return if (syntax.conditionProof == true) MessageCompletion.Accepted
        else MessageCompletion.Refused("INVALID_USE_OF_SEMANTIC_INDEX")
}

/** An indexed read reduced to what `tryGetFor` keys on: the resolved value path and the key's SPEC. */
internal data class BoundPair(val valuePath: String, val keySpec: String)

/**
 * The pair this use asks for, or null where either half is a shape this family may not reduce.
 *
 * A NESTED index, a category-projected key and a key this reader cannot resolve all return null, because
 * each carries a structural class of its own that answers before this membership question is asked.
 */
internal fun TrackedFactBase.boundPair(
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
 * Whether the condition reads a GROUP above this field at the same spelled key. A keyed group read admits a keyed
 * parameter on each of its fields (KERNEL-FINDINGS KF562), a field below a deeper repeatable group included; that
 * field's value suffix then meets the star gate, since nothing binds the deeper group.
 */
private fun TrackedFactBase.underKeyedGroup(asked: BoundPair, written: Set<BoundPair>): Boolean = written.any { bound ->
    bound.keySpec == asked.keySpec && asked.valuePath.startsWith("${bound.valuePath}/") &&
        declarationAt(bound.valuePath)?.declaredKind == DeclaredKind.GROUP
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
