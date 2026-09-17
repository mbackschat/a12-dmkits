package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.CallOperand
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionCall
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionLanguage
import io.github.mbackschat.a12.dm.consistency.syntax.callsIn
import io.github.mbackschat.a12.dm.consistency.syntax.referencesIn
import io.github.mbackschat.a12.dm.consistency.syntax.splitAtIn

/**
 * The two gates an entity list meets BEFORE its operator asks anything of its own.
 *
 * `CheckEntityListenUtils.getRefList` refuses a repeated entity while it BUILDS the list, and most callers
 * then ask `checkNotRootGroupReferenced` ([KERNEL-FINDINGS KF388],
 * `rulekit.validate.laws.EntityListPreGateLawsTest`). Both are shared utilities, so the operator does not
 * decide the law — it decides only whether the law is ASKED, which is why this is a caller table.
 *
 * | the question | class | asked by |
 * |---|---|---|
 * | is an unstarred entity named twice? | `MVK_DUPLICATE_PARAM1` | every caller |
 * | does one entity lie under another of the same list? | `MVK_DUPLICATE_PARAM2` | every caller |
 * | is a ROOT group in the list? | `MVK_ROOT_GROUP_REFERENCED` | [RootGroupGate.PLAIN] |
 *
 * **Duplicates precede everything**, the root-group gate and each operator's own arity gate included, because
 * they are asked while the list is built rather than after — measured on lists that break two laws at once.
 *
 * **A STARRED read is exempt from the direct-duplicate test and not from the nested one.**
 * `checkDuplicatesInDirectRefs` skips a reference carrying a wildcard, two starred reads of one list being
 * two different rows rather than one entity twice; `checkDuplicatesInIndirectRefs` skips nothing.
 *
 * **The root group beside ANY other entity of its own model is a nested duplicate, never a root-group
 * reference**, every entity being under it — so on a one-root model the root-group gate is reachable only on
 * a list of one, which is the shape the grid measures it on.
 *
 * **Three of these carriers ask no root-group gate at all**: `GroupFilled` and `GroupNotFilled` take the arm
 * of `CheckKontextListenBedingungImpl` that calls only `checkOnlyGroups`, and `RepetitionNotUnique` asks its
 * own key-list gates instead. All three still meet both duplicate gates.
 *
 * **The six GROUP-LIST carriers are not here**: [groupListOperandFindings] owns them, with a finer root
 * table — three of them admit a SOLE root and draw `MVK_ROOT_GROUP_WITH_OTHER_PARAMETERS` only beside
 * another operand. This family is the widening of the boundary that one names, onto the field and value
 * lists A12 draws the same classes on.
 *
 * **Silent wherever the reference reader answers first**: an operand carrying a clause — a filter, a scope, a
 * semantic-index key, a category arrow — is not one this reader may compare, A12's own duplicate test
 * comparing the semantic index and the category alongside the path, so two same-path operands under
 * different keys are not duplicates at all.
 */
internal fun TrackedFactBase.entityListPreGateFindings(): List<Finding> {
    val keywords = CONDITION_OPERATOR_KEYWORDS[conditionLanguage] ?: return emptyList()
    val gates = ROOT_GROUP_GATE_OF.mapNotNull { (id, gate) -> keywords[id]?.let { it to gate } }.toMap()
    val infixCarrier = keywords[VALUE_IN_FIELDS]
    if (gates.isEmpty()) {
        return emptyList()
    }
    return declarations().mapNotNull { element ->
        val body = element.body ?: return@mapNotNull null
        val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
        conditionTexts(element.kind, body).firstNotNullOfOrNull { (member, text) ->
            callsIn(text, conditionLanguage, syntaxEntryOf(member))
                .firstNotNullOfOrNull { call ->
                    gates[call.operator]?.let { gate ->
                        preGateFinding(call, gate, infixCarrier, group, element, member)
                    }
                }
        }
    }
}

/** Which root-group gate an operator's construct class asks — the axis the caller table turns on. */
private enum class RootGroupGate {
    /** `checkNotRootGroupReferenced`: any root group in the list is refused. */
    PLAIN,

    /** No root-group gate; the operator asks only about kind. */
    NONE,
}

/** `NumberOfValueInFields` lists its entities after an infix keyword rather than as its own operands. */
private const val VALUE_IN_FIELDS = "NumberOfValueInFields"

/**
 * Every operator that builds an entity list, and the root-group gate its construct class asks.
 *
 * The denominator is the operator set `EntityListArity` enumerates, plus the carriers it leaves out for want
 * of an arity law of their own, less the six [groupListOperandFindings] already owns.
 */
