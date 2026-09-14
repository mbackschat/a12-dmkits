package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionCall
import io.github.mbackschat.a12.dm.consistency.syntax.TokenKind
import io.github.mbackschat.a12.dm.consistency.syntax.callsIn
import io.github.mbackschat.a12.dm.consistency.syntax.referencesIn
import io.github.mbackschat.a12.dm.consistency.syntax.splitAtIn
import io.github.mbackschat.a12.dm.consistency.syntax.tokenize

/**
 * What a value-list quantifier's FIELD list may carry, and what may stand opposite it.
 *
 * `CheckFeldListeUndWerteListenBedingungImpl.check` asks the list two questions in a fixed order, then a
 * third of the list opposite it ([KERNEL-FINDINGS KF380],
 * `rulekit.validate.laws.ValueListQuantifierOperandKindLawsTest`).
 *
 * | what fails | class |
 * |---|---|
 * | the expanded fields do not all share a base type | `MVK_VARYING_TYPES_NOT_ALLOWED` |
 * | one of them is neither text, enumeration nor number | `MVK_ONLY_STRING_ENUM_NUMBER_ALLOWED` |
 * | a LITERAL value list stands opposite a NUMBER field list | `MVK_INVALID_TYPES_FOR_COMPARISON` |
 *
 * **The order is the reason a single column would be wrong.** Two Date declarations share a base type and
 * fail the second question; a Date beside a text one fails the first. Measured on both, because a family
 * keying on "the list carries a Date" would name whichever class it happened to meet.
 *
 * **The value list is judged against the FIRST field's type, not against itself** — `matchBaseType` seeds
 * the expected type from the field list and then walks the values — so an entity value list of another kind
 * draws the same `MVK_VARYING_TYPES_NOT_ALLOWED` the field list would.
 *
 * **A repeated entity pre-empts every one of them**, the duplicate walk running while the reference list is
 * built, and it spans BOTH lists: a field named again among the values is `MVK_DUPLICATE_PARAM1` just as a
 * field named twice among the fields is. Measured, and the reason this family leaves such a call unjudged
 * rather than naming a kind class on a model A12 refuses for a different reason.
 *
 * **Silent on the date-range quantifier's own arm.** `AtLeastOneDateRangeOverlaps` skips the kind question
 * entirely and asks its own instead, over classes (`MVK_INVALID_PARAMETER_FOR_DATE_RANGE_COMPARISON`,
 * `MVK_NO_CONST`, and the two year-consistency ones) that need the DateRange declaration's format, which
 * this reader does not decompose.
 *
 * **Silent on a `TypeDefType` or `UnspecifiedType` declaration**, whose effective type this reader does not
 * resolve, and on the `noValueValidation` gate that runs between the two questions above
 * (`MVK_INVALID_RAW_TYPE`) — that one is a value-position law shared with several other constructs and
 * belongs to a carrier sweep rather than to this one.
 */
fun PreparedModel.valueListQuantifierOperandKindFindings(): List<Finding> {
    val keywords = CONDITION_OPERATOR_KEYWORDS[conditionLanguage] ?: return emptyList()
    val carriers = VALUE_LIST_QUANTIFIERS.mapNotNull { keywords[it] }.toSet()
    if (carriers.isEmpty()) {
        return emptyList()
    }
    return elements.mapNotNull { element ->
        val body = element.body ?: return@mapNotNull null
        val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
        conditionTexts(element.kind, body).firstNotNullOfOrNull { (member, text) ->
            callsIn(text, conditionLanguage, syntaxEntryOf(member))
                .filter { it.operator in carriers }
                .firstNotNullOfOrNull { call -> operandKindFinding(call, group, element, member) }
        }
    }
}

/**
 * The three quantifiers that ask the kind question, by catalog id.
 *
 * `AtLeastOneDateRangeOverlaps` shares the construct and is deliberately absent: its arm of the same method
 * skips this walk and asks about DateRange formats instead.
 */
private val VALUE_LIST_QUANTIFIERS = listOf(
    "AtLeastOneFieldValueIncludedInValueList",
    "NoFieldValueIncludedInValueList",
    "NotAllFieldValuesIncludedInValueList",
)

