package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.ConditionCall
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionLanguage
import io.github.mbackschat.a12.dm.consistency.syntax.splitAtInfix

/**
 * `ValueNotConsistent`'s first operand must be an enumeration WITH A TABLE REFERENCE, and no stored model
 * has one.
 *
 * [MVK-LEDGER](../../../../../../../../../../docs/MVK-LEDGER.md) carried six codes of this family on one sentence — table-backed enums are not
 * DM-JSON-serializable, so the construct is un-buildable — and the sentence is right about five of them and
 * INVERTED on the sixth. `CheckFieldAndValueListConditionImpl.check` asks `checkField` first, and that call
 * ends in `CheckEntityUtils.checkEnumWithTableReference`, which raises `MVK_TABLE_ENUM_EXPECTED` when the
 * first operand is **not** a table enum. The absence of a table enum is therefore not what makes the code
 * unreachable; it is exactly what fires it, on every model that writes this operator at all
 * ([KERNEL-FINDINGS KF422](../../../../../../../../../../docs/KERNEL-FINDINGS.md#kf422),
 * `rulekit.validate.laws.TableEnumConditionLawsTest`).
 *
 * **The order is measured, and it decides which class this family may name.** A group first operand draws
 * `MVK_NO_GROUPS_ALLOWED` and a starred one `MVK_NO_WILDCARDS_ALLOWED`, both from checks ahead of the
 * table-enum demand, so those are reported as themselves rather than folded into it. Only a plain, resolvable
 * FIELD reaches the gate this family is named for — and then it always fails it, whatever the field's kind:
 * a string, a number and an ordinary enumeration draw the same class.
 *
 * **What it leaves alone**, under-reporting. The five codes BEHIND the gate —
 * `MVK_DEPENDENT_COLUMN_SIZE_MISMATCH`, `MVK_ONLY_ENUMS_ALLOWED`, `MVK_STRING_VALUE_NOT_IN_COLUMN`,
 * `MVK_ENUM_VALUES_NOT_IN_COLUMN` and `MVK_INVALID_TABLE_ENUM` — need the stored table enum that
 * [KF366](../../../../../../../../../../docs/KERNEL-FINDINGS.md#kf366) measured A12's own READER refusing, so no model this checker is given can
 * reach them. An operand this reader cannot resolve is left to A12 as well: an unknown path draws
 * `MVK_INVALID_ENTITY` from the reference gate ahead of everything here.
 */
internal fun TrackedFactBase.tableEnumOperandFindings(): List<Finding> = declarations().flatMap { element ->
    tableEnumContributions(element).take(1).map { it.finding }
}

internal fun TrackedFactBase.tableEnumContributions(
    element: TrackedDeclaration,
    execution: ConditionExecution? = conditionExecution(element),
): List<ConditionFinding> {
    val carrier = VALUE_NOT_CONSISTENT_SPELLING[conditionLanguage] ?: return emptyList()
    val infix = TO_SPELLING[conditionLanguage] ?: return emptyList()
    val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
    return callContributions(element, execution) { member, call ->
        if (call.operator == carrier) subjectDefect(call, infix, group, element, member) else null
    }
}

/**
 * The operator's two spellings, held here rather than read from `CONDITION_OPERATOR_KEYWORDS`.
 *
 * That map carries only CATALOGED operators, and this one cannot be cataloged: every example of it a catalog
 * entry would have to publish is refused, the catalog's examples being CI-executed against the Kernel. Both
 * spellings are listed as terminals in the generated `CONDITION_KEYWORDS`, which is what
 * `ConditionKeywordProjectionTest` measures against the Kernel's own bundle.
 */
private val VALUE_NOT_CONSISTENT_SPELLING = mapOf(
    ConditionLanguage.EN to "ValueNotConsistent",
    ConditionLanguage.DE to "WertNichtKonsistent",
)

/** The infix separating the subject from its value list — `toOp` in the grammar, one word per language. */
private val TO_SPELLING = mapOf(ConditionLanguage.EN to "To", ConditionLanguage.DE to "Zu")

private const val TABLE_ENUM_EXPECTED = "MVK_TABLE_ENUM_EXPECTED"

private const val NO_GROUPS = "MVK_NO_GROUPS_ALLOWED"

private const val NO_WILDCARDS = "MVK_NO_WILDCARDS_ALLOWED"

/**
 * What A12 answers about this call's SUBJECT — the operand before the infix — or null where it answers
 * nothing this family owns.
 *
 * The three classes are returned in the kernel's own order (`checkIsField`, `checkNoWildCards`, then the
 * table-enum demand), because naming a later class on a call the earlier gate refuses would be the wrong
 * class on a correct rejection.
 */
private fun TrackedFactBase.subjectDefect(
    call: ConditionCall,
    infix: String,
    declaringGroup: String,
    element: TrackedDeclaration,
    member: String,
): Finding? {
    val written = call.splitAtInfix(infix)?.first?.singleOrNull() ?: return null
    val reference = singleReferenceOf(written, declaringGroup, element) ?: return null
    val resolved = resolveEntity(declaringGroup, reference.replace("*", ""), element.origin)
    val path = (resolved as? Reference.Resolved)?.path ?: return null
    val target = declarationAt(path) ?: return null
    if (target.declaredKind == DeclaredKind.GROUP) {
        return finding(
            NO_GROUPS,
            "in `$member`: `${call.operator}` checks one field's value against a table, and `$written` " +
                "names a group — name the field itself",
            element,
            call.spanIn(member),
        )
    }
    if ('*' in reference) {
        return finding(
            NO_WILDCARDS,
            "in `$member`: `${call.operator}` takes one field and not a repetition list, so `$written` " +
                "may not carry a `*`",
            element,
            call.spanIn(member),
        )
    }
    return finding(
        TABLE_ENUM_EXPECTED,
        "in `$member`: `${call.operator}` requires an enumeration with a TABLE reference as its subject, " +
            "and `$written` is not one — no model A12 can read declares a table-backed enumeration, so " +
            "this operator has no admissible subject; express the check another way",
        element,
        call.spanIn(member),
    )
}

private fun finding(code: String, explain: String, element: TrackedDeclaration, span: TextSpan): Finding =
    Finding(FindingCategory.A12_CONSTRAINT, FindingSeverity.ERROR, code, explain, element.path, span)
