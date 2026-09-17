package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionCall
import io.github.mbackschat.a12.dm.consistency.syntax.callsIn

/**
 * Where a `RepetitionNotUnique` KEY has to sit relative to the scope that bounds it.
 *
 * `CheckWiederholungBedingungImpl.check` has two arms and the scope decides which: with an `@From` clause
 * every key's parent path must start with the scope's, and without one the keys' repeatable groups must lie
 * on a single hierarchy path and every key must sit inside the rule's own group
 * ([KERNEL-FINDINGS KF375], `rulekit.validate.laws.RepetitionNotUniqueFromScopeLawsTest`,
 * `…RepetitionNotUniqueKeyPathLawsTest`).
 *
 * | the arm, and what the key does | class |
 * |---|---|
 * | `@From`: the key sits outside the scope | `MVK_NOT_IN_GROUP` |
 * | no `@From`: two keys' repetitions lie on parallel branches | `MVK_NOT_IN_SAME_PATH` |
 * | no `@From`: a key sits outside the rule's own group | `MVK_NOT_IN_RULEGROUP` |
 *
 * **All three are barely reachable, and what makes them reachable is INDEX-KEYING rather than containment.**
 * Two repeatable groups on parallel branches iterate jointly only when they declare the same index field;
 * without that A12 refuses the pairing at the wildcard gate and draws `MVK_NO_WILDCARD` long before it asks
 * where anything sits. So every parallel shape here is judged only on an index-keyed pair, and the
 * index-free one is left to [operandRepetitionScopeFindings], which owns that class. The `@From` arm carries
 * the same condition against the SCOPE rather than against another key, which is why a key with no
 * repetition at all — above the scope entirely — is silent here and answered there.
 *
 * **A key with no repeatable group of its own is still judged against the rule group**, and that is the one
 * shape the joint-iteration rule must not swallow: a second key in a NON-repeatable group crosses no
 * repetition, so no wildcard gate has anything to say and `MVK_NOT_IN_RULEGROUP` is what A12 draws.
 *
 * **Silent once the rule group CONTAINS every key**, where [repetitionKeyPlacementFindings] takes over with
 * `MVK_REPEATABLE_GROUP_MISSING`: the two families partition the no-`@From` arm at exactly the gate A12
 * partitions it, containment first and the repetition demand after.
 *
 * **Silent on every scope defect**, which A12 gates ahead of containment: a scope naming a field draws
 * `MVK_NO_FIELDS_ALLOWED` and a non-repeatable one `MVK_GROUP_NOT_REPEATABLE`, both
 * [repetitionScopeGroupFindings]'.
 *
 * **Rules only**, for the reason its two siblings are: A12's calculation module refuses the operator in a
 * computation outright ([KERNEL-FINDINGS KF333]), so a computation carrying one has a different defect.
 */
internal fun TrackedFactBase.repetitionKeyContainmentFindings(): List<Finding> {
    val keywords = CONDITION_OPERATOR_KEYWORDS[conditionLanguage] ?: return emptyList()
    val spelling = keywords[REPETITION_NOT_UNIQUE] ?: return emptyList()
    val scopeKeyword = keywords[FROM_SCOPE] ?: return emptyList()
    return declarations().filter { it.kind == "Rule" }.flatMap { element ->
        val body = element.body ?: return@flatMap emptyList()
        val declaringGroup = element.path.substringBeforeLast('/', missingDelimiterValue = "")
        conditionTexts(element.kind, body).flatMap { (member, text) ->
            callsIn(text, conditionLanguage, syntaxEntryOf(member))
                .filter { it.operator == spelling }
                .mapNotNull { call ->
                    containmentDefect(call, scopeKeyword, declaringGroup, element)
                        ?.let { containmentFinding(it, call, element, member) }
                }
        }
    }
}

private const val REPETITION_NOT_UNIQUE = "RepetitionNotUnique"

private const val FROM_SCOPE = "FromScope"

private data class ContainmentDefect(val code: String, val key: String, val bound: String)

/**
 * The first containment gate this call fails, in A12's own order.
 *
 * The key list is taken whole or not at all: a shape this reader cannot reduce to a resolved path is one A12
 * answers with its own class, and guessing which would attach a containment fix to a different defect.
 */
