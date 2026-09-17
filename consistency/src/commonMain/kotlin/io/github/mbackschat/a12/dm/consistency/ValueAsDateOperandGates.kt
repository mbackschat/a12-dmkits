package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionCall
import io.github.mbackschat.a12.dm.consistency.syntax.callsIn

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
 * **Silent on a `TypeDefType` or `UnspecifiedType` field**, whose effective type this reader does not
 * resolve — a typedef may well resolve to a partially known date, and refusing it would be the one
 * direction this checker may not take.
 */
internal fun TrackedFactBase.valueAsDateOperandFindings(): List<Finding> {
    val carrier = CONDITION_OPERATOR_KEYWORDS[conditionLanguage]?.get(VALUE_AS_DATE) ?: return emptyList()
    return declarations().flatMap { element ->
        val body = element.body ?: return@flatMap emptyList()
        val declaringGroup = element.path.substringBeforeLast('/', missingDelimiterValue = "")
        conditionTexts(element.kind, body).flatMap { (member, text) ->
            callsIn(text, conditionLanguage, syntaxEntryOf(member))
                .filter { it.operator == carrier }
                .mapNotNull { call ->
                    valueAsDateDefect(call, declaringGroup, element)?.let { finding(it, call, element, member) }
                }
        }
    }
}

private const val VALUE_AS_DATE = "ValueAsDate"

/** The field to resolve and the day to resolve it to — A12's grammar gives the operator no other shape. */
private const val VALUE_AS_DATE_ARITY = 2

private class PartialDateDefect(val code: String, val detail: String)

private const val DATE_TYPE = "DateType"

/**
 * The declarations this reader is CERTAIN A12's kind gate refuses, all eleven measured.
 *
 * An allow-list rather than the complement of one kind, so a `TypeDefType` or `UnspecifiedType` — whose
 * effective type this checker does not resolve — is left unjudged. `DateType` is a member: a date field with
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
    val (written, category) = operandSpec(call.operands[0]) ?: return null
    val target = addressedTargetOf(written, declaringGroup, element) ?: return null
    val declared = allDeclarations().firstOrNull { it.path == target } ?: return null
    val starred = '*' in written
    if (declared.declaredKind == DeclaredKind.GROUP) {
        // A repeatable group read without its star iterates, and the reference reader answers there; the
        // starred spelling is the one measured against this family's own group class.
        return if (declared.isRepeatable && !starred) {
            null
        } else {
            PartialDateDefect(
                "MVK_NO_GROUPS_ALLOWED",
                "resolves `$target`, which is a group — name the partially known date field inside it",
            )
        }
    }
    if (declared.declaredKind != DeclaredKind.FIELD) {
        return null
    }
    if (starred) {
        return PartialDateDefect(
            "MVK_NO_WILDCARDS_ALLOWED",
            "resolves `$target` through a repetition star, and it resolves a single value — let the rule " +
                "iterate the repeating group instead",
        )
    }
    if (crossesARepetitionOutside(declaringGroup, target)) {
        return null
    }
    if (category != null) {
        return PartialDateDefect(
            "MVK_CATEGORY_NOT_ALLOWED",
            "resolves the category `$category` of `$target`, and it resolves the field's own value",
        )
    }
    return partialDateDefect(declared, target)
}

/** The kind gate's own verdict: A12's `isDatePartialKnown`, read off the stored declaration. */
private fun partialDateDefect(declared: TrackedDeclaration, target: String): PartialDateDefect? {
    val type = declared.declaredFieldType() ?: return null
    if (type == DATE_TYPE && declaresAPartialPrecision(declared)) {
        return null
    }
    if (type !in PLAINLY_NOT_A_PARTIAL_DATE) {
        return null
    }
    val because = if (type == DATE_TYPE) {
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
)
