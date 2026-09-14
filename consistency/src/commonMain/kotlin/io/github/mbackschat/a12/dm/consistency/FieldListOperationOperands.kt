package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionCall
import io.github.mbackschat.a12.dm.consistency.syntax.callsIn

/**
 * What KIND of declaration each FIELD-LIST operation admits in the entity list it shares with the others.
 *
 * `CheckFeldListenOpImpl.check` is one method whose body is a switch on the OPERATOR token, run over a list
 * two shared gates have already accepted — so the class a model draws is decided by the caller and not by
 * the list ([KERNEL-FINDINGS KF391], `rulekit.validate.laws.FieldListOperandKindLawsTest`).
 *
 * | the operator | what it demands of every expanded field | otherwise |
 * |---|---|---|
 * | `FirstFilledValue` | not a Confirm declaration | `MVK_NO_BOOLY_ALLOWED` |
 * | `Sum` | a Number declaration | `MVK_NO_NUMBER` |
 * | `MaxValue`, `MinValue`, behind a non-temporal LEADER | a Number declaration | `MVK_NOT_SORTABLE` |
 *
 * **The confirm gate is named for a kind it does not name.** `checkNoBoolY` tests `FieldUtils.isYes`, which
 * is `IYesType` alone; `IYesNoType` is a sibling interface rather than a subtype, so a BOOLEAN declaration
 * clears the gate and only a CONFIRM one is refused. Reading the code as "no boolean kinds" is wrong in
 * exactly that cell, and the grid measures both.
 *
 * **One Confirm anywhere in the list refuses it**, in any position and whatever the other operands are,
 * because the walk runs ahead of the type-matching gate rather than inside it.
 *
 * **The extrema's LEADER decides which arm judges the list**, which is why they carry two classes rather
 * than one: a temporal leader hands the list to [temporalAggregateOperandFindings] and its component-set
 * comparison, and everything else takes the flat numeric question here. A leader whose declared type this
 * reader cannot place leaves the call unjudged rather than guessing the arm.
 *
 * **Silent wherever a gate ahead of the switch answers instead** — the shared duplicate and root-group
 * pre-gates ([entityListPreGateAnswers]) and this construct's arity gate, a lone unstarred field, which is
 * `MVK_PARAMSIZE_INVALIDN`. Naming a kind class on any of those would be the wrong class on a correct
 * rejection.
 *
 * **Type MATCHING is deliberately not reported.** `MVK_VARYING_TYPES_NOT_ALLOWED`, which `FirstFilledValue`
 * draws behind the confirm gate, rests on `OperationResultType.matches` — a comparison that reads a date's
 * declared format as well as its base class, measured in the same grid — and transcribing it is its own
 * question. Under-reporting it costs a model nothing here.
 *
 * **`SumOfProducts` is not a carrier**, though it takes the same numeric arm: six of its own gates run
 * before it, and each is a class this family would have to answer for to avoid naming the numeric one on a
 * model A12 refuses for a parallel-list defect. [sumOfProductsOperandFindings] owns that operator.
 *
 * **A TypeDef or Unspecified declaration is skipped rather than judged**, because its effective type is a
 * definition body this checker declares as a coverage gap — a number-based typedef is admitted by every arm
 * here, measured, so refusing one would be an over-rejection. The walk continues past it: a later field's
 * class is the same class A12 would report at the typedef, so only the field a message names can differ.
 */
fun PreparedModel.fieldListOperationOperandFindings(): List<Finding> {
    val keywords = CONDITION_OPERATOR_KEYWORDS[conditionLanguage] ?: return emptyList()
    val arms = ARM_OF.mapNotNull { (id, arm) -> keywords[id]?.let { it to arm } }.toMap()
    if (arms.isEmpty()) {
        return emptyList()
    }
    return elements.flatMap { element ->
        val body = element.body ?: return@flatMap emptyList()
        val declaringGroup = element.path.substringBeforeLast('/', missingDelimiterValue = "")
        conditionTexts(element.kind, body).flatMap { (member, text) ->
            callsIn(text, conditionLanguage, syntaxEntryOf(member))
                .mapNotNull { call ->
                    val arm = arms[call.operator] ?: return@mapNotNull null
                    operandDefect(call, arm, declaringGroup, element)
                        ?.let { offending -> finding(arm, offending, call, element, member) }
                }
        }
    }
}

