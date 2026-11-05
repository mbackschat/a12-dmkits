package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.ConditionRecognizer
import io.github.mbackschat.a12.dm.consistency.syntax.MessageCompletion
import io.github.mbackschat.a12.dm.consistency.syntax.OPERATOR_SPELLINGS
import io.github.mbackschat.a12.dm.consistency.syntax.OperatorId
import io.github.mbackschat.a12.dm.consistency.syntax.SyntaxEntry
import io.github.mbackschat.a12.dm.consistency.syntax.isRuleContext
import io.github.mbackschat.a12.dm.consistency.syntax.provesRow
import io.github.mbackschat.a12.dm.consistency.syntax.referencesIn
import io.github.mbackschat.a12.dm.consistency.syntax.textOf

/**
 * The levels A12 marks as parallel-iterated in one declaration's conditions, as its message checks see them.
 *
 * The marking is [markedLevelsOf]'s two passes: nothing is marked unless some read iterates a level jointly with
 * the target, and then every read's keyed level on the target's path is marked too. A message reads that marking
 * twice ([KERNEL-FINDINGS KF675], `adapter.laws.CheckerParallelJoinMessageAgreementTest`): each marked level's
 * index field is a member although the condition names it nowhere, and a marked level's row token needs the
 * condition to prove that row.
 *
 * Keyed, filtered-body and `SumOfTerms`-term reads are passed over, since A12 marks only an iterated read. Null
 * where this reader has no verdict: an outer marker, a condition that does not parse, or a target it cannot
 * anchor. A read whose scope chain A12 refuses during the parse contributes no level.
 */
internal fun TrackedFactBase.parallelMarkingOf(element: TrackedDeclaration): Set<String>? {
    val body = element.body ?: return null
    val target = (element.declaredReference()?.target as? Reference.Resolved)?.path ?: return null
    val anchor = anchorOf(target) ?: return null
    val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
    val marks = conditionTexts(element.kind, body).flatMap { (member, text) ->
        val syntax = ConditionRecognizer(text, conditionLanguage).recognize(syntaxEntryOf(member))
        if (syntax.failure != null) return null
        syntax.entities.mapNotNull { entity ->
            if (entity.outerMarked) return null
            if (entity.inFilter || entity.indexed || entity.indexKey || entity.inTerm) return@mapNotNull null
            val written = entity.writtenPath.let { if (isRuleContext(it, conditionLanguage)) group else it }
            markedLevelsOf(written, group, anchor, element)
        }
    }
    if (marks.none { it.joint.isNotEmpty() }) return emptySet()
    return marks.flatMapTo(LinkedHashSet()) { it.marked }
}

/** The index field of each level A12 marks parallel: members of the message beside the condition's own reads. */
internal fun TrackedFactBase.parallelIndexFields(element: TrackedDeclaration): List<String> =
    parallelMarkingOf(element).orEmpty().mapNotNull { level ->
        val name = declarationAt(level)?.let(::indexFieldNameOf) ?: return@mapNotNull null
        "$level/$name".takeIf { declarationAt(it)?.declaredKind == DeclaredKind.FIELD }
    }

/**
 * A row token `$#group$` over a level the condition iterates in parallel: admitted only where the condition proves
 * that level has a row, one AND arm or every OR arm carrying the proof, else `INVALID_USE_OF_ITERATION_PARAM`.
 *
 * A proof is a presence call whose operands are marked reads at or below the level, by [provesRow]'s law, so a
 * comparison, a negative presence and `FieldsNotCollectivelyFilled` prove nothing and an every-operand call mixing
 * the two sides proves neither; a read starred at the level itself spans its every row and so proves none (KF675). Declined where the answer would rest on a keyed or filtered
 * operand, or on one starred at another level.
 */
internal fun TrackedFactBase.parallelRowTokenDefect(named: String, element: TrackedDeclaration): MessageCompletion {
    if (element.kind != "Rule") return MessageCompletion.Accepted
    val marked = parallelMarkingOf(element) ?: return MessageCompletion.Declined
    if (named !in marked) return MessageCompletion.Accepted
    val text = element.body?.string("errorCondition") ?: return MessageCompletion.Declined
    val modifiers = setOfNotNull(OPERATOR_SPELLINGS[conditionLanguage]?.get(OperatorId.HAVING),
        OPERATOR_SPELLINGS[conditionLanguage]?.get(OperatorId.SEMANTIC_INDEX))
    val tree = ConditionRecognizer(text, conditionLanguage).recognize(SyntaxEntry.CONDITION).tree
        ?: return MessageCompletion.Declined
    val ruleGroup = element.path.substringBeforeLast('/')
    val proof = provesRow(tree, conditionLanguage) { call, operand ->
        val written = call.textOf(operand)
        if (operand.any { it.text in modifiers }) return@provesRow null
        val reference = referencesIn(written, conditionLanguage).singleOrNull() ?: return@provesRow null
        if ('*' in reference) {
            return@provesRow if (resolvedPathOf(reference.substringBefore('*'), ruleGroup, element) == named) false else null
        }
        val path = resolvedPathOf(reference, ruleGroup, element) ?: return@provesRow null
        path == named || path.startsWith("$named/")
    }
    return when (proof) {
        true -> MessageCompletion.Accepted
        null -> MessageCompletion.Declined
        false -> MessageCompletion.Refused("INVALID_USE_OF_ITERATION_PARAM")
    }
}
