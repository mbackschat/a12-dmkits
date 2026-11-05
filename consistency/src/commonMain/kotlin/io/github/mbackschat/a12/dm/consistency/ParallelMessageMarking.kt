package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CallShape
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionRecognizer
import io.github.mbackschat.a12.dm.consistency.syntax.MessageCompletion
import io.github.mbackschat.a12.dm.consistency.syntax.OPERATOR_SPELLINGS
import io.github.mbackschat.a12.dm.consistency.syntax.OperatorId
import io.github.mbackschat.a12.dm.consistency.syntax.SyntaxEntry
import io.github.mbackschat.a12.dm.consistency.syntax.Token
import io.github.mbackschat.a12.dm.consistency.syntax.isRuleContext
import io.github.mbackschat.a12.dm.consistency.syntax.provesPresence
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
 * A proof is a presence call whose operands are marked reads at or below the level: every operand of
 * `FieldFilled`, `GroupFilled`, `AtLeastOneFieldFilled` and `MoreThanOneFieldFilled`, any one of `AllFieldsFilled`
 * and `AllGroupsFilled`. A comparison, a negative presence and `FieldsNotCollectivelyFilled` prove nothing, and an
 * every-operand call mixing the two sides proves neither (KF675). Declined where the answer would rest on a call this reading
 * has not measured, `AtLeastOneGroupFilled`, or on a starred, keyed or filtered operand.
 */
internal fun TrackedFactBase.parallelRowTokenDefect(named: String, element: TrackedDeclaration): MessageCompletion {
    if (element.kind != "Rule") return MessageCompletion.Accepted
    val marked = parallelMarkingOf(element) ?: return MessageCompletion.Declined
    if (named !in marked) return MessageCompletion.Accepted
    val text = element.body?.string("errorCondition") ?: return MessageCompletion.Declined
    val spellings = OPERATOR_SPELLINGS[conditionLanguage] ?: return MessageCompletion.Declined
    fun spelled(vararg ids: OperatorId) = ids.mapNotNull { spellings[it] }.toSet()
    val everyOperand = spelled(OperatorId.FIELD_FILLED, OperatorId.GROUP_FILLED, OperatorId.AT_LEAST_ONE_FIELD_FILLED,
        OperatorId.MORE_THAN_ONE_FIELD_FILLED)
    val anyOperand = spelled(OperatorId.ALL_FIELDS_FILLED, OperatorId.ALL_GROUPS_FILLED)
    val unmeasured = spelled(OperatorId.AT_LEAST_ONE_GROUP_FILLED)
    val modifiers = spelled(OperatorId.HAVING, OperatorId.SEMANTIC_INDEX)
    val tree = ConditionRecognizer(text, conditionLanguage).recognize(SyntaxEntry.CONDITION).tree
        ?: return MessageCompletion.Declined
    val ruleGroup = element.path.substringBeforeLast('/')
    var undecided = false
    fun inLevel(call: CallShape, operand: List<Token>): Boolean? {
        val written = call.textOf(operand)
        if ('*' in written || operand.any { it.text in modifiers }) return null
        val reference = referencesIn(written, conditionLanguage).singleOrNull() ?: return null
        val path = resolvedPathOf(reference, ruleGroup, element) ?: return null
        return path == named || path.startsWith("$named/")
    }
    val proven = provesPresence(tree) { call ->
        when (call.operator) {
            in everyOperand, in anyOperand -> {
                val verdicts = call.operands.map { inLevel(call, it) }
                if (null in verdicts) { undecided = true; false }
                else if (call.operator in everyOperand) verdicts.all { it == true } else verdicts.any { it == true }
            }
            in unmeasured -> { undecided = true; false }
            else -> false
        }
    }
    return when {
        proven -> MessageCompletion.Accepted
        undecided -> MessageCompletion.Declined
        else -> MessageCompletion.Refused("INVALID_USE_OF_ITERATION_PARAM")
    }
}
