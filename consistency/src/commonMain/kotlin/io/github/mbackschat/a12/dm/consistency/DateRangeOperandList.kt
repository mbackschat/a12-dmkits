package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionCall
import io.github.mbackschat.a12.dm.consistency.syntax.splitAtIn

/**
 * The two questions a range-overlap operator asks of the ranges it compares.
 *
 * `CheckEntityListenUtils.checkDateRangeWithoutSpecificInterpretationOfYear` and
 * `checkDateRangeConsistentRegardingYear` are called back to back by both carriers, so `DateRangesOverlap`'s
 * plain list and `AtLeastOneDateRangeOverlaps`' subject-plus-values list are judged by the same two walks
 * ([KERNEL-FINDINGS KF401], `rulekit.validate.laws.DateRangeOperandListLawsTest`):
 *
 * | the walk | the gate | class |
 * |---|---|---|
 * | the plain list's operands | one of them names a group | `MVK_NO_GROUPS_ALLOWED` |
 * | per expanded field, in order | it is not a date-range declaration | `MVK_NO_DATE_RANGE` |
 * | | it interprets the year as a `FROM` or a `TO` | `MVK_INVALID_DATE_RANGE_FORMAT` |
 * | then over the whole list | the members disagree about carrying a year | `MVK_DATE_WITH_AND_WITHOUT_YEAR` |
 *
 * **The per-field walk completes before the agreement walk starts**, so a list that is both wrong in kind
 * and inconsistent about the year answers with the kind class — measured in both pairings, because a reader
 * that interleaved them would name the later class on the earlier defect.
 *
 * **A model-level BASE YEAR disables the agreement walk and nothing else.** With one declared, a year-less
 * range takes it and there is nothing left to disagree about; the format walk has no such escape, and the
 * pair of base-year rows is what separates them.
 *
 * **The agreement walk takes each REFERENCE's distinct FORMATS and only then asks which carry a year.** So a
 * reference expanding to two differently-spelled dated formats fails it although both carry a year — the
 * list's own law would admit it, and it is refused on the spelling. Measured on a group of two ranges
 * declared `dd.MM.yyyy` and `yyyy-MM-dd`; a group whose members share one format is admitted.
 *
 * **What comes before these two questions differs between the carriers, and that is most of what this
 * family has to get right.** The plain list asks its own group gate first — reported here, since nothing
 * else reads this carrier — and then the arity gate `ENTITY_LIST_ARITY` owns. The value list instead
 * demands one plain field as its subject and a matching base type from its values, so a wrong KIND on
 * either side is `MVK_VARYING_TYPES_NOT_ALLOWED` and never reaches the range walk at all
 * ([valueListConditionGateFindings] owns those). Two agreeing non-ranges do reach it, which is the row that
 * keeps the pre-emption from reading as "this carrier cannot report the kind class".
 *
 * **Silent where a gate ahead of these answers**: a repeated entity, a root group or a category selector
 * ([listGateAheadOfTheArmAnswers]; the value list's own positions are [valueListConditionGateFindings]'), and
 * on a reference this reader cannot resolve or a `DateRangeType` declaring no format.
 */
internal fun TrackedFactBase.dateRangeOperandListFindings(): List<Finding> = declarations().flatMap { element ->
    dateRangeOperandContributions(element).take(1).map { it.finding }
}

internal fun TrackedFactBase.dateRangeOperandContributions(
    element: TrackedDeclaration,
    execution: ConditionExecution? = conditionExecution(element),
): List<ConditionFinding> {
    val keywords = CONDITION_OPERATOR_KEYWORDS[conditionLanguage] ?: return emptyList()
    val carriers = RANGE_OVERLAP_CARRIERS
        .mapNotNull { (id, carrier) -> keywords[id]?.let { it to carrier } }
        .toMap()
    if (carriers.isEmpty()) {
        return emptyList()
    }
    val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
    return callContributions(element, execution) { member, call ->
        val carrier = carriers[call.operator] ?: return@callContributions null
        rangeListDefect(call, carrier, group, element)?.let { finding(it, call, element, member) }
    }
}

