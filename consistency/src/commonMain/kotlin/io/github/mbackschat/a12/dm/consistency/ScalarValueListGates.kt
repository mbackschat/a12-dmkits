package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.CallOperand
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionCall
import io.github.mbackschat.a12.dm.consistency.syntax.TokenKind
import io.github.mbackschat.a12.dm.consistency.syntax.callsIn
import io.github.mbackschat.a12.dm.consistency.syntax.categoryAccessesIn
import io.github.mbackschat.a12.dm.consistency.syntax.referencesIn
import io.github.mbackschat.a12.dm.consistency.syntax.tokenize
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Everything `CheckFeldUndWerteListenBedingungImpl.check` asks of the two SCALAR value-list predicates, in
 * the order it asks it.
 *
 * The method judges the SUBJECT field first and completely, and only then the list it is compared against —
 * which is two different questions depending on whether the list is written out or is a list of `[field]`
 * entries ([KERNEL-FINDINGS KF360] and [KF398], locked by
 * `rulekit.validate.laws.EnumConstantDomainLawsTest` and `rulekit.validate.laws.ScalarValueListGateLawsTest`):
 *
 * | the gate, in order | class |
 * |---|---|
 * | the subject is a group | `MVK_NO_GROUPS_ALLOWED` |
 * | the subject carries a repetition star | `MVK_NO_WILDCARDS_ALLOWED` |
 * | the subject is neither text, custom, enumeration nor number | `MVK_ONLY_STRING_ENUM_NUMBER_ALLOWED` |
 * | the subject waives value validation | `MVK_INVALID_RAW_TYPE` |
 * | `[field]` entries: one named twice, the subject included | `MVK_DUPLICATE_PARAM1` |
 * | `[field]` entries: one of another base type | `MVK_VARYING_TYPES_NOT_ALLOWED` |
 * | `[field]` entries: one waiving value validation | `MVK_INVALID_RAW_TYPE` |
 * | a written-out list whose KIND is not the subject's | `MVK_INVALID_TYPES_FOR_COMPARISON` |
 * | a written-out value outside a plain enumeration's domain | `MVK_INVALID_STRING_CONSTANT_FOR_ENUMERATION` |
 * | …outside the CATEGORY's classifications | `MVK_INVALID_STRING_CONSTANT_FOR_ENUMERATION_CATEGORY` |
 *
 * **The subject gates run on BOTH list forms**, which is what makes the two spellings one construct rather
 * than two: a boolean subject is refused with the same class whether the list is `"x"` or `[Other]`.
 *
 * **A CUSTOM declaration rides with text on both gates that group them.** `isLikeString` admits
 * `IPredefinedType` beside `IStringType`, so the subject gate takes it; and `matchBaseType` gives the two
 * the same base type, so a custom `[field]` entry matches a string subject where an enumeration does not.
 * Both cells are measured rather than transferred — the second is the one a reader expecting the gate's own
 * name (`…STRING_ENUM_NUMBER…`) would get wrong in both directions.
 *
 * **The kind gate is asked before the domain one**, so a NUMBER list under a categorized enumeration draws
 * the comparison class and never reaches the category at all.
 *
 * **…and a CATEGORY access SWAPS the domain rather than widening it**: `Tier -> Band` is checked against
 * `Band`'s classifications, so the enumeration's own value is then refused. One enumeration, two accesses,
 * two verdicts.
 *
 * **A STRING subject's list is admitted whatever it carries**, `isLikeString` being a `continue` in A12's
 * own loop, so the domain arm reports only where the subject is an Enumeration.
 *
 * **The neighbouring carriers answer with OTHER classes and are not this family's.** The three multi-member
 * quantifiers check the UNION of their members' domains and answer `…_ENUMERATION_OR_CATEGORY`
 * ([valueListConditionGateFindings] owns them); a comparison answers `…_ENUM_COMPARISON`, whichever way it
 * is categorized. Both are measured in the same grid, and widening this family by analogy would name the
 * scalars' class on them.
 *
 * **Silent on a `TypeDefType` or `UnspecifiedType` subject**, whose effective type this reader does not
 * resolve; on an enumeration declaring a `tableName`, which A12 exempts from the domain check; and on a
 * subject whose repetition stars do not match its repeating levels, where a wildcard-validity class answers
 * ahead of every gate here.
 */
fun PreparedModel.scalarValueListGateFindings(): List<Finding> {
    val keywords = CONDITION_OPERATOR_KEYWORDS[conditionLanguage] ?: return emptyList()
    val carriers = SCALAR_VALUE_LISTS.mapNotNull { keywords[it] }.toSet()
    if (carriers.isEmpty()) {
        return emptyList()
    }
    return elements.flatMap { element ->
        val body = element.body ?: return@flatMap emptyList()
        val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
        conditionTexts(element.kind, body).firstNotNullOfOrNull { (member, text) ->
            callsIn(text, conditionLanguage, syntaxEntryOf(member))
                .filter { it.operator in carriers }
                .firstNotNullOfOrNull { call -> gateFindings(call, group, element, member) }
        } ?: emptyList()
    }
}

