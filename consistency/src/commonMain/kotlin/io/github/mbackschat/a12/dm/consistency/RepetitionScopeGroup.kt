package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.callsIn

/**
 * What a `RepetitionNotUnique` `@From` scope has to be: a REPEATABLE group.
 *
 * The scope names the repetition the operator scans, so A12 refuses one that does not repeat with
 * `MVK_GROUP_NOT_REPEATABLE` ([KERNEL-FINDINGS KF349],
 * `rulekit.validate.laws.RepetitionNotUniqueFromScopeLawsTest`). Naming the rule's own non-repeatable root
 * is the shape that draws it, which is also the easiest one to author by accident: the scope reads like a
 * containment declaration, so the group a reader reaches for first is the one everything sits under.
 *
 * **The KIND gate comes first, and that is why this family reports on groups alone.** A scope naming a FIELD
 * draws `MVK_NO_FIELDS_ALLOWED`, not this class — measured, after the opposite was predicted. "Not a
 * repeatable group" is true of every field, so a family testing repeatability alone would have named the
 * wrong defect on each of them and handed its author a fix for a different gate.
 *
 * **Silent wherever the scope IS repeatable, whatever else is wrong.** The containment classes A12 draws
 * when a key sits outside a repeatable scope — `MVK_NOT_IN_GROUP`, `MVK_NOT_IN_SAME_PATH`, and the
 * `MVK_NO_WILDCARD` that pre-empts both on an index-free pair — belong to families that do not exist yet,
 * and each is measured to turn on whether the two groups iterate JOINTLY rather than on anything this family
 * can see from the scope alone.
 *
 * **Rules only**, for the same reason [repetitionKeyPlacementFindings] is: A12's calculation module refuses
 * `RepetitionNotUnique` in a computation outright ([KERNEL-FINDINGS KF333]), so a computation carrying one
 * has a different defect entirely.
 */
fun PreparedModel.repetitionScopeGroupFindings(): List<Finding> {
    val keywords = CONDITION_OPERATOR_KEYWORDS[conditionLanguage] ?: return emptyList()
    val spelling = keywords[REPETITION_NOT_UNIQUE] ?: return emptyList()
    // Resolved by CATALOG ID rather than by a literal or by "the PATH_OP that starts with @", because the
    // German spelling is a different word and the clause set will grow: `For`/`Fuer` is a PATH_OP too and
    // means something else entirely.
    val scopeKeyword = keywords[FROM_SCOPE] ?: return emptyList()
    return elements.filter { it.kind == "Rule" }.flatMap { element ->
        val body = element.body ?: return@flatMap emptyList()
        val declaringGroup = element.path.substringBeforeLast('/', missingDelimiterValue = "")
        conditionTexts(element.kind, body).flatMap { (member, text) ->
            callsIn(text, conditionLanguage, syntaxEntryOf(member))
                .filter { it.operator == spelling }
                .flatMap { call -> call.operands }
                .mapNotNull { operand ->
                    operand.clauses.firstOrNull { it.keyword == scopeKeyword }?.path
                }
                .distinct()
                .mapNotNull { scope -> scopeFinding(scope, declaringGroup, element, member) }
        }
    }
}

private const val REPETITION_NOT_UNIQUE = "RepetitionNotUnique"

private const val FROM_SCOPE = "FromScope"

/**
 * The finding one scope draws, or null for every shape A12 answers with another class.
 *
 * Three silences, each resting on a measured row rather than on a reading: a scope that does not RESOLVE
 * (`MVK_INVALID_ENTITY`, the reference family's), a scope naming a FIELD (`MVK_NO_FIELDS_ALLOWED`, gated
 * before repeatability), and a scope that repeats, where every remaining defect is about containment —
 * [repetitionKeyContainmentFindings]', which models the joint iteration those classes turn on.
 */
private fun PreparedModel.scopeFinding(
    scope: String,
    declaringGroup: String,
    element: DeclaredElement,
    member: String,
): Finding? {
    val resolved = resolveEntity(declaringGroup, scope, element.origin) as? Reference.Resolved
        ?: return null
    val group = elements.firstOrNull { it.path == resolved.path && it.declaredKind == DeclaredKind.GROUP }
        ?: return null
    if (group.isRepeatable) {
        return null
    }
    return Finding(
        FindingCategory.A12_CONSTRAINT,
        FindingSeverity.ERROR,
        "MVK_GROUP_NOT_REPEATABLE",
        "this $member scopes `$REPETITION_NOT_UNIQUE` to `${group.path}`, which does not repeat — the " +
            "operator compares one repetition of the scope against another, so the scope has to be a " +
            "repeatable group",
        element.path,
    )
}