/** The base-type classes the kind question admits; every other one draws its class. */
private val LISTABLE_BASE_TYPES =
    setOf(BaseTypeClass.TEXT, BaseTypeClass.NUMBER, BaseTypeClass.ENUMERATION)

private const val VARYING_TYPES = "MVK_VARYING_TYPES_NOT_ALLOWED"

private const val ONLY_STRING_ENUM_NUMBER = "MVK_ONLY_STRING_ENUM_NUMBER_ALLOWED"

private const val INVALID_TYPES_FOR_COMPARISON = "MVK_INVALID_TYPES_FOR_COMPARISON"

private fun PreparedModel.operandKindFinding(
    call: ConditionCall,
    declaringGroup: String,
    element: DeclaredElement,
    member: String,
): Finding? {
    val (written, opposite) = call.splitAtIn() ?: return null
    val literalList = opposite.all { tokenize(it).singleOrNull()?.kind == TokenKind.STRING }
    val fields = expandedFields(written, declaringGroup, element) ?: return null
    val values = if (literalList) emptyList() else expandedFields(opposite, declaringGroup, element)
        ?: return null
    if (repeated(fields + values)) {
        return null
    }
    val kinds = (fields + values).map { field -> field.baseTypeClass() ?: return null }
    kinds.zipWithNext().forEachIndexed { at, (left, right) ->
        if (left != right) {
            return kindFinding(VARYING_TYPES, call.operator, (fields + values)[at + 1], member, element)
        }
    }
    fields.forEachIndexed { at, field ->
        if (kinds[at] !in LISTABLE_BASE_TYPES) {
            return kindFinding(ONLY_STRING_ENUM_NUMBER, call.operator, field, member, element)
        }
    }
    return if (literalList && kinds.firstOrNull() == BaseTypeClass.NUMBER) {
        kindFinding(INVALID_TYPES_FOR_COMPARISON, call.operator, fields.first(), member, element)
    } else {
        null
    }
}

/**
 * Every field the operand texts name, groups expanded, or null where this reader cannot reduce one.
 *
 * A group expands to every field BENEATH it, nested groups included — the expansion A12's own
 * `getAllContainedFields` performs, measured on the sibling construct
 * (`rulekit.validate.laws.ValueInFieldsConstantKindLawsTest`).
 */
private fun PreparedModel.expandedFields(
    operands: List<String>,
    declaringGroup: String,
    element: DeclaredElement,
): List<DeclaredElement>? = operands.flatMap { operand ->
    val reference = referencesIn(operand, conditionLanguage).singleOrNull() ?: return null
    val resolved = resolveEntity(declaringGroup, reference.replace("*", ""), element.origin)
    val target = (resolved as? Reference.Resolved)?.path ?: return null
    val declared = elements.firstOrNull { it.path == target } ?: return null
    when (declared.declaredKind) {
        DeclaredKind.FIELD -> listOf(declared)
        DeclaredKind.GROUP ->
            elements.filter { it.declaredKind == DeclaredKind.FIELD && it.path.startsWith("$target/") }
        else -> return null
    }
}

/** Whether any field is named twice across the two lists, which A12 answers as a duplicate parameter. */
private fun repeated(fields: List<DeclaredElement>): Boolean =
    fields.map { it.path }.distinct().size != fields.size

private fun kindFinding(
    code: String,
    operator: String,
    field: DeclaredElement,
    member: String,
    element: DeclaredElement,
): Finding = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    code,
    when (code) {
        VARYING_TYPES ->
            "in `$member`: `$operator` compares one list against another, so every entry has to be " +
                "declared alike, and `${field.path}` is `${field.declaredFieldType()}` where the entry " +
                "before it is not — split the call in two, one per declared type"
        ONLY_STRING_ENUM_NUMBER ->
            "in `$member`: `$operator` reads its entries as values to look up, which only a text, " +
                "enumeration or number declaration offers — `${field.path}` is " +
                "`${field.declaredFieldType()}`; compare it directly instead"
        else ->
            "in `$member`: `$operator` compares `${field.path}`, a number declaration, against written-out " +
                "values — a literal list is read as text, so name number fields on the other side too, or " +
                "compare the value directly"
    },
    element.path,
)