/** The two value-list predicates that take ONE subject field, named by their catalog ids. */
private val SCALAR_VALUE_LISTS = setOf("FieldValueIncludedInValueList", "FieldValueNotIncludedInValueList")

/** The declarations the subject gate admits — A12 asks three POSITIVE predicates and this is their union. */
private val SUBJECT_TYPES =
    setOf("StringType", "CustomFieldType", "EnumerationType", "NumberType")

/** The declarations whose effective type this reader does not resolve, so no gate here may judge them. */
private val UNRESOLVED_TYPES = setOf("TypeDefType", "UnspecifiedType")

private const val NO_GROUPS = "MVK_NO_GROUPS_ALLOWED"

private const val NO_WILDCARDS = "MVK_NO_WILDCARDS_ALLOWED"

private const val ONLY_STRING_ENUM_NUMBER = "MVK_ONLY_STRING_ENUM_NUMBER_ALLOWED"

private const val RAW_TYPE = "MVK_INVALID_RAW_TYPE"

private const val VARYING_TYPES = "MVK_VARYING_TYPES_NOT_ALLOWED"

private const val INVALID_TYPES_FOR_COMPARISON = "MVK_INVALID_TYPES_FOR_COMPARISON"

private const val OUTSIDE_DOMAIN = "MVK_INVALID_STRING_CONSTANT_FOR_ENUMERATION"

private const val OUTSIDE_CATEGORY = "MVK_INVALID_STRING_CONSTANT_FOR_ENUMERATION_CATEGORY"

/**
 * The first gate this call fails, as findings, or null where none fails and where this may not judge.
 *
 * Null and an empty list are different answers: null lets the caller look at the next call, an empty list
 * would claim this one is clean. Only the domain arm ever returns more than one — A12 stops at its first
 * offending constant, so several is this checker saying more than A12 does about one call, which costs an
 * author nothing and is the multiplicity the family has always had.
 */
private fun PreparedModel.gateFindings(
    call: ConditionCall,
    declaringGroup: String,
    element: DeclaredElement,
    member: String,
): List<Finding>? {
    val subjectOperand = call.operands.firstOrNull() ?: return null
    val entries = call.operands.drop(1).takeIf { it.isNotEmpty() } ?: return null
    val subject = declaredTargetOf(subjectOperand.text, declaringGroup, element) ?: return null
    if (subjectOperand.text.count { it == '*' } != repeatingLevelsOnThePathTo(subject.path)) {
        return null
    }

    // The subject, judged completely before the list is looked at at all.
    if (subject.declaredKind == DeclaredKind.GROUP) {
        return listOf(gate(NO_GROUPS, call.operator, subject, member, element))
    }
    if (subject.declaredKind != DeclaredKind.FIELD) {
        return null
    }
    if ('*' in subjectOperand.text) {
        return listOf(gate(NO_WILDCARDS, call.operator, subject, member, element))
    }
    val declared = subject.declaredFieldType() ?: return null
    if (declared in UNRESOLVED_TYPES) {
        return null
    }
    if (declared !in SUBJECT_TYPES) {
        return listOf(gate(ONLY_STRING_ENUM_NUMBER, call.operator, subject, member, element))
    }
    if (subject.waivesValueValidation()) {
        return listOf(gate(RAW_TYPE, call.operator, subject, member, element))
    }

    return if (entries.all { it.namesAFieldEntry() }) {
        entryListFindings(subject, entries, call, declaringGroup, element, member)
    } else {
        constantListFindings(subject, subjectOperand, entries, call, member, element)
    }
}

/**
 * The `[field]`-entry list's three gates, in A12's order: duplicates, base types, value validation.
 *
 * The duplicate walk takes the SUBJECT together with the entries, so naming it inside its own list is a
 * duplicate parameter rather than a self-comparison — measured, and the reason this leaves such a call to
 * A12 rather than naming a type class on it.
 */
private fun PreparedModel.entryListFindings(
    subject: DeclaredElement,
    entries: List<CallOperand>,
    call: ConditionCall,
    declaringGroup: String,
    element: DeclaredElement,
    member: String,
): List<Finding>? {
    val fields = entries.flatMap { entry ->
        val declared = declaredTargetOf(entry.text, declaringGroup, element) ?: return null
        when (declared.declaredKind) {
            DeclaredKind.FIELD -> listOf(declared)
            DeclaredKind.GROUP ->
                elements.filter { it.declaredKind == DeclaredKind.FIELD && it.path.startsWith("${declared.path}/") }
            else -> return null
        }
    }
    val walked = listOf(subject) + fields
    if (walked.map { it.path }.distinct().size != walked.size) {
        return null
    }
    var expected = subject.listBaseType() ?: return null
    fields.forEach { field ->
        val kind = field.listBaseType() ?: return null
        if (kind != expected) {
            return listOf(gate(VARYING_TYPES, call.operator, field, member, element))
        }
        expected = kind
    }
    fields.forEach { field ->
        if (field.waivesValueValidation()) {
            return listOf(gate(RAW_TYPE, call.operator, field, member, element))
        }
    }
    return null
}