/** One arm of A12's switch on the operator: what it demands, and the class it refuses with. */
private enum class FieldListArm(val code: String, val demand: String) {
    NO_CONFIRM("MVK_NO_BOOLY_ALLOWED", "takes no confirm declaration in its list"),
    ALL_NUMBERS("MVK_NO_NUMBER", "adds up its list, so every field in it must be a number"),
    SORTABLE("MVK_NOT_SORTABLE", "orders its list, and a list led by a non-date must be all numbers"),
}

private val ARM_OF = mapOf(
    "FirstFilledValue" to FieldListArm.NO_CONFIRM,
    "Sum" to FieldListArm.ALL_NUMBERS,
    "MaxValue" to FieldListArm.SORTABLE,
    "MinValue" to FieldListArm.SORTABLE,
)

private const val CONFIRM_TYPE = "ConfirmType"

/** The first listed field this arm refuses, or null where it refuses none and where it may not judge. */
private fun PreparedModel.operandDefect(
    call: ConditionCall,
    arm: FieldListArm,
    declaringGroup: String,
    element: DeclaredElement,
): DeclaredElement? {
    val written = call.operands.map { operand -> operand.path ?: return null }
    val targets = written.map { addressedTargetOf(it, declaringGroup, element) ?: return null }
    if (preEmptedBeforeTheSwitch(written, targets)) {
        return null
    }
    val fields = targets.flatMap { target ->
        val declared = elements.firstOrNull { it.path == target } ?: return null
        when (declared.declaredKind) {
            DeclaredKind.FIELD -> listOf(declared)
            DeclaredKind.GROUP ->
                elements.filter { it.declaredKind == DeclaredKind.FIELD && it.path.startsWith("$target/") }
            else -> return null
        }
    }
    if (fields.isEmpty()) {
        return null
    }
    return when (arm) {
        FieldListArm.NO_CONFIRM -> fields.firstOrNull { it.declaredFieldType() == CONFIRM_TYPE }
        FieldListArm.ALL_NUMBERS -> fields.firstOrNull { it.declaredFieldType() in PLAINLY_NON_NUMBER_TYPES }
        FieldListArm.SORTABLE ->
            // The leader picks the arm, so an unplaceable or temporal one is not this family's to judge.
            if (fields.first().declaredFieldType().let { it == null || it in TEMPORAL_DECLARED_TYPES }) {
                null
            } else {
                fields.firstOrNull { it.declaredFieldType() in PLAINLY_NON_NUMBER_TYPES }
            }
    }
}

/**
 * Whether a gate ahead of the operator switch answers this list instead.
 *
 * The shared pre-gates every entity list meets, plus the arity gate all four carriers here take, which
 * demands more than one field or one group.
 */
private fun PreparedModel.preEmptedBeforeTheSwitch(written: List<String>, targets: List<String>): Boolean {
    val loneField = targets.size == 1 && '*' !in written.first() &&
        elements.firstOrNull { it.path == targets.first() }?.declaredKind == DeclaredKind.FIELD
    return entityListPreGateAnswers(written, targets) || loneField
}

private fun finding(
    arm: FieldListArm,
    offending: DeclaredElement,
    call: ConditionCall,
    element: DeclaredElement,
    member: String,
): Finding = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    arm.code,
    "in `$member`: `${call.operator}` ${arm.demand}, and `${offending.path}` is declared " +
        "`${offending.declaredFieldType()}` — drop that field from the list, or reach for an operator " +
        "whose list admits its kind",
    element.path,
)
