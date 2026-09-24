package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionCall
import io.github.mbackschat.a12.dm.consistency.syntax.HAVING
import io.github.mbackschat.a12.dm.consistency.syntax.callsIn
import io.github.mbackschat.a12.dm.consistency.syntax.referencesIn
import io.github.mbackschat.a12.dm.consistency.syntax.splitAtIn

/**
 * How many entities each entity-list operator's argument list needs.
 *
 * Six arity gates live in `CheckEntityListenUtils`, and which one an operator calls is fixed per operator by
 * a switch in its construct class ([KERNEL-FINDINGS KF381],
 * `rulekit.validate.laws.EntityListArityLawsTest`). They are not variations on one predicate, which is the
 * whole reason this is a table rather than a rule of thumb.
 *
 * | the law | refuses | class |
 * |---|---|---|
 * | exactly one entity | any other count | `MVK_PARAMSIZE_INVALID1` |
 * | exactly two entities | any other count | `MVK_PARAMSIZE_INVALID_2` |
 * | more than one FIELD, or a group | a lone unstarred field | `MVK_PARAMSIZE_INVALIDN` |
 * | the same, under `DateRangesOverlap` | a lone unstarred field | `MVK_PARAMSIZE_INVALIDFN` |
 * | more than one ENTITY | a lone unstarred entity of any kind | `MVK_PARAMSIZE_INVALID2` |
 * | the same, under `NumberOfFilledGroups` | a lone unstarred entity of any kind | `MVK_PARAMSIZE_INVALIDGN` |
 *
 * **A lone GROUP passes the third law and fails the fifth**, which is the difference the two predicates
 * turn on and the reason reading them all as "at least two" would refuse models A12 admits: `AllFieldsFilled`
 * takes one group, `AllGroupsFilled` does not.
 *
 * **A wildcard satisfies the fifth law and not the third.** `checkIsMoreThanOneEntity` asks whether the lone
 * entity is a FIELD at all, so a star changes nothing for a group there; `isMoreThanOneParameter` asks only
 * whether it carries one. Both measured, and the starred row for the group conditions is measured on
 * `NumberOfFilledGroups` because `AllGroupsFilled` follows its arity gate with a wildcard gate that answers
 * `MVK_NO_WILDCARDS_G_ALLOWED` first.
 *
 * **`MVK_PARAMSIZE_INVALID2` and `MVK_PARAMSIZE_INVALID_2` differ only in an underscore** and are different
 * laws on different operators, so each is measured on its own carrier rather than inferred from the other.
 *
 **Three operators ask about KIND before they ask about count**, so a list of the wrong kind is answered
 * there whatever its length: `SumOfProducts` and `DateRangesOverlap` refuse a group with
 * `MVK_NO_GROUPS_ALLOWED`, and `NumberOfFilledGroups` refuses a field with `MVK_NO_FIELDS_ALLOWED` — all
 * three measured, each on a list whose count is separately wrong, so the row cannot be explained by the
 * arity gate having passed.
 *
 * **An operand crossing an unstarred repetition never reaches its operator's arity gate**, the reference
 * reader refusing it while the list is built — `MVK_NO_WILDCARD` where the error field sits outside that
 * group and `MVK_WILDCARDS_REQUIRED` where it sits inside. Measured by the corpus mutation that removes a
 * star from an aggregate operand, which is exactly the shape a lone-field arity reading would claim.
 *
 * **Nor does a FILTERED operand, whatever it crosses**, for the same reason one layer along:
 * `checkFilterCondition` judges it while the list is built, so a lone filtered field draws the filter's
 * `MVK_WILDCARDS_REQUIRED` and never the arity class. That one is not a repetition shape at all — a lone
 * filtered field on a NON-repeating path draws it too — so the rule above does not cover it, and the two
 * were reported together until it was measured (2026-09-15, [filteredOperandKindFindings]).
 *
 * **Silent wherever a gate ahead of the arity one answers**: a repeated entity is `MVK_DUPLICATE_PARAM1`, a
 * root group `MVK_ROOT_GROUP_REFERENCED`, a category selector `MVK_CATEGORY_NOT_ALLOWED`, and an entity no
 * reader can resolve `MVK_INVALID_ENTITY` — all of them built or checked while the reference list is, before
 * any operator's own switch is reached. The first two are [entityListPreGateFindings]', which is why the
 * shared reader this family yields to is that family's; they are a silence here, not in the report.
 */