/**
 * The written-out list's two gates: its KIND against the subject's, then an Enumeration's domain.
 *
 * The kind test is A12's own exclusive-or — a number subject demands a number list and every other subject
 * refuses one — so it is asked of the LIST's spelling rather than of each constant's type.
 */
private fun PreparedModel.constantListFindings(
    subject: DeclaredElement,
    subjectOperand: CallOperand,
    entries: List<CallOperand>,
    call: ConditionCall,
    member: String,
    element: DeclaredElement,
): List<Finding>? {
    val numberList = entries.all { it.isNumberLiteral() }
    val stringList = entries.all { it.literal != null }
    if (!numberList && !stringList) {
        return null
    }
    if ((subject.declaredFieldType() == "NumberType") != numberList) {
        return listOf(gate(INVALID_TYPES_FOR_COMPARISON, call.operator, subject, member, element))
    }
    val body = subject.enumerationBody() ?: return null
    if (body["tableName"] != null) {
        return null
    }
    val category = categoryAccessesIn(subjectOperand.text, conditionLanguage).singleOrNull()?.second
    val domain = body.lookupDomain(category)?.toSet() ?: return null
    val offending = entries.mapNotNull { it.literal }.filter { it !in domain }
    return offending.map { constant ->
        Finding(
            FindingCategory.A12_CONSTRAINT,
            FindingSeverity.ERROR,
            if (category == null) OUTSIDE_DOMAIN else OUTSIDE_CATEGORY,
            if (category == null) {
                "in `$member`: `\"$constant\"` is not a value of `${subject.path}` — it admits " +
                    domain.joinToString { "`$it`" }
            } else {
                "in `$member`: `\"$constant\"` is not a classification of `${subject.path} -> $category` — " +
                    "the `->` access checks the CATEGORY's tokens instead of the enumeration's own values, " +
                    "which are " + domain.joinToString { "`$it`" }
            },
            element.path,
        )
    }.ifEmpty { null }
}

/** The values this enumeration is looked up in — its own, or the named category's INSTEAD of them. */
private fun JsonObject.lookupDomain(category: String?): List<String>? {
    if (category == null) {
        return declaredEnumValues()
    }
    val entries = this["categories"] as? JsonArray ?: return null
    val named = entries.filterIsInstance<JsonObject>()
        .firstOrNull { (it["name"] as? JsonPrimitive)?.contentOrNull == category } ?: return null
    val values = named["values"] as? JsonArray ?: return null
    return values.map { (it as? JsonPrimitive)?.takeIf { token -> token.isString }?.content ?: return null }
}

/**
 * The base type `matchBaseType` sorts this declaration into, or null where this reader cannot say.
 *
 * A CUSTOM declaration answers TEXT — measured on this carrier
 * (`rulekit.validate.laws.ScalarValueListGateLawsTest`), and deliberately not folded into the shared
 * [baseTypeClass], whose other consumers ask the same question on carriers where the grouping is unmeasured.
 */
private fun DeclaredElement.listBaseType(): BaseTypeClass? =
    if (declaredFieldType() == "CustomFieldType") BaseTypeClass.TEXT else baseTypeClass()

/** Whether this operand is the list's `[entity]` spelling rather than a constant. */
private fun CallOperand.namesAFieldEntry(): Boolean =
    text.trim().startsWith("[") && text.trim().endsWith("]")

/** Whether this operand is one number constant — the spelling A12's grammar sorts into a `numberListe`. */
private fun CallOperand.isNumberLiteral(): Boolean = isNumberLiteral(tokenize(text))

/** The declaration one written operand names, whatever kind it is, or null where nothing resolves it. */
private fun PreparedModel.declaredTargetOf(
    operand: String,
    declaringGroup: String,
    element: DeclaredElement,
): DeclaredElement? {
    val reference = referencesIn(operand, conditionLanguage).singleOrNull() ?: return null
    val resolved = resolveEntity(declaringGroup, reference.replace("*", ""), element.origin)
    val target = (resolved as? Reference.Resolved)?.path ?: return null
    return elements.firstOrNull { it.path == target }
}

private fun gate(
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
        NO_GROUPS ->
            "in `$member`: `$operator` takes one field and this hands it the group `${field.path}` — name " +
                "a field inside it, or use a value-list QUANTIFIER, which takes a list"
        NO_WILDCARDS ->
            "in `$member`: `$operator` compares ONE value, so `${field.path}` may not be read across a " +
                "repetition — drop the star, or quantify with a value-list condition"
        ONLY_STRING_ENUM_NUMBER ->
            "in `$member`: `$operator` reads `${field.path}`, declared `${field.declaredFieldType()}` — a " +
                "value list takes a text, custom, enumeration or number field; compare a boolean with " +
                "`== True` and a date with a date comparison"
        RAW_TYPE ->
            "in `$member`: `${field.path}` waives value validation, and `$operator` refuses every field in " +
                "its call that does — drop `noValueValidation`, or compare the field another way"
        else ->
            "in `$member`: `$operator` compares `${field.path}`, declared `${field.declaredFieldType()}`, " +
                "against a list of another base type — every entry has to be declared like the subject"
    },
    element.path,
)
