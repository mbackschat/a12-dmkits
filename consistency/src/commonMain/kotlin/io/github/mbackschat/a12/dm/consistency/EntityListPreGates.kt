package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.CallOperand
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionCall
import io.github.mbackschat.a12.dm.consistency.syntax.NUMBER_OF_VALUE_IN_FIELDS
import io.github.mbackschat.a12.dm.consistency.syntax.SEMANTIC_INDEX
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
    val infixCarrier = keywords[NUMBER_OF_VALUE_IN_FIELDS]
    val valueListCarriers = VALUE_LIST_QUANTIFIERS.mapNotNull { keywords[it] }.toSet()
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
                        preGateFinding(call, gate, infixCarrier, valueListCarriers, group, element, member)
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

/** The value-list quantifiers, which list their entities BEFORE an infix keyword, the values after it. */
private val VALUE_LIST_QUANTIFIERS = listOf(
    "AtLeastOneFieldValueIncludedInValueList",
    "NoFieldValueIncludedInValueList",
    "NotAllFieldValuesIncludedInValueList",
)

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
    NUMBER_OF_VALUE_IN_FIELDS to RootGroupGate.PLAIN,
    "DateRangesOverlap" to RootGroupGate.PLAIN,
    "AtLeastOneFieldValueIncludedInValueList" to RootGroupGate.PLAIN,
    "NoFieldValueIncludedInValueList" to RootGroupGate.PLAIN,
    "NotAllFieldValuesIncludedInValueList" to RootGroupGate.PLAIN,
    "GroupFilled" to RootGroupGate.NONE,
    "GroupNotFilled" to RootGroupGate.NONE,
    "RepetitionNotUnique" to RootGroupGate.NONE,
)

private fun finding(code: String, explain: String, at: String, span: TextSpan): Finding =
    Finding(FindingCategory.A12_CONSTRAINT, FindingSeverity.ERROR, code, explain, at, span)

private const val DIRECT_DUPLICATE = "MVK_DUPLICATE_PARAM1"

private const val NESTED_DUPLICATE = "MVK_DUPLICATE_PARAM2"

private const val ROOT_GROUP = "MVK_ROOT_GROUP_REFERENCED"

