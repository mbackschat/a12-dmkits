package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionCall

/**
 * What KIND of field `FieldValuesNotUnique` admits in its list, how their date SPELLINGS must agree, and the two
 * gates behind that.
 *
 * `CheckFeldListenBedingungImpl`'s arm runs four gates in a fixed order, and this family owns the last three
 * ([KERNEL-FINDINGS KF390], `rulekit.validate.laws.FieldValuesNotUniqueKindGateLawsTest`): the arity gate
 * (`ENTITY_LIST_ARITY`'s), then `checkOnlyStringEnumNumberOrDateWithSameFormat`
 * (`MVK_ONLY_STRING_ENUM_NUMBER_DATE_ALLOWED`), then `checkSameBaseType` (`MVK_VARYING_TYPES_NOT_ALLOWED`), then
 * `checkAllAreValueValidation` (`MVK_INVALID_RAW_TYPE`). Ahead of all four, the list's reads, its repeats, its
 * root group and its category selectors answer ([listGateAheadOfTheArmAnswers];
 * `adapter.laws.CheckerListAdmissionOrderAgreementTest`).
 *
 * **One class, two questions.** A field that is neither string-like nor date-like is refused outright; and
 * among the temporal ones the declared format STRINGS must be equal.
 *
 * **The admitted KIND set is measured, and reading it off the class NAME would refuse four legal shapes.**
 * "String, enum, number or date" sounds like a closed list, but `FieldUtils.isDate` accepts the clock and
 * stamp kinds too: a `TimeType`, a `DateTimeType` and a `DateFragmentType` all CLEAR this gate and are
 * refused by the base-type gate after it, under a different code. A `DateRangeType` is the one measured
 * refusal. Naming this class on the other three would be the wrong class on a correct rejection, which is
 * the one direction this project forbids.
 *
 * **The format comparison spans KINDS, not just dates.** A `DateType` spelled `yyyy-MM-dd` beside a
 * `DateTimeType` spelled `yyyy-MM-dd'T'HH:mm:ss` draws this class rather than the base-type one, because the
 * kind gate runs first and the two spellings differ — measured rather than inferred from the class name.
 *
 * **It is the declared SPELLING and not the component set**, which is the neighbouring aggregates' gate:
 * `yyyy-MM-dd` beside `dd.MM.yyyy` is one component set and two spellings, admitted by
 * [OperationAnalysis] and refused here ([KF193]).
 *
 * **The format walk remembers a date's spelling past the fields between**, so a Number between two
 * differently spelled Dates is this class, where a Number beside one Date passes on to the base-type class.
 *
 * **Silent on every declared type this reader does not resolve** — `CustomFieldType`, `TypeDefType`,
 * `UnspecifiedType` and the two boolean kinds — because the refusal set is an allow-list of what was
 * measured, never the complement of the admitted one. The base-type gate is silent on a field without a
 * [BaseTypeClass] for the same reason.
 */
internal fun TrackedFactBase.fieldValuesNotUniqueOperandKindFindings(): List<Finding> = declarations().flatMap { element ->
    fieldValuesNotUniqueContributions(element).take(1).map { it.finding }
}

internal fun TrackedFactBase.fieldValuesNotUniqueContributions(
    element: TrackedDeclaration,
    execution: ConditionExecution? = conditionExecution(element),
): List<ConditionFinding> {
    val keywords = CONDITION_OPERATOR_KEYWORDS[conditionLanguage] ?: return emptyList()
    val carrier = keywords[FIELD_VALUES_NOT_UNIQUE] ?: return emptyList()
    val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
    return callContributions(element, execution) { member, call ->
        if (call.operator == carrier) operandKindFinding(call, group, element, member) else null
    }
}

private const val FIELD_VALUES_NOT_UNIQUE = "FieldValuesNotUnique"

private const val KIND_REFUSED = "MVK_ONLY_STRING_ENUM_NUMBER_DATE_ALLOWED"

private fun TrackedFactBase.operandKindFinding(
    call: ConditionCall,
    declaringGroup: String,
    element: TrackedDeclaration,
    member: String,
): Finding? {
    if (listGateAheadOfTheArmAnswers(call, declaringGroup, element)) {
        return null
    }
    // The list is taken whole or not at all: an operand this reader cannot reduce is one whose kind it
    // cannot know, and judging the rest would judge a shorter list than A12 judged. A selection whose key A12
    // admits is judged by the field it selects, never by its key ([KERNEL-FINDINGS KF605]).
    val fields = call.operands.flatMap { operand ->
        val written = operandReferenceIn(operand.text, declaringGroup, element, call.keyAnchoring) {
            operand.path?.let(::OperandReference)
        }?.reference ?: return null
        val resolved = resolveEntity(declaringGroup, written.replace("*", ""), element.origin)
        expandToFields((resolved as? Reference.Resolved)?.path ?: return null) ?: return null
    }
    var spelling: Pair<String, String>? = null
    fields.forEach { field ->
        // The one declared type measured as refused by this gate; every other refusal belongs to a later one.
        if (field.declaredFieldType() == FieldType.DATE_RANGE) {
            return finding(
                "in `$member`: `$FIELD_VALUES_NOT_UNIQUE` compares stored values, and `${field.path}` is a " +
                    "date RANGE — list the range's own fields, or compare a field of another kind",
                element,
                call.spanIn(member),
            )
        }
        val format = declaredTemporalFormatOf(field) ?: return@forEach
        val first = spelling
        if (first == null) {
            spelling = field.path to format
        } else if (first.second != format) {
            return finding(
                "in `$member`: `$FIELD_VALUES_NOT_UNIQUE` compares the stored text, so every temporal field " +
                    "it lists must be spelled alike — `${first.first}` declares `${first.second}` and " +
                    "`${field.path}` declares `$format`",
                element,
                call.spanIn(member),
            )
        }
    }
    val classes = fields.map { it.baseTypeClass() ?: return null }
    // A12 refuses the two boolean kinds at the kind gate this family leaves silent, so the base-type gate behind
    // it may not answer for them.
    if (classes.any { it == BaseTypeClass.YES_NO || it == BaseTypeClass.YES }) {
        return null
    }
    classes.zipWithNext().forEachIndexed { at, (left, right) ->
        if (left != right) {
            return finding(
                "in `$member`: `$FIELD_VALUES_NOT_UNIQUE` compares values of one kind, and `${fields[at + 1].path}` " +
                    "is `${fields[at + 1].declaredFieldType()}` beside `${fields[at].path}`, " +
                    "`${fields[at].declaredFieldType()}` — compare each kind in its own condition",
                element,
                call.spanIn(member),
                VARYING_TYPES,
            )
        }
    }
    fields.firstOrNull { it.waivesValueValidation() }?.let { raw ->
        return finding(
            "in `$member`: `$FIELD_VALUES_NOT_UNIQUE` compares stored values, and `${raw.path}` waives value " +
                "validation — compare a validated field, or drop `noValueValidation` from it",
            element,
            call.spanIn(member),
            RAW,
        )
    }
    return null
}

private const val VARYING_TYPES = "MVK_VARYING_TYPES_NOT_ALLOWED"

private const val RAW = "MVK_INVALID_RAW_TYPE"

private fun finding(explain: String, element: TrackedDeclaration, span: TextSpan, code: String = KIND_REFUSED): Finding =
    Finding(FindingCategory.A12_CONSTRAINT, FindingSeverity.ERROR, code, explain, element.path, span)
