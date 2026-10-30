package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.ConditionCall
import io.github.mbackschat.a12.dm.consistency.syntax.OPERATOR_SPELLINGS
import io.github.mbackschat.a12.dm.consistency.syntax.OperatorId

/**
 * A category inside an entity list whose carrier refuses one: `MVK_CATEGORY_NOT_ALLOWED`.
 *
 * `CheckEntityListenUtils.checkNoCategories` refuses a list any of whose operands projects a category, and on
 * an A12 model four carriers call it (source-read; the two other callers are H-model constructs). They differ
 * in WHERE they call it against the list `getRefList` builds, which is where the duplicate gates are asked, and
 * that position is the whole of this family's per-carrier knowledge ([KERNEL-FINDINGS KF482],
 * `adapter.laws.CheckerEntityListCategoryAgreementTest`):
 *
 * | carrier | its operators | the category gate |
 * |---|---|---|
 * | `CheckFeldListenAnzahlOpImpl` | `NumberOfFilledFields`, `NumberOfFilledGroups`, `NumberOfDifferentValues` | before the list, so ahead of a duplicate |
 * | `CheckFeldListenBedingungImpl` | the nine field-fill predicates, `DateRangesOverlap`, `FieldValuesNotUnique` | after the list and the root gate |
 * | `CheckKontextListenBedingungImpl` | the five group-list quantifiers, `GroupFilled`, `GroupNotFilled` | after the list |
 * | `CheckWiederholungBedingungImpl` | `RepetitionNotUnique` | after the list |
 *
 * **Only an Enumeration's declared category reaches the gate.** The spec's own category gate runs while the
 * operand is read, ahead of any list gate, and refuses a group, a field declaring no enumeration and an
 * undeclared category itself ([categoryContributions]).
 *
 * **Silent unless every operand reads cleanly**: a plain one the reference reader addresses, a categorized one
 * an Enumeration declaring its category. Anything else meets a class raised while the list is read, and that
 * class, not this one, is A12's answer.
 */
internal fun TrackedFactBase.entityListCategoryFindings(): List<Finding> = declarations().flatMap { element ->
    entityListCategoryContributions(element).take(1).map { it.finding }
}

internal fun TrackedFactBase.entityListCategoryContributions(
    element: TrackedDeclaration,
    execution: ConditionExecution? = conditionExecution(element),
): List<ConditionFinding> {
    if (categoryGatesByKeyword().isEmpty()) {
        return emptyList()
    }
    val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
    return callContributions(element, execution) { member, call ->
        categoryGateOf(call.operator)?.let { categoryGateFinding(call, it, group, element, member) }
    }
}

/** Where a carrier asks `checkNoCategories` against the list its `getRefList` builds. */
internal enum class CategoryGate {
    /** Before the list is built, so ahead of both duplicate gates. */
    BEFORE_THE_LIST,

    /** Once the list is built, so behind the duplicate gates and, where the carrier asks it, the root gate. */
    AFTER_THE_LIST,
}