private fun TrackedFactBase.preGateFinding(
    call: ConditionCall,
    gate: RootGroupGate,
    infixCarrier: String?,
    valueListCarriers: Set<String>,
    declaringGroup: String,
    element: TrackedDeclaration,
    member: String,
): Finding? {
    val written = entityListOperandsOf(call, infixCarrier, valueListCarriers, declaringGroup, element) ?: return null
    // ALL or nothing, and that is a measured rule rather than caution. One operand A12 cannot address makes
    // the per-reference wildcard bar answer for the whole call — `MVK_NO_WILDCARD`, in BOTH operand orders
    // (KERNEL-FINDINGS KF443, `validate.laws.DuplicateOverlapCarrierSweepLawsTest`) — so a duplicate sitting
    // elsewhere in that list is not this family's to name. Judging each operand alone reported it, which is
    // the shape of an over-rejection: a code A12 does not give, on a model it refuses for something else.
    val targets = written.map { addressedTargetOf(it.spec, declaringGroup, element, it.indexSelected) ?: return null }
    val direct = targets.firstDirectDuplicate(written)
    if (direct != null) {
        return finding(
            DIRECT_DUPLICATE,
            "in `$member`: `${call.operator}` names `${written[direct].spec}` twice — one entity counts " +
                "once however often it is listed, so drop the repeat",
            element.path,
            call.spanIn(member),
        )
    }
    val nested = targets.firstNestedEntity(written)
    if (nested != null) {
        return finding(
            NESTED_DUPLICATE,
            "in `$member`: `${call.operator}` names `${written[nested].spec}` and a group that already " +
                "contains it — drop whichever of the two you did not mean",
            element.path,
            call.spanIn(member),
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
        call.spanIn(member),
    )
}

/**
 * One entity-list operand as this reader can compare it: the spec it names, and the rest of its identity.
 *
 * @param spec the bare entity spec, for the star test and for the diagnostic's text.
 * @param key everything A12 compares BESIDES the resolved path — the clauses as spelled. Empty for a plain
 *     operand, so two plain operands compare on path alone exactly as before.
 * @param indexSelected whether a semantic-index key is among those clauses, which decides whether the
 *     operand's repetition is ADDRESSED — a separate question from identity, and the one DG57 closed.
 */
private data class ListOperand(val spec: String, val key: String, val indexSelected: Boolean = false)

/**
 * The entity list's operands as written, or null wherever this reader may not read the list at all.
 *
 * The infix carrier keeps its entities after an `In` keyword rather than as its own operands, so its texts
 * are re-read here; every other carrier has already reduced each operand.
 *
 * **A value-list quantifier keeps them BEFORE the `In`, and is read only in its written-out spelling.**
 * There the field list alone is the entity list. Against a field-read value list A12 still answers the root
 * class on either side, but not the nested duplicate across the two halves (`/Parcel In /Parcel/Label` is
 * the root class, KERNEL-FINDINGS KF388), so reading one half would name the wrong class and reading both
 * as one list would too.
 *
 * **A CLAUSE-carrying operand yields itself rather than silencing the list.** Returning null for the whole
 * list meant one such operand answered for its siblings: the plain duplicate in
 * `Sum(Lines/Amount For "A", Quantity, Quantity)` went unreported although it needs no clause read at all
 * (measured against the kernel 2026-09-20; the row is in the PR #3 review record under `docs/archived/`).
 * A12's identity is four components rather than one — resolved path, category projection, semantic index and
 * the `$` outer flag (KERNEL-FINDINGS kf439) — so such an operand is differently KEYED, not unjudgeable.
 *
 * **Every OTHER unreducible operand still silences the list, and that is the measured basis rather than
 * caution.** A categorized operand is the case that proves it: A12 answers `MVK_CATEGORY_NOT_ALLOWED` for
 * `NumberOfDifferentValues(Severity -> Band, Notes, Notes)` — its category bar runs ahead of the duplicate
 * the list also carries — so reading the duplicate there would name a class A12 does not
 * (`FieldListOperationOperandsTest` holds that row). The clause case was measured; this one was measured the
 * other way, and the two are not the same operand.
 */
private fun TrackedFactBase.entityListOperandsOf(
    call: ConditionCall,
    infixCarrier: String?,
    valueListCarriers: Set<String>,
    declaringGroup: String,
    element: TrackedDeclaration,
): List<ListOperand>? {
    val infixEntities = when (call.operator) {
        infixCarrier -> call.splitAtIn()?.second ?: return null
        in valueListCarriers -> call.splitAtIn()?.takeIf { it.second.isWrittenOutValueList() }?.first ?: return null
        else -> null
    }
    if (infixEntities != null) {
        return infixEntities.map { text ->
            if (!isBareEntitySpec(text)) return null
            referencesIn(text, conditionLanguage).singleOrNull()?.let { ListOperand(it, "") } ?: return null
        }
    }
    // By CATALOG ID, never by the literal: `For` is `Fuer` in German and a bare literal would read a German
    // model's field of that name as a key — the same reasoning `repetitionScopeGroupFindings` states for
    // `@From`, which is a PATH_OP alongside this one and means something else entirely.
    val indexKeyword = CONDITION_OPERATOR_KEYWORDS[conditionLanguage]?.get(SEMANTIC_INDEX)
    return call.operands.map { operand ->
        if (operand.clauses.isEmpty()) {
            return@map ListOperand(operand.path ?: return null, "")
        }
        val keyed = operand.clauses.any { it.keyword == indexKeyword }
        // A key A12 refuses answers ahead of every list gate, the duplicates included ([KERNEL-FINDINGS KF476]),
        // so a list holding one is the key's to report; only a key the shared reader honours leaves them to answer.
        if (keyed && operandReferenceIn(operand.text, declaringGroup, element) { null } == null) {
            return null
        }
        ListOperand(
            operand.base ?: return null,
            operand.clauses.joinToString("|") { "${it.keyword} ${it.text}" },
            indexSelected = keyed,
        )
    }
}

/**
 * The index of the first identity that repeats an earlier one, or null.
 *
 * A starred reference is skipped exactly as `checkDuplicatesInDirectRefs` skips one, so the index this
 * returns is into the FULL list rather than into the unstarred part of it. An operand this reader could not
 * reduce is skipped for itself alone.
 */
private fun List<String>.firstDirectDuplicate(written: List<ListOperand>): Int? {
    val seen = mutableSetOf<String>()
    indices.forEach { at ->
        val operand = written[at]
        if ('*' !in operand.spec && !seen.add("${this[at]}\u0000${operand.key}")) {
            return at
        }
    }
    return null
}

/**
 * The index of the first entity lying under another of the same list, or null.
 *
 * **Containment holds between IDENTICALLY keyed operands only.** An index-selected ancestor does not contain
 * a differently-keyed descendant — `Lines For "A"` beside `Lines/Amount For "B"` is admitted, while the same
 * pair under one key is `MVK_DUPLICATE_PARAM2` (both measured at the kernel 2026-09-20). Equality is the
 * whole test: where the keys merely differ this stays silent rather than deciding what the difference means,
 * which under-reports and is the safe direction.
 */
private fun List<String>.firstNestedEntity(written: List<ListOperand>): Int? =
    indices.firstOrNull { at ->
        indices.any { other ->
            other != at && written[other].key == written[at].key &&
                this[at].startsWith("${this[other]}/")
        }
    }

/**
 * Whether a duplicate or root-group gate answers this list before its operator asks anything.
 *
 * The shared home for the two families that judge an entity list further on: both are asked while the list
 * is BUILT, so a family naming its own class on such a list would name the wrong one on a correct rejection.
 * The root-group arm is read as PLAIN here because a family that yields on a root group is silent either
 * way — under-reporting where the caller asks no gate, never over-reporting.
 */
internal fun entityListPreGateAnswers(written: List<String>, targets: List<String>): Boolean {
    // These callers reduce their own operands to plain specs before asking, so every identity here carries an
    // empty key — which is what makes the arms compare on path alone, exactly as they did for every list
    // before clause-carrying operands became readable.
    val operands = written.map { ListOperand(it, "") }
    return targets.firstDirectDuplicate(operands) != null ||
        targets.firstNestedEntity(operands) != null ||
        targets.any { it.isRootGroup() }
}
