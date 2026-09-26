package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.CallOperand
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
 * class — measured, after the opposite was predicted. A starred scope draws `MVK_NO_WILDCARDS_ALLOWED` wherever
 * the star reads at all: on a group that does not repeat the read answers `MVK_INVALID_WILDCARD`, on an empty scope
 * its emptiness, and beside an index `MVK_SEMANTIC_INDEX_AND_WILDCARD`, so no row orders the star against the index
 * gate and the order here is the check source's. A scope selecting one repetition by a semantic index that
 * reads draws `MVK_SEMANTIC_INDEX_NOT_ALLOWED`, and one whose index does not read draws the index's own
 * class, raised while the scope is read and so ahead of every gate of the construct
 * (`rulekit.validate.laws.RepetitionNotUniquePairedGateLawsTest`). "Not a repeatable group" is true of every field, so a
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
                    call.operands.mapNotNull { operand -> scopeTextOf(operand, scopeKeyword)?.let { it to call } }
                }
                .distinctBy { (scope, _) -> scope.text }
                // The operator's own check refuses the scope, so the class is raised at the call's `)`.
                .mapNotNull { (scope, call) ->
                    // Read as a key is, so an index keeps its key and a clean one reaches the index gate.
                    val read = categorySpecIn(scope.text, declaringGroup, element) { scope.bare }
                        ?: return@mapNotNull null
                    scopeFinding(read, declaringGroup, element, member, call.spanIn(member))
                }
        }
    }
}

/** A `@From` scope's text, and the reference it names when it is a bare path. */
internal data class ScopeText(val text: String, val bare: String?)

/**
 * [operand]'s `@From` scope, or null without one.
 *
 * The scope is the last clause the grammar allows, so it runs to the operand's end: a `For` after it is the
 * scope's own index, which the clause reader cuts as a clause of its own because `For` is a path operator too.
 */
internal fun scopeTextOf(operand: CallOperand, scopeKeyword: String): ScopeText? {
    val clauses = operand.clauses.dropWhile { it.keyword != scopeKeyword }
    val scope = clauses.firstOrNull() ?: return null
    return ScopeText(operand.text.substring(scope.at - operand.at).trim(), scope.path.takeIf { clauses.size == 1 })
}

/**
 * The finding one scope draws, or null for every shape A12 answers with another class.
 *
 * Silent on a scope the reference reader answers for — one that does not RESOLVE (`MVK_INVALID_ENTITY`) or
 * crosses a repetition nothing addresses, or whose index does not read — on one carrying a category, which no
 * row measures, and on one that repeats, where every remaining defect is about containment:
 * [repetitionKeyContainmentFindings]', which models the joint iteration those classes turn on.
 */
private fun TrackedFactBase.scopeFinding(
    scope: OperandReference,
    declaringGroup: String,
    element: TrackedDeclaration,
    member: String,
    span: TextSpan,
): Finding? {
    if (scope.category != null) {
        return null
    }
    val target = addressedTargetOf(
        scope.reference, declaringGroup, element, scope.indexSelected, everyLevelAddressed = false,
    ) ?: return null
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
    if ('*' in scope.reference) {
        return Finding(
            FindingCategory.A12_CONSTRAINT,
            FindingSeverity.ERROR,
            "MVK_NO_WILDCARDS_ALLOWED",
            "this $member scopes `$REPETITION_NOT_UNIQUE` to `${scope.reference}`, which carries a repetition star " +
                "— the scope names the whole repeatable group whose rows are compared, so drop the star",
            element.path,
            span,
        )
    }
    if (scope.indexSelected) {
        return Finding(
            FindingCategory.A12_CONSTRAINT,
            FindingSeverity.ERROR,
            "MVK_SEMANTIC_INDEX_NOT_ALLOWED",
            "this $member scopes `$REPETITION_NOT_UNIQUE` to one repetition of `${declared.path}`, selected by " +
                "its semantic index — the scope names the whole repetition whose rows are compared, so drop the key",
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