/** Every operator whose carrier calls `checkNoCategories`, by catalog id, with where it calls it. */
private val CATEGORY_GATE_OF = mapOf(
    OperatorId.NUMBER_OF_FILLED_FIELDS to CategoryGate.BEFORE_THE_LIST,
    OperatorId.NUMBER_OF_FILLED_GROUPS to CategoryGate.BEFORE_THE_LIST,
    OperatorId.NUMBER_OF_DIFFERENT_VALUES to CategoryGate.BEFORE_THE_LIST,
    OperatorId.AT_LEAST_ONE_FIELD_FILLED to CategoryGate.AFTER_THE_LIST,
    OperatorId.NO_FIELD_FILLED to CategoryGate.AFTER_THE_LIST,
    OperatorId.FIELD_FILLED to CategoryGate.AFTER_THE_LIST,
    OperatorId.FIELD_NOT_FILLED to CategoryGate.AFTER_THE_LIST,
    OperatorId.NOT_ALL_FIELDS_FILLED to CategoryGate.AFTER_THE_LIST,
    OperatorId.ALL_FIELDS_FILLED to CategoryGate.AFTER_THE_LIST,
    OperatorId.FIELDS_NOT_COLLECTIVELY_FILLED to CategoryGate.AFTER_THE_LIST,
    OperatorId.NOT_EXACTLY_ONE_FIELD_FILLED to CategoryGate.AFTER_THE_LIST,
    OperatorId.MORE_THAN_ONE_FIELD_FILLED to CategoryGate.AFTER_THE_LIST,
    OperatorId.DATE_RANGES_OVERLAP to CategoryGate.AFTER_THE_LIST,
    OperatorId.FIELD_VALUES_NOT_UNIQUE to CategoryGate.AFTER_THE_LIST,
    OperatorId.AT_LEAST_ONE_GROUP_FILLED to CategoryGate.AFTER_THE_LIST,
    OperatorId.NO_GROUP_FILLED to CategoryGate.AFTER_THE_LIST,
    OperatorId.ALL_GROUPS_FILLED to CategoryGate.AFTER_THE_LIST,
    OperatorId.NOT_ALL_GROUPS_FILLED to CategoryGate.AFTER_THE_LIST,
    OperatorId.GROUPS_NOT_COLLECTIVELY_FILLED to CategoryGate.AFTER_THE_LIST,
    OperatorId.GROUP_FILLED to CategoryGate.AFTER_THE_LIST,
    OperatorId.GROUP_NOT_FILLED to CategoryGate.AFTER_THE_LIST,
    OperatorId.REPETITION_NOT_UNIQUE to CategoryGate.AFTER_THE_LIST,
)

/** [CATEGORY_GATE_OF] keyed by how this model's language spells each operator. */
private fun TrackedFactBase.categoryGatesByKeyword(): Map<String, CategoryGate> {
    val keywords = OPERATOR_SPELLINGS[conditionLanguage] ?: return emptyMap()
    return CATEGORY_GATE_OF.mapNotNull { (id, gate) -> keywords[id]?.let { it to gate } }.toMap()
}

/** Where [operator], as this model's language spells it, asks the category gate, or null where it asks none. */
internal fun TrackedFactBase.categoryGateOf(operator: String): CategoryGate? = categoryGatesByKeyword()[operator]

/**
 * Whether [call]'s category gate answers ahead of the duplicate gates: its carrier asks it before the list is
 * built and one of its operands projects a category. A family judging that list's duplicates yields there.
 */
internal fun TrackedFactBase.categoryGateAnswersBeforeTheList(
    call: ConditionCall,
    declaringGroup: String,
    element: TrackedDeclaration,
): Boolean = categoryGateOf(call.operator) == CategoryGate.BEFORE_THE_LIST &&
    call.operands.any { operandSpec(it, declaringGroup, element, call.inAFilterBody)?.category != null }

private fun TrackedFactBase.categoryGateFinding(
    call: ConditionCall,
    gate: CategoryGate,
    declaringGroup: String,
    element: TrackedDeclaration,
    member: String,
): Finding? {
    val reads = call.operands.map { operandSpec(it, declaringGroup, element, call.inAFilterBody) ?: return null }
    val categorized = reads.firstOrNull { it.category != null } ?: return null
    val targets = addressedTargetsOf(reads, declaringGroup, element, call.inAFilterBody) ?: return null
    if (reads.indices.any { at -> reads[at].category != null && !declaresTheCategory(targets[at], reads[at]) }) {
        return null
    }
    if (gate == CategoryGate.AFTER_THE_LIST && entityListPreGateAnswers(reads, targets)) {
        return null
    }
    return Finding(
        FindingCategory.A12_CONSTRAINT,
        FindingSeverity.ERROR,
        "MVK_CATEGORY_NOT_ALLOWED",
        "in `$member`: `${call.operator}` takes the entities themselves, and `${categorized.reference} -> " +
            "${categorized.category}` projects a category — drop the `-> ${categorized.category}`",
        element.path,
        call.spanIn(member),
    )
}

/** Whether [target] is an Enumeration declaring [read]'s category, the one categorized spec that reads. */
private fun TrackedFactBase.declaresTheCategory(target: String, read: OperandReference): Boolean {
    val declared = declarationAt(target)?.takeIf { it.declaredKind == DeclaredKind.FIELD } ?: return false
    val enumeration = enumerationBodyOf(declared)?.takeIf { it["tableName"] == null } ?: return false
    return read.category in enumeration.declaredCategoryNames().orEmpty()
}