/** Which of the two spellings a carrier takes; they differ only in how the compared list is written. */
private enum class RangeCarrier { PLAIN_LIST, VALUE_LIST }

private val RANGE_OVERLAP_CARRIERS = mapOf(
    "DateRangesOverlap" to RangeCarrier.PLAIN_LIST,
    "AtLeastOneDateRangeOverlaps" to RangeCarrier.VALUE_LIST,
)

/** The two year interpretations A12 refuses here; `STANDARD` is expressed by omitting the key. */
private val YEAR_INTERPRETING = setOf("FROM", "TO")

/** One written reference and the fields A12's `expandToFieldList` takes for it, in declaration order. */
private class ListedReference(val written: String, val fields: List<TrackedDeclaration>)

private class RangeDefect(val code: String, val at: String, val because: String)

/** The first gate this call fails, in A12's own order, or null where none fails and where it may not judge. */
private fun TrackedFactBase.rangeListDefect(
    call: ConditionCall,
    carrier: RangeCarrier,
    declaringGroup: String,
    element: TrackedDeclaration,
): RangeDefect? {
    val listed = when (carrier) {
        RangeCarrier.PLAIN_LIST -> {
            if (listGateAheadOfTheArmAnswers(call, declaringGroup, element)) return null
            groupOperandDefect(call, declaringGroup, element)?.let { return it }
            plainList(call, declaringGroup, element)
        }
        RangeCarrier.VALUE_LIST -> valueList(call, declaringGroup, element)
    } ?: return null
    val fields = listed.flatMap { it.fields }
    if (fields.map { it.path }.distinct().size != fields.size) {
        return null
    }

    // A12 sweeps every expanded field for both per-member gates before it asks about agreement at all.
    fields.forEach { field ->
        val type = effectiveFieldType(field)
        if (type != FieldType.DATE_RANGE) {
            return RangeDefect(
                "MVK_NO_DATE_RANGE", field.path,
                "is declared `$type`, and overlapping ranges needs a date range",
            )
        }
        if (rangeYearInterpretationOf(field) in YEAR_INTERPRETING) {
            return RangeDefect(
                "MVK_INVALID_DATE_RANGE_FORMAT", field.path,
                "reads its year as a `${rangeYearInterpretationOf(field)}` boundary, a declaration " +
                    "this operator refuses — compare ranges that interpret their year the standard way",
            )
        }
    }
    return if (hasBaseYear) null else yearDisagreement(listed)
}

/**
 * The reference whose formats break the list's agreement about carrying a year, or null where none does.
 *
 * The distinct-FORMATS step is A12's and is reproduced rather than simplified: collapsing it to "does this
 * reference carry a year" would admit the group of two differently-spelled dated ranges that A12 refuses.
 */
private fun TrackedFactBase.yearDisagreement(listed: List<ListedReference>): RangeDefect? {
    var carriesYear: Boolean? = null
    listed.forEach { reference ->
        val formats = reference.fields
            .map { dateRangeFormatOf(it) ?: return null }
            .distinct()
        val years = formats.map { 'y' in it }
        if (years.size > 1 || (carriesYear != null && carriesYear != years.first())) {
            return RangeDefect(
                "MVK_DATE_WITH_AND_WITHOUT_YEAR", reference.written,
                if (years.size > 1) {
                    "expands to the formats ${formats.joinToString { "`$it`" }}, and this operator takes " +
                        "one format per reference — name the range fields individually"
                } else {
                    "is declared `${formats.first()}`, where an earlier range in the same call " +
                        "${if (years.first()) "omits" else "carries"} the year — declare a model base " +
                        "year, or spell both the same way"
                },
            )
        }
        carriesYear = years.first()
    }
    return null
}

