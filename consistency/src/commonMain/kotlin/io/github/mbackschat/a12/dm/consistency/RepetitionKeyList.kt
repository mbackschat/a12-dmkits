package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.CallOperand
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionCall
import io.github.mbackschat.a12.dm.consistency.syntax.FROM_SCOPE
import io.github.mbackschat.a12.dm.consistency.syntax.REPETITION_NOT_UNIQUE
import io.github.mbackschat.a12.dm.consistency.syntax.callsIn

/**
 * What a `RepetitionNotUnique` KEY LIST has to hold before either branch of the operator's check runs.
 *
 * `CheckWiederholungBedingungImpl.check` builds the list, asks its category gate, and then four gates of its
 * own, each over the WHOLE list before the next begins ([KERNEL-FINDINGS KF355],
 * `adapter.laws.CheckerRepetitionNotUniqueGateAgreementTest`):
 *
 * | the gate, in order | class |
 * |---|---|
 * | a key names a group | `MVK_NO_GROUPS_ALLOWED` |
 * | a key carries a repetition star | `MVK_NO_WILDCARDS_ALLOWED` |
 * | a key carries a semantic index | `MVK_SEMANTIC_INDEX_NOT_ALLOWED` |
 * | a key waives value validation | `MVK_INVALID_RAW_TYPE` |
 *
 * **Gate by gate, not key by key, and the difference is measured.** A list holding a starred field and then
 * a group is `MVK_NO_GROUPS_ALLOWED`: the group gate reads the second key before the wildcard gate reads the
 * first. A reader walking each key through all four gates names the star.
 *
 * **Every class here answers ahead of both branches** — the `@From` scope's gates and the placement gates
 * alike — so [repetitionKeyListIsSound] is how [repetitionScopeGroupFindings],
 * [repetitionKeyContainmentFindings] and [repetitionKeyPlacementFindings] know they may speak at all.
 *
 * **Silent wherever something earlier answers**, and each silence is another family's class rather than a
 * gap: a key the reference reader does not address (resolved first, [KERNEL-FINDINGS KF356]), a duplicate
 * (`MVK_DUPLICATE_PARAM2` beside the group that holds a starred key, measured), and a category, whose gate
 * runs once the list is built ([entityListCategoryFindings]). A ROOT group is no silence: this carrier asks no
 * root-group gate, so a sole root key meets the group gate like any other group.
 *
 * **Rules only**: A12's calculation module refuses the operator in a computation outright
 * ([KERNEL-FINDINGS KF333]), so a computation carrying one has a different defect.
 */
internal fun TrackedFactBase.repetitionKeyListFindings(): List<Finding> {
    val spelling = CONDITION_OPERATOR_KEYWORDS[conditionLanguage]?.get(REPETITION_NOT_UNIQUE) ?: return emptyList()
    return declarations().filter { it.kind == "Rule" }.flatMap { element ->
        val body = element.body ?: return@flatMap emptyList()
        val declaringGroup = element.path.substringBeforeLast('/', missingDelimiterValue = "")
        conditionTexts(element.kind, body).flatMap { (member, text) ->
            callsIn(text, conditionLanguage, syntaxEntryOf(member))
                .filter { it.operator == spelling }
                .mapNotNull { call ->
                    (keyListOf(call, declaringGroup, element) as? KeyList.Defective)
                        ?.let { finding(it, call, element, member) }
                }
        }
    }
}

/**
 * Whether [call]'s key list reaches the branch the `@From` clause selects: every key reads, no list gate
 * answers, and none of the four key gates does.
 */
internal fun TrackedFactBase.repetitionKeyListIsSound(
    call: ConditionCall,
    declaringGroup: String,
    element: TrackedDeclaration,
): Boolean = keyListOf(call, declaringGroup, element) == KeyList.Sound

private sealed interface KeyList {
    /** A gate ahead of the key gates answers — the reference reader's, a duplicate's, the category's. */
    data object AnsweredEarlier : KeyList

    class Defective(val code: String, val key: String, val because: String) : KeyList

    data object Sound : KeyList
}

private fun TrackedFactBase.keyListOf(
    call: ConditionCall,
    declaringGroup: String,
    element: TrackedDeclaration,
): KeyList {
    // A12 refuses the construct itself inside a filter body, so its key list is never read there.
    if (call.operands.isEmpty() || call.inAFilterBody) {
        return KeyList.AnsweredEarlier
    }
    val scopeKeyword = CONDITION_OPERATOR_KEYWORDS[conditionLanguage]?.get(FROM_SCOPE)
    val reads = call.operands.map { operand ->
        // The operand's own path keeps a key's star, which the text reader's reduction drops; a clause ahead of
        // the scope leaves no bare path, and the reader then takes a keyed or categorized text itself. The
        // clauses after the scope are its own, such as its index.
        val bare = operand.base.takeIf { operand.clauses.firstOrNull()?.let { it.keyword == scopeKeyword } ?: true }
        categorySpecIn(keyTextOf(operand, scopeKeyword), declaringGroup, element) { bare }
            ?: return KeyList.AnsweredEarlier
    }
    val targets = reads.map {
        addressedTargetOf(it.reference, declaringGroup, element, it.indexSelected, everyLevelAddressed = false)
            ?: return KeyList.AnsweredEarlier
    }
    if (entityListDuplicateAnswers(reads, targets) || reads.any { it.category != null }) {
        return KeyList.AnsweredEarlier
    }
    val keys = targets.map { declarationAt(it) ?: return KeyList.AnsweredEarlier }
    keys.firstOrNull { it.declaredKind != DeclaredKind.FIELD }?.let {
        return KeyList.Defective(
            "MVK_NO_GROUPS_ALLOWED", it.path,
            "is a group — a key is one field whose values are compared across the repetitions",
        )
    }
    reads.firstOrNull { '*' in it.reference }?.let {
        return KeyList.Defective(
            "MVK_NO_WILDCARDS_ALLOWED", it.reference,
            "carries a repetition star — the operator iterates the repetition itself, so name the key unstarred",
        )
    }
    reads.firstOrNull { it.indexSelected }?.let {
        return KeyList.Defective(
            "MVK_SEMANTIC_INDEX_NOT_ALLOWED", it.reference,
            "selects one repetition by its semantic index — the operator compares every repetition, so drop the key",
        )
    }
    keys.firstOrNull { it.waivesValueValidation() }?.let {
        return KeyList.Defective(
            "MVK_INVALID_RAW_TYPE", it.path,
            "waives value validation, so A12 holds its content unparsed and has no value to compare",
        )
    }
    return KeyList.Sound
}

/**
 * [operand]'s text without the `@From` clause, which the grammar hangs on the CALL after the list — the
 * reader attaches it to the last key, and reading it as part of that key would make every scoped list
 * unreadable.
 */
private fun keyTextOf(operand: CallOperand, scopeKeyword: String?): String {
    val scope = operand.clauses.firstOrNull { it.keyword == scopeKeyword } ?: return operand.text
    val keywordAt = operand.text.lastIndexOf(scope.keyword, scope.at - operand.at - 1)
    return operand.text.substring(0, keywordAt).trimEnd()
}

private fun finding(
    defect: KeyList.Defective,
    call: ConditionCall,
    element: TrackedDeclaration,
    member: String,
): Finding = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    defect.code,
    "in `$member`: `${call.operator}` takes `${defect.key}` as a key, which ${defect.because}",
    element.path,
    call.spanIn(member),
)
