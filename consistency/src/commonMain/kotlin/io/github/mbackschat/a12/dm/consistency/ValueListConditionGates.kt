package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionCall
import io.github.mbackschat.a12.dm.consistency.syntax.TokenKind
import io.github.mbackschat.a12.dm.consistency.syntax.categoryAccessesIn
import io.github.mbackschat.a12.dm.consistency.syntax.decodeAuthoredStringLiteral
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
 * | the FIELD list repeats an entity, or names one under another | `MVK_DUPLICATE_PARAM1`, `MVK_DUPLICATE_PARAM2` |
 * | the FIELD list names a root group | `MVK_ROOT_GROUP_REFERENCED` |
 * | the FIELD list's entries do not all share a base type | `MVK_VARYING_TYPES_NOT_ALLOWED` |
 * | `AtLeastOneDateRangeOverlaps`: the field list is not exactly one plain field | `MVK_INVALID_PARAMETER_FOR_DATE_RANGE_COMPARISON` |
 * | the other three: an entry is neither text, enumeration nor number | `MVK_ONLY_STRING_ENUM_NUMBER_ALLOWED` |
 * | a field-list entry waives value validation | `MVK_INVALID_RAW_TYPE` |
 * | `AtLeastOneDateRangeOverlaps` against a WRITTEN-OUT list | `MVK_NO_CONST` |
 * | a written-out list against a NUMBER field list | `MVK_INVALID_TYPES_FOR_COMPARISON` |
 * | a written-out value outside the enumeration's domain | `MVK_INVALID_STRING_CONSTANT_FOR_ENUMERATION_OR_CATEGORY` |
 * | an ENTITY value list repeats an entity, or names one under another | `MVK_DUPLICATE_PARAM1`, `MVK_DUPLICATE_PARAM2` |
 * | an entity value list names a root group | `MVK_ROOT_GROUP_REFERENCED` |
 * | an ENTITY value list of another base type | `MVK_VARYING_TYPES_NOT_ALLOWED` |
 * | the two lists together repeat an entity | `MVK_DUPLICATE_PARAM1`, `MVK_DUPLICATE_PARAM2` |
 * | an entity value list entry waives value validation | `MVK_INVALID_RAW_TYPE` |
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
 * **A repeat and a root group are asked at their own positions, so this family names them.** `getRefList`
 * refuses a repeat as it builds each list, and `checkNotRootGroupReferenced` follows: the FIELD list ahead of
 * every gate above, the value list only after the field list's type and validation gates. A repeat ACROSS the
 * two is asked later still, after the value list's base type and ahead of its validation. So
 * `Reference, Quantity In Reference` is the field list's `MVK_VARYING_TYPES_NOT_ALLOWED`,
 * `Reference In Reference, InternalMemo` is `MVK_DUPLICATE_PARAM1` (KERNEL-FINDINGS KF380), and
 * `Window In Window, Premium` is the value list's `MVK_VARYING_TYPES_NOT_ALLOWED` ([KF401],
 * `adapter.laws.CheckerListAdmissionOrderAgreementTest`). A list this reader cannot build — an operand with a
 * clause or a category arrow — leaves it silent from that list's position on.
 *
 * **The category access is asked FIRST, and it belongs to another family.** An access demands a field that
 * is an enumeration declaring the named category, and the spec's own gate asks that as the operand is read
 * (`CheckEntitySpecImpl.checkCategory`), so `CheckFamily.CATEGORY_REFERENCE` answers — `MVK_NO_GROUPS_ALLOWED`
 * for a GROUP under an access, measured on either side of the comparison, in both operand orders and ahead of
 * both gates below, and `MVK_NO_ENUMERATION` or `MVK_CATEGORY_UNKOWN` (the kernel's spelling) for a field it
 * cannot ride. A12 reports nothing after it, so this family stays SILENT there rather than adding the class
 * its own lists would draw.
 *
 * **Silent on a `TypeDefType` or `UnspecifiedType` declaration**, whose effective type this reader does not
 * resolve; on the date-range arm's two format-and-year walks, which run after everything here and belong to
 * [dateRangeOperandListFindings]; and on an enumeration declaring a `tableName`, which A12 exempts from the
 * domain check and which makes a model unreadable here anyway.
 */
internal fun TrackedFactBase.valueListConditionGateFindings(): List<Finding> = declarations().flatMap { element ->
    valueListConditionContributions(element).take(1).map { it.finding }
}

internal fun TrackedFactBase.valueListConditionContributions(
    element: TrackedDeclaration,
    execution: ConditionExecution? = conditionExecution(element),
): List<ConditionFinding> {
    val keywords = CONDITION_OPERATOR_KEYWORDS[conditionLanguage] ?: return emptyList()
    val carriers = VALUE_LIST_CONDITIONS
        .mapNotNull { (id, dateRange) -> keywords[id]?.let { it to dateRange } }
        .toMap()
    if (carriers.isEmpty()) {
        return emptyList()
    }
    val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
    return callContributions(element, execution) { member, call ->
        carriers[call.operator]?.let { gateFinding(call, it, group, element, member) }
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


private const val RAW = "MVK_INVALID_RAW_TYPE"

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

    // A category access on a GROUP, or on a FIELD it cannot ride, draws the ACCESS's own class — raised as
    // the operand is read, whatever the group would expand to and on either side of the comparison
    // (`rulekit.validate.laws.ValueListConditionOperandLawsTest`) — and A12 reports nothing after it, so this
    // family must stay SILENT rather than add the class its own lists would draw. The group and both field
    // arms were measured beside a number field, where the varying-types gate would otherwise answer, and all
    // three still draw the access's class — so the silence is as wide as the pre-emption, not wider.
    if (aCategoryAccessAnotherFamilyOwns(written + opposite, declaringGroup, element)) {
        return null
    }

    val literalList = opposite.isWrittenOutValueList()
    // A list the shared builder cannot key — a keyed or starred operand it does not address — keeps the
    // conservative reading this family had before it named these gates: silent on a root group or a repeat.
    val subjects = builtEntityList(written, declaringGroup, element, call.inAFilterBody)
    val members = if (literalList) null else builtEntityList(opposite, declaringGroup, element, call.inAFilterBody)
    if (subjects == null || !literalList && members == null) {
        if ((written + opposite).any { declaredTargetOf(it, declaringGroup, element)?.path?.isRootGroup() == true }) {
            return null
        }
    }
    (subjects?.duplicateClass() ?: subjects?.rootGroupClass())
        ?.let { return listClass(it, call, member, element) }
    val listed = listedFields(written, declaringGroup, element) ?: return null
    val fields = listed.map { it.field }
    if (subjects == null && repeated(fields)) {
        return null
    }
    val fieldKinds = fields.map { it.baseTypeClass() ?: return null }

    // The FIELD list's own two gates, both asked before the value list is looked at.
    fieldKinds.zipWithNext().forEachIndexed { at, (left, right) ->
        if (left != right) {
            return gate(VARYING_TYPES, call.operator, fields[at + 1], member, element, call.spanIn(member))
        }
    }
    if (dateRange) {
        rangeShapeDefect(written, declaringGroup, element)
            ?.let { return gate(BAD_RANGE_OPERAND, call.operator, it, member, element, call.spanIn(member)) }
    } else {
        fields.forEachIndexed { at, field ->
            if (fieldKinds[at] !in LISTABLE_BASE_TYPES) {
                return gate(ONLY_STRING_ENUM_NUMBER, call.operator, field, member, element, call.spanIn(member))
            }
        }
    }

    fields.firstOrNull(::waivesValueValidation)
        ?.let { return gate(RAW, call.operator, it, member, element, call.spanIn(member)) }

    // …then the value list, which is a different question on each of its two spellings.
    if (!literalList) {
        // The value list is built only now, so its own repeat and root answer here, behind the field list's gates.
        (members?.duplicateClass() ?: members?.rootGroupClass())
            ?.let { return listClass(it, call, member, element) }
        val values = expandedFields(opposite, declaringGroup, element) ?: return null
        if (members == null && repeated(values)) {
            return null
        }
        val valueKinds = values.map { it.baseTypeClass() ?: return null }
        valueKinds.forEachIndexed { at, kind ->
            if (kind != fieldKinds.firstOrNull()) {
                return gate(VARYING_TYPES, call.operator, values[at], member, element, call.spanIn(member))
            }
        }
        // A repeat ACROSS the two lists is asked after the value list's base type and ahead of its validation.
        if (subjects != null && members != null) {
            (subjects + members).duplicateClass()?.let { return listClass(it, call, member, element) }
        } else if (repeated(fields + values)) {
            return null
        }
        values.firstOrNull(::waivesValueValidation)
            ?.let { return gate(RAW, call.operator, it, member, element, call.spanIn(member)) }
        return null
    }
    if (dateRange) {
        return gate(NO_CONST, call.operator, fields.firstOrNull() ?: return null, member, element, call.spanIn(member))
    }
    return when (fieldKinds.firstOrNull()) {
        BaseTypeClass.NUMBER ->
            gate(INVALID_TYPES_FOR_COMPARISON, call.operator, fields.first(), member, element, call.spanIn(member))
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
    val offending = opposite.map { it.javaTrimmed().decodeAuthoredStringLiteral() }
        .firstOrNull { it !in domain } ?: return null
    return Finding(
        FindingCategory.A12_CONSTRAINT,
        FindingSeverity.ERROR,
        BAD_CONSTANT,
        "in `$member`: `${call.operator}` looks up `$offending`, which none of the listed enumerations " +
            "declares — the domain is the union over the whole list, and a `->` category access " +
            "contributes that category's tokens INSTEAD of the enumeration's own values",
        element.path,
        call.spanIn(member),
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
    val category = categoryAccessesIn(operand, conditionLanguage).singleOrNull()?.category
    val declared = declaredTargetOf(operand, declaringGroup, element) ?: return null
    // The category rides the REFERENCE, so only an operand that IS a field carries one: the fields a group
    // expands into were never named, and A12 groups by the name it was given.
    val referenced = if (declared.declaredKind == DeclaredKind.FIELD) category else null
    fieldsUnder(declared)?.map { ListedField(it, referenced) } ?: return null
}

/**
 * Whether an operand's category access is one another family already answers for.
 *
 * A GROUP under an access is one, and for a field it asks [ListedField.contributedValues], so the answer is
 * exactly "this reader cannot settle what the access contributes" — a field that is no enumeration, an
 * enumeration declaring no such category, or a table-backed one whose values are not in the model. The group
 * and the first two are classes A12 reports from the access's own gates; the third is beyond any reader
 * here, and silence is the safe direction for it too.
 */
private fun TrackedFactBase.aCategoryAccessAnotherFamilyOwns(
    operands: List<String>,
    declaringGroup: String,
    element: TrackedDeclaration,
): Boolean = operands.any { operand ->
    val category = categoryAccessesIn(operand, conditionLanguage).singleOrNull()?.category ?: return@any false
    val target = declaredTargetOf(operand, declaringGroup, element) ?: return@any false
    target.declaredKind == DeclaredKind.GROUP ||
        target.declaredKind == DeclaredKind.FIELD && ListedField(target, category).contributedValues() == null
}

/** The same expansion where only the declarations matter — the value list, which carries no category law. */
private fun TrackedFactBase.expandedFields(
    operands: List<String>,
    declaringGroup: String,
    element: TrackedDeclaration,
): List<TrackedDeclaration>? = listedFields(operands, declaringGroup, element)?.map { it.field }

/** Whether a value list is written out, every entry a string literal, rather than read from fields. */
internal fun List<String>.isWrittenOutValueList(): Boolean =
    all { tokenize(it).singleOrNull()?.kind == TokenKind.STRING }

/** Whether any field is named twice among [fields] — the conservative reading for a list no builder keys. */
private fun repeated(fields: List<TrackedDeclaration>): Boolean =
    fields.map { it.path }.distinct().size != fields.size

/** A duplicate or root-group class one of the two lists draws as A12 builds it. */
private fun listClass(code: String, call: ConditionCall, member: String, element: TrackedDeclaration): Finding = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    code,
    "in `$member`: `${call.operator}` " + when (code) {
        "MVK_ROOT_GROUP_REFERENCED" -> "names a root group — name the fields or groups you mean instead"
        "MVK_DUPLICATE_PARAM2" -> "names an entity and a group that already contains it, within one list or " +
            "across the two — drop whichever of the two you did not mean"
        else -> "names one entity twice, within one list or across the two — drop the repeat"
    },
    element.path,
    call.spanIn(member),
)

private fun gate(
    code: String,
    operator: String,
    field: TrackedDeclaration,
    member: String,
    element: TrackedDeclaration,
    span: TextSpan,
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
        RAW ->
            "in `$member`: `$operator` compares stored values, and `${field.path}` waives value validation, " +
                "so its value is never checked against its type — compare a validated field instead, or drop " +
                "`noValueValidation` from it"
        else ->
            "in `$member`: `$operator` compares `${field.path}`, a number declaration, against written-out " +
                "values — a literal list is read as text, so name number fields on the other side too, or " +
                "compare the value directly"
    },
    element.path,
    span,
)