/**
 * The first operand A12 refuses as a group, asked ahead of the arity gate and of both range walks.
 *
 * Only the plain list asks it here. The value list's subject-shape gate refuses a group with its own class
 * ([valueListConditionGateFindings], `MVK_INVALID_PARAMETER_FOR_DATE_RANGE_COMPARISON`), and its VALUE list
 * takes a group outright — the two differ, so neither carrier may borrow the other's answer.
 */
private fun TrackedFactBase.groupOperandDefect(
    call: ConditionCall,
    declaringGroup: String,
    element: TrackedDeclaration,
): RangeDefect? = call.operands.firstNotNullOfOrNull { operand ->
    val target = declaredTargetOf(operand.text, declaringGroup, element, call.inAFilterBody) ?: return null
    if (target.declaredKind == DeclaredKind.GROUP) {
        RangeDefect(
            "MVK_NO_GROUPS_ALLOWED", target.path,
            "names a group where this operator compares fields — name the date-range fields themselves",
        )
    } else {
        null
    }
}

/**
 * The plain list's references, or null where the arity gate of another family answers first.
 *
 * A lone operand is `MVK_PARAMSIZE_INVALIDFN`, which `ENTITY_LIST_ARITY` owns and A12 asks after the group
 * gate above — so this reader declines rather than reproducing it.
 */
private fun TrackedFactBase.plainList(
    call: ConditionCall,
    declaringGroup: String,
    element: TrackedDeclaration,
): List<ListedReference>? {
    if (call.operands.size < 2) {
        return null
    }
    return call.operands.map { operand ->
        val field = bareReferenceField(operand.text, declaringGroup, element, call.inAFilterBody) ?: return null
        ListedReference(field.path, listOf(field))
    }
}

/**
 * The value list's subject and values, or null where one of its own pre-gates answers first.
 *
 * The subject must already be one plain unstarred field and every value must already share its base type;
 * both are [valueListConditionGateFindings]' classes, so a call failing either never reaches here.
 */
private fun TrackedFactBase.valueList(
    call: ConditionCall,
    declaringGroup: String,
    element: TrackedDeclaration,
): List<ListedReference>? {
    val (written, opposite) = call.splitAtIn() ?: return null
    // The two lists' repeats and root groups answer first, at positions [valueListConditionGateFindings] names.
    val subjects = builtEntityList(written, declaringGroup, element, call.inAFilterBody)
    val members = builtEntityList(opposite, declaringGroup, element, call.inAFilterBody)
    if (subjects != null && members != null &&
        listOf(subjects, members, subjects + members).any { (it.duplicateClass() ?: it.rootGroupClass()) != null }) {
        return null
    }
    val subject = bareReferenceField(written.singleOrNull() ?: return null, declaringGroup, element, call.inAFilterBody)
        ?: return null
    val values = opposite.map { operand -> expandedReference(operand, declaringGroup, element, call.inAFilterBody) ?: return null }
    if (values.any { reference -> reference.fields.any { it.baseTypeClass() != subject.baseTypeClass() } }) {
        return null
    }
    return listOf(ListedReference(subject.path, listOf(subject))) + values
}

/** One written operand and the fields beneath it, groups expanded — A12's own `expandToFieldList`. */
private fun TrackedFactBase.expandedReference(
    operand: String,
    declaringGroup: String,
    element: TrackedDeclaration,
    inAFilterBody: Boolean,
): ListedReference? {
    val target = declaredTargetOf(operand, declaringGroup, element, inAFilterBody) ?: return null
    val fields = expandToFields(target.path) ?: return null
    return ListedReference(target.path, fields)
}

private fun finding(
    defect: RangeDefect,
    call: ConditionCall,
    element: TrackedDeclaration,
    member: String,
): Finding = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    defect.code,
    "in `$member`: `${call.operator}` overlaps `${defect.at}`, which ${defect.because}",
    element.path,
    call.spanIn(member),
)