internal fun TrackedFactBase.entityListArityFindings(): List<Finding> {
    val keywords = CONDITION_OPERATOR_KEYWORDS[conditionLanguage] ?: return emptyList()
    val laws = ARITY_OF.mapNotNull { (id, law) -> keywords[id]?.let { it to law } }.toMap()
    if (laws.isEmpty()) {
        return emptyList()
    }
    val filterKeyword = keywords[HAVING]
    return declarations().mapNotNull { element ->
        val body = element.body ?: return@mapNotNull null
        val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
        conditionTexts(element.kind, body).firstNotNullOfOrNull { (member, text) ->
            callsIn(text, conditionLanguage, syntaxEntryOf(member))
                .firstNotNullOfOrNull { call ->
                    laws[call.operator]?.let { law ->
                        arityFinding(call, law, filterKeyword, group, element, member)
                    }
                }
        }
    }
}

/** One entity-list arity law: what the operator's list must contain, and what it draws when it does not. */
private enum class ArityLaw(val code: String, val wants: String, val kindFirst: DeclaredKind? = null) {
    EXACTLY_ONE("MVK_PARAMSIZE_INVALID1", "exactly one entity"),
    EXACTLY_TWO("MVK_PARAMSIZE_INVALID_2", "exactly two entities", DeclaredKind.FIELD),
    MORE_THAN_ONE_FIELD_OR_A_GROUP("MVK_PARAMSIZE_INVALIDN", "more than one field, or one group"),
    MORE_THAN_ONE_FIELD("MVK_PARAMSIZE_INVALIDFN", "more than one field", DeclaredKind.FIELD),
    MORE_THAN_ONE_ENTITY("MVK_PARAMSIZE_INVALID2", "more than one entity, or one starred"),
    MORE_THAN_ONE_GROUP("MVK_PARAMSIZE_INVALIDGN", "more than one group, or one starred", DeclaredKind.GROUP),
}

/**
 * Which law each operator's construct class calls, by catalog id.
 *
 * `AtLeastOneFieldFilled`, `NoFieldFilled`, `AtLeastOneGroupFilled` and `NoGroupFilled` are deliberately
 * absent: their arms of the same switches call no arity gate at all.
 */
private val ARITY_OF = mapOf(
    "FieldFilled" to ArityLaw.EXACTLY_ONE,
    "FieldNotFilled" to ArityLaw.EXACTLY_ONE,
    "GroupFilled" to ArityLaw.EXACTLY_ONE,
    "GroupNotFilled" to ArityLaw.EXACTLY_ONE,
    "SumOfProducts" to ArityLaw.EXACTLY_TWO,
    "AllFieldsFilled" to ArityLaw.MORE_THAN_ONE_FIELD_OR_A_GROUP,
    "FieldsNotCollectivelyFilled" to ArityLaw.MORE_THAN_ONE_FIELD_OR_A_GROUP,
    "NotAllFieldsFilled" to ArityLaw.MORE_THAN_ONE_FIELD_OR_A_GROUP,
    "NotExactlyOneFieldFilled" to ArityLaw.MORE_THAN_ONE_FIELD_OR_A_GROUP,
    "MoreThanOneFieldFilled" to ArityLaw.MORE_THAN_ONE_FIELD_OR_A_GROUP,
    "FieldValuesNotUnique" to ArityLaw.MORE_THAN_ONE_FIELD_OR_A_GROUP,
    "MaxValue" to ArityLaw.MORE_THAN_ONE_FIELD_OR_A_GROUP,
    "MinValue" to ArityLaw.MORE_THAN_ONE_FIELD_OR_A_GROUP,
    "Sum" to ArityLaw.MORE_THAN_ONE_FIELD_OR_A_GROUP,
    "FirstFilledValue" to ArityLaw.MORE_THAN_ONE_FIELD_OR_A_GROUP,
    "NumberOfFilledFields" to ArityLaw.MORE_THAN_ONE_FIELD_OR_A_GROUP,
    "NumberOfDifferentValues" to ArityLaw.MORE_THAN_ONE_FIELD_OR_A_GROUP,
    "NumberOfValueInFields" to ArityLaw.MORE_THAN_ONE_FIELD_OR_A_GROUP,
    "DateRangesOverlap" to ArityLaw.MORE_THAN_ONE_FIELD,
    "AllGroupsFilled" to ArityLaw.MORE_THAN_ONE_ENTITY,
    "GroupsNotCollectivelyFilled" to ArityLaw.MORE_THAN_ONE_ENTITY,
    "NotAllGroupsFilled" to ArityLaw.MORE_THAN_ONE_ENTITY,
    "NumberOfFilledGroups" to ArityLaw.MORE_THAN_ONE_GROUP,
)

