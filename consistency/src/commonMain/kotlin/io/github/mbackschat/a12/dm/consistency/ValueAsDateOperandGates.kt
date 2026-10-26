package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionCall

/**
 * What `ValueAsDate` demands of the field it resolves, and in what order.
 *
 * `CheckFeldOpMitDatumSpecialImpl.check` runs four gates over one operand and every one of them throws, so
 * the classes form a chain ([KERNEL-FINDINGS KF408],
 * `rulekit.validate.laws.ValueAsDateOperandGateLawsTest`):
 *
 * | the gate, in order | class |
 * |---|---|
 * | the operand names a group | `MVK_NO_GROUPS_ALLOWED` |
 * | it carries a repetition star | `MVK_NO_WILDCARDS_ALLOWED` |
 * | it reads a category | `MVK_CATEGORY_NOT_ALLOWED` |
 * | the field is not a partially known date | `MVK_INVALID_DATE_TYPE_FOR_OP` |
 *
 * **The kind gate is `FieldUtils.isDatePartialKnown`, which is two questions and not one**: the declaration
 * is an `IDateType`, and its `partlyKnownDate()` is not `BEKANNT`. A12's ADO layer gives `TimeType`,
 * `DateTimeType` and `DateFragmentType` the same interface a plain date carries, so all three pass the first
 * question and fail the second — and the operator has no second class, so every refused declaration draws
 * the one code whatever the reason. Measured over the whole declared-type domain, which is why this reader
 * can key on `datePrecision` alone and still agree with A12 on eleven kinds.
 *
 * **The group gate answers even on the ROOT group**, measured — predicted `MVK_ROOT_GROUP_REFERENCED` and
 * refuted. That class belongs to the operators that ACCEPT a group and exclude the root one; a method with
 * no group arm at all never reaches it.
 *
 * **Silent where the read ITERATES.** An unstarred read into a repetition the declaring rule does not sit
 * inside draws `MVK_NO_WILDCARD` from the reference reader outside this method, and it pre-empts all four
 * classes. The same read from the row's OWN rule reaches the gates, measured, so the anchoring is what
 * decides rather than the operand's spelling.
 *
 * **Silent on an unresolvable type definition or an `UnspecifiedType` field**, whose type this reader cannot know — either may
 * well be a partially known date, and refusing it would be the one
 * direction this checker may not take.
 */
internal fun TrackedFactBase.valueAsDateOperandFindings(): List<Finding> = declarations().flatMap { element ->
    valueAsDateContributions(element).map { it.finding }
}

internal fun TrackedFactBase.valueAsDateContributions(
    element: TrackedDeclaration,
    execution: ConditionExecution? = conditionExecution(element),
): List<ConditionFinding> {
    val carrier = CONDITION_OPERATOR_KEYWORDS[conditionLanguage]?.get(VALUE_AS_DATE) ?: return emptyList()
    val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
    return callContributions(element, execution) { member, call ->
        if (call.operator != carrier) return@callContributions null
        valueAsDateDefect(call, group, element)?.let { finding(it, call, element, member) }
    }
}

private const val VALUE_AS_DATE = "ValueAsDate"

/** The field to resolve and the day to resolve it to — A12's grammar gives the operator no other shape. */
private const val VALUE_AS_DATE_ARITY = 2

private class PartialDateDefect(val code: String, val detail: String)

/**
 * The declarations this reader is CERTAIN A12's kind gate refuses, all eleven measured.
 *
 * An allow-list rather than the complement of one kind, so an unresolvable type definition or an `UnspecifiedType` — whose type this
 * checker cannot know — is left unjudged. `DateType` is a member: a date field with
 * no partial precision is refused exactly like the kinds that carry none at all, and the one date that
 * passes has already returned before this set is consulted.
 */
private val PLAINLY_NOT_A_PARTIAL_DATE = PLAINLY_NON_DATE_TYPES + TEMPORAL_DECLARED_TYPES

/** The first gate this call fails, in A12's own order, or null where none fails and where it may not judge. */
private fun TrackedFactBase.valueAsDateDefect(
    call: ConditionCall,
    declaringGroup: String,
    element: TrackedDeclaration,
): PartialDateDefect? {
    if (call.operands.size != VALUE_AS_DATE_ARITY) {
        return null
    }
    val read = soleFieldReadOf(call.operands[0], declaringGroup, element, call.inAFilterBody) ?: return null
    val target = read.target
    return when (read) {
        is SoleFieldRead.Group -> PartialDateDefect(
            "MVK_NO_GROUPS_ALLOWED",
            "resolves `$target`, which is a group — name the partially known date field inside it",
        )
        is SoleFieldRead.Starred -> PartialDateDefect(
            "MVK_NO_WILDCARDS_ALLOWED",
            "resolves `$target` through a repetition star, and it resolves a single value — let the rule " +
                "iterate the repeating group instead",
        )
        is SoleFieldRead.Categorized -> PartialDateDefect(
            "MVK_CATEGORY_NOT_ALLOWED",
            "resolves the category `${read.category}` of `$target`, and it resolves the field's own value",
        )
        is SoleFieldRead.Field -> partialDateDefect(read.declared, target)
    }
}

/** The kind gate's own verdict: A12's `isDatePartialKnown`, read off the type the field is declared by. */
private fun TrackedFactBase.partialDateDefect(declared: TrackedDeclaration, target: String): PartialDateDefect? {
    val type = effectiveFieldType(declared) ?: return null
    if (type == FieldType.DATE && hasAPartialPrecision(declared)) {
        return null
    }
    if (type !in PLAINLY_NOT_A_PARTIAL_DATE) {
        return null
    }
    val because = if (type == FieldType.DATE) {
        "declared `$type` at the FULL default precision — the operator exists to resolve a date whose parts " +
            "may be unknown, so declare `datePrecision` DAY_OPTIONAL, MONTH_OPTIONAL or YEAR_OPTIONAL on it, " +
            "or compare the field directly"
    } else {
        "declared `$type`, and only a `DateType` carries a `datePrecision` — the other temporal kinds share " +
            "A12's date interface and still fail this gate"
    }
    return PartialDateDefect(
        "MVK_INVALID_DATE_TYPE_FOR_OP",
        "resolves `$target`, $because",
    )
}

private fun finding(
    defect: PartialDateDefect,
    call: ConditionCall,
    element: TrackedDeclaration,
    member: String,
): Finding = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    defect.code,
    "in `$member`: `${call.operator}` ${defect.detail}",
    element.path,
    call.spanIn(member),
)
