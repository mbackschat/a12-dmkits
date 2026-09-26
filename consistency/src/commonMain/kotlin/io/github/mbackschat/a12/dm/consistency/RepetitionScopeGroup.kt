package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.FROM_SCOPE
import io.github.mbackschat.a12.dm.consistency.syntax.REPETITION_NOT_UNIQUE
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
 * **The KIND gate comes first**, so a scope naming a FIELD draws `MVK_NO_FIELDS_ALLOWED` rather than this
 * class — measured, after the opposite was predicted. "Not a repeatable group" is true of every field, so a
 * family testing repeatability alone would name the wrong defect on each of them; this one asks both gates,
 * in that order.
 *
 * **Behind the key list**: every key-list class answers ahead of the scope's gates ([KERNEL-FINDINGS KF355],
 * `adapter.laws.CheckerRepetitionNotUniqueGateAgreementTest`), so a list [repetitionKeyListIsSound] does not
 * pass leaves the scope unjudged.
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
internal fun TrackedFactBase.repetitionScopeGroupFindings(): List<Finding> {
    val keywords = CONDITION_OPERATOR_KEYWORDS[conditionLanguage] ?: return emptyList()
    val spelling = keywords[REPETITION_NOT_UNIQUE] ?: return emptyList()
    // Resolved by CATALOG ID rather than by a literal or by "the PATH_OP that starts with @", because the
    // German spelling is a different word and the clause set will grow: `For`/`Fuer` is a PATH_OP too and
    // means something else entirely.
    val scopeKeyword = keywords[FROM_SCOPE] ?: return emptyList()
    return declarations().filter { it.kind == "Rule" }.flatMap { element ->
        val body = element.body ?: return@flatMap emptyList()
        val declaringGroup = element.path.substringBeforeLast('/', missingDelimiterValue = "")
        conditionTexts(element.kind, body).flatMap { (member, text) ->
            callsIn(text, conditionLanguage, syntaxEntryOf(member))
                .filter { it.operator == spelling && repetitionKeyListIsSound(it, declaringGroup, element) }
                .flatMap { call ->
                    call.operands.mapNotNull { operand ->
                        operand.clauses.firstOrNull { it.keyword == scopeKeyword }?.path?.let { it to call }
                    }
                }
                .distinctBy { (scope, _) -> scope }
                // The operator's own check refuses the scope, so the class is raised at the call's `)`.
                .mapNotNull { (scope, call) ->
                    scopeFinding(scope, declaringGroup, element, member, call.spanIn(member))
                }
        }
    }
}

/**
 * The finding one scope draws, or null for every shape A12 answers with another class.
 *
 * Silent on a scope the reference reader answers for — one that does not RESOLVE (`MVK_INVALID_ENTITY`) or
 * crosses a repetition nothing addresses — and on one that repeats, where every remaining defect is about
 * containment: [repetitionKeyContainmentFindings]', which models the joint iteration those classes turn on.
 */
private fun TrackedFactBase.scopeFinding(
    scope: String,
    declaringGroup: String,
    element: TrackedDeclaration,
    member: String,
    span: TextSpan,
): Finding? {
    val target = addressedTargetOf(scope, declaringGroup, element, indexSelected = false, everyLevelAddressed = false)
        ?: return null
    val declared = declarationAt(target) ?: return null
    if (declared.declaredKind == DeclaredKind.FIELD) {
        return Finding(
            FindingCategory.A12_CONSTRAINT,
            FindingSeverity.ERROR,
            "MVK_NO_FIELDS_ALLOWED",
            "this $member scopes `$REPETITION_NOT_UNIQUE` to `${declared.path}`, which is a field — the scope " +
                "names the repeatable group whose repetitions are compared",
            element.path,
            span,
        )
    }
    if (declared.isRepeatable) {
        return null
    }
    return Finding(
        FindingCategory.A12_CONSTRAINT,
        FindingSeverity.ERROR,
        "MVK_GROUP_NOT_REPEATABLE",
        "this $member scopes `$REPETITION_NOT_UNIQUE` to `${declared.path}`, which does not repeat — the " +
            "operator compares one repetition of the scope against another, so the scope has to be a " +
            "repeatable group",
        element.path,
        span,
    )
}
