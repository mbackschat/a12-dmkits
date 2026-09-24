package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.SEMANTIC_INDEX
import io.github.mbackschat.a12.dm.consistency.syntax.SemanticIndexUse
import io.github.mbackschat.a12.dm.consistency.syntax.messageParameterBodies
import io.github.mbackschat.a12.dm.consistency.syntax.semanticIndexUsesIn

/**
 * The semantic index an error-message parameter carries, against the one its own CONDITION wrote.
 *
 * A12 resolves a message parameter against the rule's condition rather than against the model:
 * `FullChecks_ErrorTextParameterParser.checkFieldReferencedInConditionAndGetFirstId` asks
 * `refEntities.tryGetFor(fullName, semanticIndexSpec)` and, finding nothing, answers
 * `MVK_INDEX_FOR_ERROR_TEXT_INVALID` when a key was written — where the same unresolvable parameter WITHOUT
 * a key draws `INVALID_FIELD`, which [messageParameterFindings] already owns
 * ([KERNEL-FINDINGS KF412], `rulekit.validate.laws.ErrorTextSemanticIndexLawsTest`).
 *
 * **The key must match, and matching is by SPEC and not by repetition.** A condition reading
 * `[Shipments/Weight For "T-1"]` admits the parameter `$Shipments/Weight For "T-1"$` and refuses
 * `$… For "T-9"$` and `$… For LookupCode$` — a literal and a field key are different specs even where they
 * could name the same value, which is what `tryGetFor` keys on.
 *
 * **One field rides along with the key, and exactly one: the indexed group's own INDEX FIELD.** That is the
 * discriminating pair of the grid and the row a reader would get wrong: `$Shipments/TrackingNo For "T-1"$` is
 * ADMITTED on a condition that never names `TrackingNo`, while `$Shipments/Carrier For "T-1"$` — a plain
 * sibling in the same group at the same key — is REFUSED. So the key does not bind the whole repetition for
 * a message to read from; it binds the pair the condition wrote, plus the field that names the key itself.
 *
 * **A key that is itself keyed is a separate class, and it answers FIRST.** `$Shipments/Weight For
 * Shipments/TrackingNo For "T-1"$` draws `ILLEGAL_SEMANTIC_INDEX`, measured both on a field the condition
 * reads and on one it does not — so this shape is reported without establishing membership, because A12's
 * `checkSemanticIndex` runs as the KEY sub-rule completes and `checkFieldRef` never reaches the condition.
 *
 * **Silent wherever the parameter is not this form**, which is the family's boundary rather than a choice:
 * the message grammar carries a category suffix, a row specifier and a base-year terminal, each with its own
 * gate, and [messageParameterBodies] hands them over unrecognised. Silent too on a text A12 refuses for its
 * CHARACTERS and on an unterminated one, both of which end the parse before a parameter is read.
 */
internal fun TrackedFactBase.errorTextSemanticIndexFindings(): List<Finding> {
    val keyword = CONDITION_OPERATOR_KEYWORDS[conditionLanguage]?.get(SEMANTIC_INDEX) ?: return emptyList()
    return declarations().flatMap { element ->
        val body = element.body ?: return@flatMap emptyList()
        val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
        val messages = body.authoredMessages() as? AuthoredMessages.Read ?: return@flatMap emptyList()
        val written = conditionTexts(element.kind, body)
            .flatMap { (_, text) -> semanticIndexUsesIn(text, conditionLanguage, keyword) }
            .mapNotNull { use -> boundPair(use, group, element) }
            .toSet()
        messages.entries.flatMap { message ->
            if (message.text.carriesAnOffendingCharacter()) {
                return@flatMap emptyList()
            }
            messageParameterBodies(message.text).mapNotNull { parameter ->
                // Only the bare indexed form is this family's. A parameter also carrying a category arrow
                // or the `index(…)` terminal is a different form with its own gate family, and reducing it
                // here would name this family's class for a defect A12 answers elsewhere.
                if (parameter.contains("->") || parameter.trimStart().startsWith("index(")) {
                    return@mapNotNull null
                }
                val uses = semanticIndexUsesIn(parameter, conditionLanguage, keyword)
                // A key that is itself keyed is refused outright, and BEFORE membership: A12's check runs as
                // the key SUB-RULE completes, so `checkFieldRef` never reaches the condition. Measured on a
                // field the condition reads and on one it does not, both `ILLEGAL_SEMANTIC_INDEX`.
                if (uses.any { it.nested }) {
                    return@mapNotNull nestedFinding(element)
                }
                val use = uses.singleOrNull() ?: return@mapNotNull null
                val asked = boundPair(use, group, element) ?: return@mapNotNull null
                if (asked in written || ridesAlong(asked, written)) {
                    return@mapNotNull null
                }
                finding(asked, keyword, element)
            }
        }
    }
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

private fun nestedFinding(element: TrackedDeclaration): Finding = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    "ILLEGAL_SEMANTIC_INDEX",
    "in `errorMessage`: a parameter's semantic-index key is itself keyed, and A12 takes one key per " +
        "parameter — one index selects one repetition, and they do not chain",
    element.path,
)

private fun finding(asked: BoundPair, keyword: String, element: TrackedDeclaration): Finding = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    "MVK_INDEX_FOR_ERROR_TEXT_INVALID",
    "in `errorMessage`: the parameter reads `${asked.valuePath}` `$keyword` `${asked.keySpec}`, a pairing " +
        "this declaration's condition never writes — A12 resolves a message parameter against the " +
        "condition, so read the field with that same key in the condition or drop the key",
    element.path,
)
