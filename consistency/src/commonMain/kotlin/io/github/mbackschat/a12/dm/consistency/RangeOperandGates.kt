package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.ConditionCall
import io.github.mbackschat.a12.dm.consistency.syntax.OPERATOR_SPELLINGS
import io.github.mbackschat.a12.dm.consistency.syntax.OperatorId

/**
 * What the two SUBSTRING operators demand of the field they cut, and of the interval that cuts it.
 *
 * `RangeAsString` and `RangeAsNumber` are one kernel method (`CheckFeldOpMitParamsImpl.check`), so their six
 * classes form a single chain ([KERNEL-FINDINGS KF405], `rulekit.validate.laws.RangeOperandGateLawsTest`):
 *
 * | the gate, in order | class |
 * |---|---|
 * | the operand names a group | `MVK_NO_GROUPS_ALLOWED` |
 * | it carries a repetition star | `MVK_NO_WILDCARDS_ALLOWED` |
 * | it reads a category | `MVK_CATEGORY_NOT_ALLOWED` |
 * | the interval is not `1 <= start <= end` | `MVK_INVALID_INTERVAL` |
 * | the field is neither text nor a custom type | `MVK_INVALID_TYPE` |
 * | the field waives value validation | `MVK_INVALID_RAW_TYPE` |
 *
 * **The interval answers before either gate that reads the declaration**, which is the half of the order a
 * reader is likeliest to invert: the operand is written first, so it looks like the first thing judged. The
 * interval is arithmetic on the written text and needs nothing from the model at all.
 *
 * **The kind gate takes what `FieldUtils.isLikeString` takes, which is two interfaces and no enumeration.**
 * The kernel's own javadoc for this method lists "strings, predefined types or non-closed enumerations", and
 * the third of those is a FLAG on a string declaration rather than a kind — so an extensible enum passes as
 * the string it is, and a closed `EnumerationType` does not pass at all. Both are measured, because reading
 * that list as three kinds is what the wording invites.
 *
 * **The gate reads the FIELD, never the operator's result kind**: `RangeAsNumber` produces a number and still
 * refuses a number field, measured on the same fixture as the text carrier so the two operators are one law
 * rather than two that happen to agree.
 *
 * **Silent where the read ITERATES.** An unstarred read into a repetition draws `MVK_NO_WILDCARD` from the
 * gate outside this method, and it pre-empts all six classes — the interval included, which needs no model.
 * Measured, so this family declines such an operand rather than naming the class its own chain would give.
 *
 * **Silent on an unresolvable type definition or an `UnspecifiedType` field**, whose type this reader cannot know, and on an interval whose bounds are not both written as integers.
 */
internal fun TrackedFactBase.rangeOperandGateFindings(): List<Finding> = declarations().flatMap { element ->
    rangeOperandContributions(element).map { it.finding }
}

internal fun TrackedFactBase.rangeOperandContributions(
    element: TrackedDeclaration,
    execution: ConditionExecution? = conditionExecution(element),
): List<ConditionFinding> {
    val keywords = OPERATOR_SPELLINGS[conditionLanguage] ?: return emptyList()
    val carriers = RANGE_OPERATORS.mapNotNull { keywords[it] }.toSet()
    if (carriers.isEmpty()) {
        return emptyList()
    }
    val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
    return callContributions(element, execution) { member, call ->
        if (call.operator !in carriers) return@callContributions null
        rangeDefect(call, group, element)?.let { finding(it, call, element, member) }
    }
}

/** Both substring operators; one kernel method checks them, so one family owns both. */
private val RANGE_OPERATORS = listOf(OperatorId.RANGE_AS_STRING, OperatorId.RANGE_AS_NUMBER)

/** The operand, the interval's start and its end — A12 parses the last two as integers unconditionally. */
private const val RANGE_ARITY = 3

private class SubstringDefect(val code: String, val detail: String)

/** The first gate this call fails, in A12's own order, or null where none fails and where it may not judge. */
private fun TrackedFactBase.rangeDefect(
    call: ConditionCall,
    declaringGroup: String,
    element: TrackedDeclaration,
): SubstringDefect? {
    if (call.operands.size != RANGE_ARITY) {
        return null
    }
    val read = soleFieldReadOf(call.operands[0], declaringGroup, element, call.inAFilterBody) ?: return null
    val target = read.target
    val declared = when (read) {
        is SoleFieldRead.Group ->
            return SubstringDefect("MVK_NO_GROUPS_ALLOWED", "cuts `$target`, which is a group — cut one field of it")
        is SoleFieldRead.Starred -> return SubstringDefect(
            "MVK_NO_WILDCARDS_ALLOWED",
            "cuts `$target` through a repetition star, and it cuts a single value — let the rule iterate " +
                "the repeating group instead",
        )
        is SoleFieldRead.Categorized -> return SubstringDefect(
            "MVK_CATEGORY_NOT_ALLOWED",
            "cuts the category `${read.category}` of `$target`, and it cuts the field's own value",
        )
        is SoleFieldRead.Field -> read.declared
    }
    intervalDefect(call)?.let { return it }
    val type = effectiveFieldType(declared) ?: return null
    if (type in PLAINLY_NON_STRING_TYPES) {
        return SubstringDefect(
            "MVK_INVALID_TYPE",
            "cuts `$target`, declared `$type` — it cuts a text or a custom type, and the extensible-enum " +
                "spelling its kernel doc mentions is a flag on a text declaration rather than a kind",
        )
    }
    return if (waivesValueValidation(declared)) {
        SubstringDefect(
            "MVK_INVALID_RAW_TYPE",
            "cuts `$target`, which waives value validation — A12 holds its content unparsed and has no " +
                "positions to count",
        )
    } else {
        null
    }
}

/**
 * The interval's own class, or null where it is sound and where this reader cannot read both bounds.
 *
 * A12 parses both with `Integer.parseInt` and refuses `begin < 1 || begin > ende`, so an equal pair is a
 * one-character cut and admitted. A bound this reader cannot read as an integer is one the grammar would
 * already have refused, so there is nothing here to judge.
 */
private fun intervalDefect(call: ConditionCall): SubstringDefect? {
    val begin = call.operands[1].text.javaTrimmed().javaIntOrNull() ?: return null
    val end = call.operands[2].text.javaTrimmed().javaIntOrNull() ?: return null
    return if (begin < 1 || begin > end) {
        SubstringDefect(
            "MVK_INVALID_INTERVAL",
            "cuts positions $begin to $end, and the cut is 1-based and inclusive — start at 1 or later and " +
                "no later than the end",
        )
    } else {
        null
    }
}

private fun finding(
    defect: SubstringDefect,
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
