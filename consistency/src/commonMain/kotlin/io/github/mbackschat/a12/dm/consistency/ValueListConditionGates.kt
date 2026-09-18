package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionCall
import io.github.mbackschat.a12.dm.consistency.syntax.TokenKind
import io.github.mbackschat.a12.dm.consistency.syntax.callsIn
import io.github.mbackschat.a12.dm.consistency.syntax.categoryAccessesIn
import io.github.mbackschat.a12.dm.consistency.syntax.referencesIn
import io.github.mbackschat.a12.dm.consistency.syntax.splitAtIn
import io.github.mbackschat.a12.dm.consistency.syntax.tokenize
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Everything `CheckFeldListeUndWerteListenBedingungImpl.check` asks of a field list and the value list it is
 * compared against, in the order it asks it.
 *
 * Four operators share the construct, and the method branches on the operator token TWICE — once on the
 * field list and once on the value list ([KERNEL-FINDINGS KF380] and [KF397], locked by
 * `rulekit.validate.laws.ValueListQuantifierOperandKindLawsTest` and
 * `rulekit.validate.laws.ValueListConditionOperandLawsTest`):
 *
 * | the gate, in order | class |
 * |---|---|
 * | the FIELD list's entries do not all share a base type | `MVK_VARYING_TYPES_NOT_ALLOWED` |
 * | `AtLeastOneDateRangeOverlaps`: the field list is not exactly one plain field | `MVK_INVALID_PARAMETER_FOR_DATE_RANGE_COMPARISON` |
 * | the other three: an entry is neither text, enumeration nor number | `MVK_ONLY_STRING_ENUM_NUMBER_ALLOWED` |
 * | `AtLeastOneDateRangeOverlaps` against a WRITTEN-OUT list | `MVK_NO_CONST` |
 * | a written-out list against a NUMBER field list | `MVK_INVALID_TYPES_FOR_COMPARISON` |
 * | a written-out value outside the enumeration's domain | `MVK_INVALID_STRING_CONSTANT_FOR_ENUMERATION_OR_CATEGORY` |
 * | an ENTITY value list of another base type | `MVK_VARYING_TYPES_NOT_ALLOWED` |
 *
 * **The FIELD list is judged before the value list's type is looked at**, which a reader merging the two
 * into one same-type walk gets wrong: a date-range field list against a text one is the field list's own
 * kind class, not the disagreement class. Measured in both directions, and the row this family's first
 * version would have failed.
 *
 * **The order is also why a single column would be wrong within the field list.** Two Date declarations
 * share a base type and fail the kind gate; a Date beside a text one fails the same-type gate. A family
 * keying on "the list carries a Date" would name whichever class it happened to meet.
 *
 * **The enum domain is the UNION over the whole field list**, so a constant valid for the second field
 * alone is admitted — a reader checking each field separately would refuse a model A12 takes.
 *
 * **…and a CATEGORY access SWAPS that field's contribution rather than widening it.** A field read through
 * `-> Band` contributes its category's tokens and stops contributing its own values, so the enumeration's
 * own value is then refused. One enumeration, two accesses, two verdicts.
 *
 * **A repeated entity pre-empts every one of them**, the duplicate walk running while the reference list is
 * built, and it spans BOTH lists: a field named again among the values is `MVK_DUPLICATE_PARAM1` just as a
 * field named twice among the fields is. Measured, and the reason this family leaves such a call unjudged
 * rather than naming a class on a model A12 refuses for a different reason.
 *
 * **Silent on a `TypeDefType` or `UnspecifiedType` declaration**, whose effective type this reader does not
 * resolve; on the `noValueValidation` gate that runs between the field gates and the value list
 * (`MVK_INVALID_RAW_TYPE`), a value-position law shared with several other constructs; on the date-range
 * arm's two format-and-year walks, which run after everything here and belong to
 * [dateRangeOperandListFindings]; and on an enumeration declaring a `tableName`, which A12 exempts from the
 * domain check and which makes a model unreadable here anyway.
 */
internal fun TrackedFactBase.valueListConditionGateFindings(): List<Finding> {
    val keywords = CONDITION_OPERATOR_KEYWORDS[conditionLanguage] ?: return emptyList()
    val carriers = VALUE_LIST_CONDITIONS
        .mapNotNull { (id, dateRange) -> keywords[id]?.let { it to dateRange } }
        .toMap()
    if (carriers.isEmpty()) {
        return emptyList()
    }
    return declarations().mapNotNull { element ->
        val body = element.body ?: return@mapNotNull null
        val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
        conditionTexts(element.kind, body).firstNotNullOfOrNull { (member, text) ->
            callsIn(text, conditionLanguage, syntaxEntryOf(member))
                .mapNotNull { call -> carriers[call.operator]?.let { call to it } }
                .firstNotNullOfOrNull { (call, dateRange) ->
                    gateFinding(call, dateRange, group, element, member)
                }
        }
    }
}