private val ROOT_GROUP_GATE_OF = mapOf(
    "FieldFilled" to RootGroupGate.PLAIN,
    "FieldNotFilled" to RootGroupGate.PLAIN,
    "AtLeastOneFieldFilled" to RootGroupGate.PLAIN,
    "NoFieldFilled" to RootGroupGate.PLAIN,
    "SumOfProducts" to RootGroupGate.PLAIN,
    "AllFieldsFilled" to RootGroupGate.PLAIN,
    "FieldsNotCollectivelyFilled" to RootGroupGate.PLAIN,
    "NotAllFieldsFilled" to RootGroupGate.PLAIN,
    "NotExactlyOneFieldFilled" to RootGroupGate.PLAIN,
    "MoreThanOneFieldFilled" to RootGroupGate.PLAIN,
    "FieldValuesNotUnique" to RootGroupGate.PLAIN,
    "MaxValue" to RootGroupGate.PLAIN,
    "MinValue" to RootGroupGate.PLAIN,
    "Sum" to RootGroupGate.PLAIN,
    "FirstFilledValue" to RootGroupGate.PLAIN,
    "NumberOfFilledFields" to RootGroupGate.PLAIN,
    "NumberOfDifferentValues" to RootGroupGate.PLAIN,
    VALUE_IN_FIELDS to RootGroupGate.PLAIN,
    "DateRangesOverlap" to RootGroupGate.PLAIN,
    "GroupFilled" to RootGroupGate.NONE,
    "GroupNotFilled" to RootGroupGate.NONE,
    "RepetitionNotUnique" to RootGroupGate.NONE,
)

private fun finding(code: String, explain: String, at: String): Finding =
    Finding(FindingCategory.A12_CONSTRAINT, FindingSeverity.ERROR, code, explain, at)

private const val DIRECT_DUPLICATE = "MVK_DUPLICATE_PARAM1"

private const val NESTED_DUPLICATE = "MVK_DUPLICATE_PARAM2"

private const val ROOT_GROUP = "MVK_ROOT_GROUP_REFERENCED"

private fun TrackedFactBase.preGateFinding(
    call: ConditionCall,
    gate: RootGroupGate,
    infixCarrier: String?,
    declaringGroup: String,
    element: TrackedDeclaration,
    member: String,
): Finding? {
    val written = call.entityListReferences(conditionLanguage, infixCarrier) ?: return null
    val targets = written.map { addressedTargetOf(it, declaringGroup, element) ?: return null }
    val direct = targets.firstDirectDuplicate(written)
    if (direct != null) {
        return finding(
            DIRECT_DUPLICATE,
            "in `$member`: `${call.operator}` names `${written[direct]}` twice — one entity counts once " +
                "however often it is listed, so drop the repeat",
            element.path,
        )
    }
    val nested = targets.firstNestedEntity()
    if (nested != null) {
        return finding(
            NESTED_DUPLICATE,
            "in `$member`: `${call.operator}` names `${written[nested]}` and a group that already " +
                "contains it — drop whichever of the two you did not mean",
            element.path,
        )
    }
    val rootGroup = targets.indexOfFirst { it.isRootGroup() }.takeIf { it >= 0 } ?: return null
    val code = when (gate) {
        RootGroupGate.PLAIN -> ROOT_GROUP
        RootGroupGate.NONE -> return null
    }
    return finding(
        code,
        "in `$member`: `${call.operator}` names the root group `${written[rootGroup]}` — name the fields " +
            "or groups you mean instead",
        element.path,
    )
}

/**
 * The entity list's references as written, or null wherever this reader may not compare them.
 *
 * The infix carrier keeps its entities after an `In` keyword rather than as its own operands, so its texts
 * are re-read here; every other carrier has already reduced each operand, and a null [CallOperand.path] is
 * precisely the clause-carrying or computed operand no duplicate test of this reader's may judge.
 */
private fun ConditionCall.entityListReferences(
    language: ConditionLanguage,
    infixCarrier: String?,
): List<String>? {
    if (operator == infixCarrier) {
        val texts = splitAtIn()?.second ?: return null
        return texts.map { text ->
            if (!isBareEntitySpec(text)) return null
            referencesIn(text, language).singleOrNull() ?: return null
        }
    }
    return operands.map { it.path ?: return null }
}

/**
 * The index of the first reference that repeats an earlier one, or null.
 *
 * A starred reference is skipped exactly as `checkDuplicatesInDirectRefs` skips one, so the index this
 * returns is into the FULL list rather than into the unstarred part of it.
 */
private fun List<String>.firstDirectDuplicate(written: List<String>): Int? {
    val seen = mutableSetOf<String>()
    indices.forEach { at ->
        if ('*' !in written[at] && !seen.add(this[at])) {
            return at
        }
    }
    return null
}

/** The index of the first reference lying under another entity of the same list, or null. */
private fun List<String>.firstNestedEntity(): Int? =
    indexOfFirst { inner -> any { outer -> inner.startsWith("$outer/") } }.takeIf { it >= 0 }

/** Whether this model path names a ROOT group — one step below the model, so one separator deep. */
private fun String.isRootGroup(): Boolean = count { it == '/' } == 1

/**
 * Whether a duplicate or root-group gate answers this list before its operator asks anything.
 *
 * The shared home for the two families that judge an entity list further on: both are asked while the list
 * is BUILT, so a family naming its own class on such a list would name the wrong one on a correct rejection.
 * The root-group arm is read as PLAIN here because a family that yields on a root group is silent either
 * way — under-reporting where the caller asks no gate, never over-reporting.
 */
internal fun entityListPreGateAnswers(written: List<String>, targets: List<String>): Boolean =
    targets.firstDirectDuplicate(written) != null ||
        targets.firstNestedEntity() != null ||
        targets.any { it.isRootGroup() }
