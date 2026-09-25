package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionCall
import io.github.mbackschat.a12.dm.consistency.syntax.callsIn

/**
 * What KIND of declaration each FIELD-LIST operation admits in the entity list it shares with the others.
 *
 * `CheckFeldListenOpImpl.check` is one method whose body is a switch on the OPERATOR token, run over a list
 * two shared gates have already accepted — so the class a model draws is decided by the caller and not by
 * the list ([KERNEL-FINDINGS KF391], `rulekit.validate.laws.FieldListOperandKindLawsTest`). A fifth carrier
 * lives in a DIFFERENT kernel method, `CheckFeldListenAnzahlOpImpl.check`, and is here rather than in its
 * own family because it asks this family's question with this family's pre-emptions — the two never share a
 * carrier, so neither can report the other's class ([KERNEL-FINDINGS KF399],
 * `rulekit.validate.laws.DistinctValueOperandKindLawsTest`).
 *
 * | the operator | what it demands of every expanded field | otherwise |
 * |---|---|---|
 * | `FirstFilledValue` | not a Confirm declaration | `MVK_NO_BOOLY_ALLOWED` |
 * | `Sum` | a Number declaration | `MVK_NO_NUMBER` |
 * | `MaxValue`, `MinValue`, behind a non-temporal LEADER | a Number declaration | `MVK_NOT_SORTABLE` |
 * | `NumberOfDifferentValues`, behind a text/custom/enum LEADER | one of those three | `MVK_STRING_ENUM_AND_NON_STRING_ENUM` |
 * | …and then | value validation not waived | `MVK_INVALID_RAW_TYPE` |
 * | `NumberOfDifferentValues`, behind a NUMBER leader | a Number declaration | `MVK_NUMBER_AND_NON_NUMBER` |
 * | `NumberOfDifferentValues`, behind a leader no arm claims | — | `MVK_ONLY_STRING_ENUM_NUMBER_CMP_DATE_ALLOWED` |
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
 * **`NumberOfDifferentValues` takes the same leader mechanism and three of its four arms differ from the
 * extrema's**, which is why it is a table rather than a shared branch. Its string arm FOLDS custom and
 * enumeration in with text where the extrema refuse both; its leader is read off the first EXPANDED field,
 * so a group leader is its own first member; and a leader no arm claims — Boolean, Confirm or DateRange —
 * has a class of its OWN rather than falling into the numeric one. That last row is the one a reader
 * assembling arms from the codes' names would miss, and all three unclaimed leaders are measured.
 *
 * **Its value-validation gate runs over the whole list AFTER the kind walk**, so a kind defect later in the
 * list than a waiving field still answers first — measured, and the reason the two walks are sequential.
 *
 * **Silent wherever a gate ahead of the switch answers instead** — the shared duplicate and root-group
 * pre-gates ([entityListPreGateAnswers]) and this construct's arity gate, a lone unstarred field, which is
 * `MVK_PARAMSIZE_INVALIDN`. All five carriers take that same arity gate. Naming a kind class on any of those
 * would be the wrong class on a correct rejection.
 *
 * **`NumberOfDifferentValues` meets a SIXTH gate the other four never do, and NOBODY reports it.**
 * `checkNoCategories` is the first statement of its method, ahead of the reference list being built, so a
 * `->` access anywhere in its list is `MVK_CATEGORY_NOT_ALLOWED` whatever else is wrong with it — measured,
 * on a list that also carries a duplicate. The shared operand reducer declines a categorized operand, so
 * this family never sees such a call and no other reads this carrier: an under-report, stated here rather
 * than left to read as agreement.
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
internal fun TrackedFactBase.fieldListOperationOperandFindings(): List<Finding> {
    val keywords = CONDITION_OPERATOR_KEYWORDS[conditionLanguage] ?: return emptyList()
    val arms = ARM_OF.mapNotNull { (id, arm) -> keywords[id]?.let { it to arm } }.toMap()
    if (arms.isEmpty()) {
        return emptyList()
    }
    return declarations().flatMap { element ->
        val body = element.body ?: return@flatMap emptyList()
        val declaringGroup = element.path.substringBeforeLast('/', missingDelimiterValue = "")
        conditionTexts(element.kind, body).flatMap { (member, text) ->
            callsIn(text, conditionLanguage, syntaxEntryOf(member))
                .mapNotNull { call ->
                    val arm = arms[call.operator] ?: return@mapNotNull null
                    operandDefect(call, arm, declaringGroup, element)
                        ?.let { defect -> finding(defect, call, element, member) }
                }
        }
    }
}

/** One arm of A12's switch on the operator — which question it asks of the list it was handed. */
private enum class FieldListArm {
    NO_CONFIRM,
    ALL_NUMBERS,
    SORTABLE,
    DISTINCT,
}

private val ARM_OF = mapOf(
    "FirstFilledValue" to FieldListArm.NO_CONFIRM,
    "Sum" to FieldListArm.ALL_NUMBERS,
    "MaxValue" to FieldListArm.SORTABLE,
    "MinValue" to FieldListArm.SORTABLE,
    "NumberOfDifferentValues" to FieldListArm.DISTINCT,
)


/** The declarations `NumberOfDifferentValues`' leader sends down the string arm, which folds all three. */
private val STRING_LIKE_LEADERS = setOf(FieldType.STRING, FieldType.CUSTOM_FIELD, FieldType.ENUMERATION)

/** The leaders NO arm claims, each measured: the else branch has a class of its own. */
private val UNCLAIMED_LEADERS = setOf(FieldType.BOOLEAN, FieldType.CONFIRM, FieldType.DATE_RANGE)