/**
 * The four carriers, by catalog id, each with whether it takes the DATE-RANGE arm.
 *
 * `AtLeastOneDateRangeOverlaps` shares the grammar and nothing else after the first gate: it demands one
 * plain field where the other three take a list, and refuses the written-out value list they require.
 */
private val VALUE_LIST_CONDITIONS = listOf(
    "AtLeastOneFieldValueIncludedInValueList" to false,
    "NoFieldValueIncludedInValueList" to false,
    "NotAllFieldValuesIncludedInValueList" to false,
    "AtLeastOneDateRangeOverlaps" to true,
)

/** The base-type classes the kind gate admits; every other one draws its class. */
private val LISTABLE_BASE_TYPES =
    setOf(BaseTypeClass.TEXT, BaseTypeClass.NUMBER, BaseTypeClass.ENUMERATION)

private const val VARYING_TYPES = "MVK_VARYING_TYPES_NOT_ALLOWED"

private const val ONLY_STRING_ENUM_NUMBER = "MVK_ONLY_STRING_ENUM_NUMBER_ALLOWED"

private const val INVALID_TYPES_FOR_COMPARISON = "MVK_INVALID_TYPES_FOR_COMPARISON"

private const val BAD_RANGE_OPERAND = "MVK_INVALID_PARAMETER_FOR_DATE_RANGE_COMPARISON"

private const val NO_CONST = "MVK_NO_CONST"

private const val BAD_CONSTANT = "MVK_INVALID_STRING_CONSTANT_FOR_ENUMERATION_OR_CATEGORY"

/** One entry of the field list: the declaration it reaches, and the category it reads it through. */
private class ListedField(val field: TrackedDeclaration, val category: String?)

/** The first gate this call fails, in A12's own order, or null where none fails and where it may not judge. */
private fun TrackedFactBase.gateFinding(
    call: ConditionCall,
    dateRange: Boolean,
    declaringGroup: String,
    element: TrackedDeclaration,
    member: String,
): Finding? {
    val (written, opposite) = call.splitAtIn() ?: return null
    val literalList = opposite.all { tokenize(it).singleOrNull()?.kind == TokenKind.STRING }
    val listed = listedFields(written, declaringGroup, element) ?: return null
    val fields = listed.map { it.field }
    val values = if (literalList) {
        emptyList()
    } else {
        expandedFields(opposite, declaringGroup, element) ?: return null
    }
    if (repeated(fields + values)) {
        return null
    }
    val fieldKinds = fields.map { it.baseTypeClass() ?: return null }

    // The FIELD list's own two gates, both asked before the value list is looked at.
    fieldKinds.zipWithNext().forEachIndexed { at, (left, right) ->
        if (left != right) {
            return gate(VARYING_TYPES, call.operator, fields[at + 1], member, element)
        }
    }
    if (dateRange) {
        rangeShapeDefect(written, declaringGroup, element)
            ?.let { return gate(BAD_RANGE_OPERAND, call.operator, it, member, element) }
    } else {
        fields.forEachIndexed { at, field ->
            if (fieldKinds[at] !in LISTABLE_BASE_TYPES) {
                return gate(ONLY_STRING_ENUM_NUMBER, call.operator, field, member, element)
            }
        }
    }

    // …then the value list, which is a different question on each of its two spellings.
    if (!literalList) {
        val valueKinds = values.map { it.baseTypeClass() ?: return null }
        valueKinds.forEachIndexed { at, kind ->
            if (kind != fieldKinds.firstOrNull()) {
                return gate(VARYING_TYPES, call.operator, values[at], member, element)
            }
        }
        return null
    }
    if (dateRange) {
        return gate(NO_CONST, call.operator, fields.firstOrNull() ?: return null, member, element)
    }
    return when (fieldKinds.firstOrNull()) {
        BaseTypeClass.NUMBER ->
            gate(INVALID_TYPES_FOR_COMPARISON, call.operator, fields.first(), member, element)
        BaseTypeClass.ENUMERATION -> constantDomainFinding(listed, opposite, call, member, element)
        else -> null
    }
}

/**
 * The entry that makes this date-range call's field list the wrong shape, or null where it is the right one.
 *
 * A12 asks it of the REFERENCE list rather than of the expansion — exactly one entry, naming a field, with
 * no repetition star — so a group holding a single field is refused here even though it expands to one.
 */
private fun TrackedFactBase.rangeShapeDefect(
    written: List<String>,
    declaringGroup: String,
    element: TrackedDeclaration,
): TrackedDeclaration? {
    val only = written.singleOrNull() ?: return declaredTargetOf(written.first(), declaringGroup, element)
    val declared = declaredTargetOf(only, declaringGroup, element) ?: return null
    return if (declared.declaredKind != DeclaredKind.FIELD || '*' in only) declared else null
}