private fun TrackedFactBase.containmentDefect(
    call: ConditionCall,
    scopeKeyword: String,
    declaringGroup: String,
    element: TrackedDeclaration,
): ContainmentDefect? {
    if (call.operands.isEmpty()) {
        return null
    }
    val scopeSpelling = call.operands.firstNotNullOfOrNull { operand ->
        operand.clauses.firstOrNull { it.keyword == scopeKeyword }?.path
    }
    val written = call.operands.map { it.base ?: return null }
    if (written.any { it.contains('*') }) {
        return null
    }
    val keys = written.mapNotNull {
        (resolveEntity(declaringGroup, it, element.origin) as? Reference.Resolved)?.path
    }
    if (keys.size != written.size) {
        return null
    }
    val scope = scopeSpelling?.let {
        repeatableGroupAt((resolveEntity(declaringGroup, it, element.origin) as? Reference.Resolved)?.path)
            ?: return null
    }
    return if (scope != null) outsideScope(keys, scope) else withoutScope(keys, declaringGroup)
}

/**
 * The `@From` arm: every key's parent has to sit under the scope.
 *
 * A key outside it is only ASKED where the two branches iterate jointly; otherwise the wildcard gate has
 * already answered, which is the measured difference between an index-keyed pair and an index-free one.
 */
private fun TrackedFactBase.outsideScope(keys: List<String>, scope: TrackedDeclaration): ContainmentDefect? {
    val outside = keys.firstOrNull { !parentOf(it).startsWith("${scope.path}/") } ?: return null
    return if (iteratesJointlyWith(lowestRepetitionOf(outside), scope)) {
        ContainmentDefect("MVK_NOT_IN_GROUP", outside, scope.path)
    } else {
        null
    }
}

/**
 * The no-`@From` arm, in source order: the repetitions must chain, then every key must sit in the rule group.
 *
 * The chain test walks the keys in authored order and keeps the longest path seen, exactly as A12 does, so
 * the key REPORTED is the first one that cannot extend it rather than the pair that disagrees.
 */
private fun TrackedFactBase.withoutScope(keys: List<String>, declaringGroup: String): ContainmentDefect? {
    var chain: TrackedDeclaration? = null
    keys.forEach { key ->
        val repetition = lowestRepetitionOf(key) ?: return@forEach
        val held = chain
        if (held == null) {
            chain = repetition
            return@forEach
        }
        val nested = repetition.path.startsWith("${held.path}/") || held.path.startsWith("${repetition.path}/")
        if (!nested && repetition.path != held.path) {
            return if (iteratesJointlyWith(repetition, held)) {
                ContainmentDefect("MVK_NOT_IN_SAME_PATH", key, held.path)
            } else {
                null
            }
        }
        if (repetition.path.length > held.path.length) {
            chain = repetition
        }
    }
    val outside = keys.firstOrNull { !parentOf(it).startsWith("$declaringGroup/") } ?: return null
    // A key whose own branch repeats is refused at the wildcard gate first, so only one with no repetition
    // of its own — or one sharing an index field with the rule's branch — reaches this class.
    return if (lowestRepetitionOf(outside) == null) {
        ContainmentDefect("MVK_NOT_IN_RULEGROUP", outside, declaringGroup)
    } else {
        null
    }
}

/** The parent path of [path], with the trailing separator A12's own comparison carries. */
private fun parentOf(path: String): String = "${path.substringBeforeLast('/', missingDelimiterValue = "")}/"

/** The innermost repeatable group [path] lies under, which is the level A12 compares. */
private fun TrackedFactBase.lowestRepetitionOf(path: String): TrackedDeclaration? = declarations()
    .filter { it.isRepeatableGroup && path.startsWith("${it.path}/") }
    .maxByOrNull { it.path.length }

private fun TrackedFactBase.repeatableGroupAt(path: String?): TrackedDeclaration? = allDeclarations().firstOrNull {
    it.path == path && it.isRepeatableGroup
}

/**
 * Whether two repeatable groups A12 would otherwise refuse to pair iterate JOINTLY — the same declared index
 * field name on both, which is what makes their repetitions correspond.
 *
 * Absent that, the pair draws `MVK_NO_WILDCARD` before any containment question, so this predicate is the
 * whole reachability of the three classes above rather than a refinement of them.
 */
private fun iteratesJointlyWith(one: TrackedDeclaration?, other: TrackedDeclaration): Boolean {
    val name = indexFieldNameOf(one ?: return false) ?: return false
    return name == indexFieldNameOf(other)
}

private fun containmentFinding(
    defect: ContainmentDefect,
    call: ConditionCall,
    element: TrackedDeclaration,
    member: String,
): Finding = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    defect.code,
    if (defect.code == "MVK_NOT_IN_SAME_PATH") {
        "this $member asks `${call.operator}` about `${defect.key}`, whose repetition lies on a branch " +
            "PARALLEL to `${defect.bound}` — the operator compares repetitions of one branch, so the keys " +
            "have to sit on a single path through the model"
    } else {
        "this $member asks `${call.operator}` about `${defect.key}`, which sits outside `${defect.bound}` " +
            "— every key has to sit inside the group that bounds the comparison"
    },
    element.path,
)