/** The declarations this reader is CERTAIN `isLikeString || isEnum` refuses, every member measured. */
private val PLAINLY_NON_STRING_LIKE_TYPES =
    setOf(FieldType.NUMBER, FieldType.BOOLEAN, FieldType.CONFIRM, FieldType.DATE_RANGE) + TEMPORAL_DECLARED_TYPES

/** What one arm's walk landed on: the class, the field that drew it, and what the operator wanted. */
private class FieldListDefect(val code: String, val demand: String, val field: TrackedDeclaration)

/** The first defect this arm finds, or null where it finds none and where it may not judge. */
private fun TrackedFactBase.operandDefect(
    call: ConditionCall,
    arm: FieldListArm,
    declaringGroup: String,
    element: TrackedDeclaration,
): FieldListDefect? {
    val reads = call.operands.map { operand ->
        operandReferenceIn(operand.text, declaringGroup, element) { operand.path?.let { OperandReference(it) } }
            ?: return null
    }
    val targets = reads.map { read ->
        addressedTargetOf(read.reference, declaringGroup, element, read.indexSelected, call.inAFilterBody)
            ?: return null
    }
    if (preEmptedBeforeTheSwitch(reads, targets)) {
        return null
    }
    val fields = targets.flatMap { target ->
        val declared = declarationAt(target) ?: return null
        fieldsUnder(declared) ?: return null
    }
    if (fields.isEmpty()) {
        return null
    }
    return when (arm) {
        FieldListArm.NO_CONFIRM -> fields.firstOrNull { it.declaredFieldType() == FieldType.CONFIRM }
            ?.let { FieldListDefect("MVK_NO_BOOLY_ALLOWED", "takes no confirm declaration in its list", it) }
        FieldListArm.ALL_NUMBERS -> fields.firstOrNull { it.declaredFieldType() in PLAINLY_NON_NUMBER_TYPES }
            ?.let {
                FieldListDefect("MVK_NO_NUMBER", "adds up its list, so every field in it must be a number", it)
            }
        FieldListArm.SORTABLE ->
            // The leader picks the arm, so an unplaceable or temporal one is not this family's to judge.
            if (fields.first().declaredFieldType().let { it == null || it in TEMPORAL_DECLARED_TYPES }) {
                null
            } else {
                fields.firstOrNull { it.declaredFieldType() in PLAINLY_NON_NUMBER_TYPES }?.let {
                    FieldListDefect(
                        "MVK_NOT_SORTABLE",
                        "orders its list, and a list led by a non-date must be all numbers",
                        it,
                    )
                }
            }
        FieldListArm.DISTINCT -> distinctDefect(fields)
    }
}

/**
 * The `NumberOfDifferentValues` arm, which is itself a switch on the LEADER's declared type.
 *
 * Four leaders, four outcomes, and the two this reader answers walk the rest of the list with the leader's
 * own predicate. A temporal leader hands the list to [temporalAggregateOperandFindings]; a leader whose
 * effective type this reader does not resolve leaves the call unjudged rather than guessing the arm.
 *
 * **The value-validation gate runs over the WHOLE list after the kind walk**, not interleaved with it, so a
 * kind defect later than a waiving field still answers first — measured, and the reason the two walks here
 * are sequential rather than one pass.
 */
private fun distinctDefect(fields: List<TrackedDeclaration>): FieldListDefect? {
    val leader = fields.first().declaredFieldType() ?: return null
    return when {
        leader in STRING_LIKE_LEADERS ->
            fields.firstOrNull { it.declaredFieldType() in PLAINLY_NON_STRING_LIKE_TYPES }?.let {
                FieldListDefect(
                    "MVK_STRING_ENUM_AND_NON_STRING_ENUM",
                    "counts distinct values of a list led by a text, custom or enumeration field, so " +
                        "every field in it must be one of those three",
                    it,
                )
            } ?: fields.firstOrNull { it.waivesValueValidation() }?.let {
                FieldListDefect(
                    "MVK_INVALID_RAW_TYPE",
                    "counts distinct values, which it cannot do over a field that waives value validation",
                    it,
                )
            }
        leader == FieldType.NUMBER -> fields.firstOrNull { it.declaredFieldType() in PLAINLY_NON_NUMBER_TYPES }
            ?.let {
                FieldListDefect(
                    "MVK_NUMBER_AND_NON_NUMBER",
                    "counts distinct values of a list led by a number, so every field in it must be one",
                    it,
                )
            }
        leader in UNCLAIMED_LEADERS -> FieldListDefect(
            "MVK_ONLY_STRING_ENUM_NUMBER_CMP_DATE_ALLOWED",
            "counts distinct values only of a text, custom, enumeration, number or date list, and the " +
                "LEADING field is what decides which",
            fields.first(),
        )
        else -> null
    }
}

/**
 * Whether a gate ahead of the operator switch answers this list instead.
 *
 * The shared pre-gates every entity list meets, plus the arity gate all four carriers here take, which
 * demands more than one field or one group.
 */
private fun TrackedFactBase.preEmptedBeforeTheSwitch(reads: List<OperandReference>, targets: List<String>): Boolean {
    val loneField = targets.size == 1 && '*' !in reads.first().reference &&
        declarationAt(targets.first())?.declaredKind == DeclaredKind.FIELD
    return entityListPreGateAnswers(reads, targets) || loneField
}

private fun finding(
    defect: FieldListDefect,
    call: ConditionCall,
    element: TrackedDeclaration,
    member: String,
): Finding = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    defect.code,
    "in `$member`: `${call.operator}` ${defect.demand}, and `${defect.field.path}` is declared " +
        "`${defect.field.declaredFieldType()}` — drop that field from the list, or reach for an operator " +
        "whose list admits its kind",
    element.path,
    call.spanIn(member),
)