/** The declaration one written operand names, whatever kind it is, or null where nothing resolves it. */
private fun TrackedFactBase.declaredTargetOf(
    operand: String,
    declaringGroup: String,
    element: TrackedDeclaration,
): TrackedDeclaration? {
    val reference = referencesIn(operand, conditionLanguage).singleOrNull() ?: return null
    val resolved = resolveEntity(declaringGroup, reference.replace("*", ""), element.origin)
    val target = (resolved as? Reference.Resolved)?.path ?: return null
    return allDeclarations().firstOrNull { it.path == target }
}

/**
 * The first written-out value outside the field list's enum domain, as a finding, or null.
 *
 * The domain is the UNION of every entry's contribution, and an entry read through a category contributes
 * that category's tokens INSTEAD of its own values. A contribution this reader cannot settle — an
 * enumeration whose `values` it cannot read, a category the declaration does not name — abandons the whole
 * check rather than shortening the domain, since a short domain refuses a model A12 takes.
 */
private fun TrackedFactBase.constantDomainFinding(
    listed: List<ListedField>,
    opposite: List<String>,
    call: ConditionCall,
    member: String,
    element: TrackedDeclaration,
): Finding? {
    val domain = mutableSetOf<String>()
    listed.forEach { entry ->
        domain += entry.contributedValues() ?: return null
    }
    val offending = opposite.map { it.trim().removeSurrounding("\"") }
        .firstOrNull { it !in domain } ?: return null
    return Finding(
        FindingCategory.A12_CONSTRAINT,
        FindingSeverity.ERROR,
        BAD_CONSTANT,
        "in `$member`: `${call.operator}` looks up `$offending`, which none of the listed enumerations " +
            "declares — the domain is the union over the whole list, and a `->` category access " +
            "contributes that category's tokens INSTEAD of the enumeration's own values",
        element.path,
    )
}

/** What this entry adds to the lookup domain, or null where this reader cannot settle its contribution. */
private fun ListedField.contributedValues(): List<String>? {
    val body = field.enumerationBody() ?: return null
    if (body["tableName"] != null) {
        return null
    }
    if (category == null) {
        return body.declaredEnumValues()
    }
    val entries = body["categories"] as? JsonArray ?: return null
    val named = entries.filterIsInstance<JsonObject>()
        .firstOrNull { (it["name"] as? JsonPrimitive)?.contentOrNull == category } ?: return null
    val values = named["values"] as? JsonArray ?: return null
    return values.map { (it as? JsonPrimitive)?.takeIf { token -> token.isString }?.content ?: return null }
}

/**
 * Every field the operand texts name, groups expanded, each with the category it is read through.
 *
 * A group expands to every field BENEATH it, nested groups included — the expansion A12's own
 * `getAllContainedFields` performs, measured on the sibling construct
 * (`rulekit.validate.laws.ValueInFieldsConstantKindLawsTest`). An expanded field inherits no category,
 * A12's own grouping being by the REFERENCE's category name.
 */
private fun TrackedFactBase.listedFields(
    operands: List<String>,
    declaringGroup: String,
    element: TrackedDeclaration,
): List<ListedField>? = operands.flatMap { operand ->
    val category = categoryAccessesIn(operand, conditionLanguage).singleOrNull()?.second
    val declared = declaredTargetOf(operand, declaringGroup, element) ?: return null
    when (declared.declaredKind) {
        DeclaredKind.FIELD -> listOf(ListedField(declared, category))
        DeclaredKind.GROUP ->
            allDeclarations().filter { it.declaredKind == DeclaredKind.FIELD && it.path.startsWith("${declared.path}/") }
                .map { ListedField(it, null) }
        else -> return null
    }
}

/** The same expansion where only the declarations matter — the value list, which carries no category law. */
private fun TrackedFactBase.expandedFields(
    operands: List<String>,
    declaringGroup: String,
    element: TrackedDeclaration,
): List<TrackedDeclaration>? = listedFields(operands, declaringGroup, element)?.map { it.field }

/** Whether any field is named twice across the two lists, which A12 answers as a duplicate parameter. */
private fun repeated(fields: List<TrackedDeclaration>): Boolean =
    fields.map { it.path }.distinct().size != fields.size

private fun gate(
    code: String,
    operator: String,
    field: TrackedDeclaration,
    member: String,
    element: TrackedDeclaration,
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
        BAD_RANGE_OPERAND ->
            "in `$member`: `$operator` overlaps ONE date range against a list, and `${field.path}` is not " +
                "one plain field — name a single unstarred date-range field on the left"
        NO_CONST ->
            "in `$member`: `$operator` overlaps date RANGES, so the right-hand side names declarations " +
                "rather than written-out values"
        else ->
            "in `$member`: `$operator` compares `${field.path}`, a number declaration, against written-out " +
                "values — a literal list is read as text, so name number fields on the other side too, or " +
                "compare the value directly"
    },
    element.path,
)
