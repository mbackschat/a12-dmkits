package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionCall
import io.github.mbackschat.a12.dm.consistency.syntax.callsIn

/**
 * What KIND of field `FieldValuesNotUnique` admits in its list, and how their date SPELLINGS must agree.
 *
 * `CheckFeldListenBedingungImpl`'s arm runs four gates in a fixed order, and this family owns the second
 * ([KERNEL-FINDINGS KF390], `rulekit.validate.laws.FieldValuesNotUniqueKindGateLawsTest`): the arity gate,
 * then `checkOnlyStringEnumNumberOrDateWithSameFormat` (`MVK_ONLY_STRING_ENUM_NUMBER_DATE_ALLOWED`), then
 * `checkSameBaseType` (`MVK_VARYING_TYPES_NOT_ALLOWED`), then `checkAllAreValueValidation`
 * (`MVK_INVALID_RAW_TYPE`). The three neighbours are measured in the same grid, because a gate's class means
 * nothing without knowing what out-ranks it.
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
 * [temporalAggregateOperandFindings] and refused here ([KF193]).
 *
 * **Silent on every declared type this reader does not resolve** — `CustomFieldType`, `TypeDefType`,
 * `UnspecifiedType` and the two boolean kinds — because the refusal set is an allow-list of what was
 * measured, never the complement of the admitted one.
 */
internal fun TrackedFactBase.fieldValuesNotUniqueOperandKindFindings(): List<Finding> {
    val keywords = CONDITION_OPERATOR_KEYWORDS[conditionLanguage] ?: return emptyList()
    val carrier = keywords[FIELD_VALUES_NOT_UNIQUE] ?: return emptyList()
    return declarations().mapNotNull { element ->
        val body = element.body ?: return@mapNotNull null
        val declaringGroup = element.path.substringBeforeLast('/', missingDelimiterValue = "")
        conditionTexts(element.kind, body).firstNotNullOfOrNull { (member, text) ->
            callsIn(text, conditionLanguage, syntaxEntryOf(member))
                .filter { it.operator == carrier }
                .firstNotNullOfOrNull { call -> operandKindFinding(call, declaringGroup, element, member) }
        }
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
    // The list is taken whole or not at all: an operand this reader cannot reduce is one whose kind it
    // cannot know, and judging the rest would judge a shorter list than A12 judged.
    val fields = call.operands.flatMap { operand ->
        val written = operand.path ?: return null
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
    return null
}

private fun finding(explain: String, element: TrackedDeclaration, span: TextSpan): Finding =
    Finding(FindingCategory.A12_CONSTRAINT, FindingSeverity.ERROR, KIND_REFUSED, explain, element.path, span)