private fun TrackedFactBase.arityFinding(
    call: ConditionCall,
    law: ArityLaw,
    filterKeyword: String?,
    declaringGroup: String,
    element: TrackedDeclaration,
    member: String,
): Finding? {
    // A FILTERED operand is judged by `checkFilterCondition` while the reference list is built, so the call
    // never reaches its arity gate: an unstarred one draws the filter's own `MVK_WILDCARDS_REQUIRED` (or the
    // iteration gate's class where it crosses a repetition), and naming the arity class beside it would add a
    // second, wrong repair. Measured 2026-09-15 on a lone filtered field, which a lone-field arity reading
    // claims outright (`rulekit.validate.laws.AggregateSlotLawsTest`).
    if (call.operands.any { operand -> operand.clauses.any { it.keyword == filterKeyword } }) {
        return null
    }
    // `NumberOfValueInFields` puts its constant ahead of an infix `In`, so the entity list is what follows
    // it; every other carrier here lists its entities as the call's own operands.
    val texts = call.splitAtIn()?.second ?: call.operands.map { it.base ?: it.text }
    val written = texts.map { referencesIn(it, conditionLanguage).singleOrNull() ?: return null }
    val targets = written.map { addressedTargetOf(it, declaringGroup, element) ?: return null }
    if (entityListPreGateAnswers(written, targets)) {
        return null
    }
    val kinds = targets.map { target -> declarationAt(target)?.declaredKind }
    // Three of the six operators run a KIND gate before their arity one, so a list of the wrong kind is
    // answered there — `MVK_NO_GROUPS_ALLOWED` or `MVK_NO_FIELDS_ALLOWED` — however wrong its count is.
    if (law.kindFirst != null && kinds.any { it != law.kindFirst }) {
        return null
    }
    val lone = written.singleOrNull()
    val loneIsAField = lone != null && kinds.first() == DeclaredKind.FIELD
    val refused = when (law) {
        ArityLaw.EXACTLY_ONE -> written.size != 1
        ArityLaw.EXACTLY_TWO -> written.size != 2
        ArityLaw.MORE_THAN_ONE_FIELD_OR_A_GROUP, ArityLaw.MORE_THAN_ONE_FIELD ->
            lone != null && loneIsAField && '*' !in lone
        ArityLaw.MORE_THAN_ONE_ENTITY, ArityLaw.MORE_THAN_ONE_GROUP -> lone != null && '*' !in lone
    }
    if (!refused) {
        return null
    }
    return Finding(
        FindingCategory.A12_CONSTRAINT,
        FindingSeverity.ERROR,
        law.code,
        "in `$member`: `${call.operator}` takes ${law.wants} and this call lists " +
            "${written.size} — ${written.joinToString { "`$it`" }}",
        element.path,
    )
}
