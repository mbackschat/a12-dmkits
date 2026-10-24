package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionComparison
import io.github.mbackschat.a12.dm.consistency.syntax.ComparisonShape
import io.github.mbackschat.a12.dm.consistency.syntax.OperationShape
import io.github.mbackschat.a12.dm.consistency.syntax.spanning
import io.github.mbackschat.a12.dm.predicates.kernelPatternBlacklist
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Comparison arms consume child results once; KF382–KF387 settle their order and direct-read boundaries. */
internal fun OperationAnalysis.comparisonFinding(shape: ComparisonShape): Finding? {
    val leftShape = recognition.operations.spanning(shape.left) ?: return null
    val rightShape = recognition.operations.spanning(shape.right) ?: return null
    val (left, right) = baseYearResolved(shape.operator.text, value(leftShape), value(rightShape)) ?: return null
    val comparison = ConditionComparison(written(leftShape), shape.operator.text, shape.operator.start,
        written(rightShape), leftShape.at, rightShape.end)
    return comparisonFinding(comparison, leftShape, left, right)
}

/**
 * The two sides with a base-year operand replaced by the type A12 compares it as, or null to leave the
 * comparison unjudged.
 *
 * `BaseYear` meets the walk as a whole number, except that opposite a date or time it is the base year's first
 * day ([KERNEL-FINDINGS KF86]); `StartOfDateRange(BaseYear)` and `EndOfDateRange(BaseYear)` meet it as a date.
 * Both are measured with `==` against one field of each of eleven kinds, on a model that declares a base year
 * (`adapter.laws.BaseYearComparisonClassAgreementTest`, [KERNEL-FINDINGS KF502]). Every other pairing stays
 * unjudged: another operator, a constant, computed or base-year partner, and an endpoint opposite a date-time,
 * which the grid does not reach. A model without a base year reports `MVK_NO_BASE_YEAR` alone, as A12 does,
 * whatever this resolves (the same test's `no-base-year-*` rows).
 */
private fun OperationAnalysis.baseYearResolved(
    operator: String,
    left: OperationValue,
    right: OperationValue,
): Pair<OperationValue, OperationValue>? {
    val year = left.takeIf { it.baseYear != null } ?: right.takeIf { it.baseYear != null } ?: return left to right
    val partner = if (year === left) right else left
    if (operator != "==" || partner.baseYear != null || partner.field == null ||
        partner.type == OperationType.Unknown) return null
    val date = OperationValue(OperationType.Temporal(componentsOf(DATE_FORMAT)))
    val resolved = when (year.baseYear) {
        BaseYearRole.ENDPOINT -> if (facts.effectiveFieldType(partner.field) == FieldType.DATE_TIME) return null else date
        BaseYearRole.YEAR -> if (partner.temporal != null) date else OperationValue(OperationType.Number(0))
        null -> return left to right
    }
    return if (year === left) resolved to partner else partner to resolved
}

private fun OperationAnalysis.comparisonFinding(
    comparison: ConditionComparison,
    leftShape: OperationShape,
    left: OperationValue,
    right: OperationValue,
): Finding? {
    val operator = comparison.operator
    fun problem(code: String, reason: String): Finding? = finding(code, comparison.start, comparison.end) { "in `$it`: $reason" }
    val a = left.constant
    val b = right.constant
    if (a != null && b != null && !constantGateAdmits(a, b)) return problem("MVK_INVALID_COMPARE_TO_CONST",
        "`${comparison.left}` and `${comparison.right}` are both written-out values, so the comparison decides nothing about the document — put a field on one side")
    val customField = listOfNotNull(left.field, right.field).any { facts.effectiveFieldType(it) == FieldType.CUSTOM_FIELD }
    val words = CONDITION_OPERATOR_KEYWORDS.getValue(facts.conditionLanguage)
    val tolerance = listOf("DiffersWithToleranceRange1", "DiffersWithToleranceRange2", "DiffersWithToleranceRange5", "DiffersWithToleranceRange10")
        .mapNotNull { words[it] }
    if (operator in tolerance) {
        if (left.type == OperationType.Unknown || right.type == OperationType.Unknown) return null
        return if (left.numeric != null && right.numeric != null) null else problem("MVK_INVALID_TYPE_FOR_TOLERANCE_OP",
            "`$operator` compares two numbers within a tolerance, and `${comparison.left}` / `${comparison.right}` are not both numbers — compare them with `==` or `!=` instead")
    }
    if (operator == words["PatternMatched"] || operator == words["PatternViolated"]) {
        if (customField) return null
        if (leftShape !is OperationShape.Read) return problem("MVK_INVALID_PARAMETER_FOR_PATTERN_COMPARISON",
            "`$operator` reads one declaration's value against a pattern; put the field on the left and the pattern on the right")
        if (left.type == OperationType.Unknown || right.type == OperationType.Unknown) return null
        if (left.type != OperationType.Text || right.type != OperationType.Text) return problem("MVK_INVALID_TYPE_FOR_PATTERN_COMPARISON",
            "`$operator` matches text against a pattern; `${comparison.left}` / `${comparison.right}` are not both text")
        val pattern = (right.constant as? WrittenConstant.Text)?.value ?: return problem("MVK_MISSING_CONST_IN_PATTERN_COMP",
            "`$operator` needs a written-out pattern, since A12 reads it while checking the model")
        val listed = kernelPatternBlacklist(pattern) ?: return null
        return problem("MVK_INVALID_PATTERN", "the pattern `${comparison.right}` carries $listed, which A12's admission gate refuses; escaping the character does not exempt it")
    }
    if (left.type == OperationType.Unknown || right.type == OperationType.Unknown) return null
    val equality = operator == "==" || operator == "!="
    if (!equality && (left.numeric == null && left.temporal == null || right.numeric == null && right.temporal == null))
        return problem("MVK_INVALID_TYPE_FOR_COMPARISON",
            "`$operator` orders its two sides, which only numbers and dates can be — `${comparison.left}` / `${comparison.right}` cannot; compare them with `==` or `!=` instead")
    if (equality) {
        val confirm = if (left.type == OperationType.Confirm) left else right.takeIf { it.type == OperationType.Confirm }
        if (confirm != null) {
            val partner = if (left === confirm) right else left
            val wrote = if (left === confirm) comparison.right else comparison.left
            if (partner.type == OperationType.Confirm && operator == "==") return problem("MVK_INVALID_COMPARE_TO_YES",
                "`${comparison.left}` and `${comparison.right}` are both `confirm` declarations, which can only ever be true, so `==` between them decides nothing — write `!=` to ask whether exactly one of them is confirmed")
            if (partner.type != OperationType.Confirm && (partner.constant as? WrittenConstant.BooleanConstant)?.yes != true)
                return problem("MVK_INVALID_COMPARE_TO_YES",
                    "a `confirm` declaration records a yes and has no spelling for a no, so it stands only against `True` or another `confirm` — `$wrote` is not such a partner; write `!= True` for the unconfirmed side")
            return null
        }
        val boolean = if (left.type == OperationType.Boolean && left.constant == null) left
            else right.takeIf { it.type == OperationType.Boolean && it.constant == null }
        if (boolean != null) {
            val partner = if (left === boolean) right else left
            return if (partner.type == OperationType.Boolean) null else problem("MVK_INVALID_COMPARE_TO_YESNO",
                "a `boolean` declaration stands only against `True`, `False` or another `boolean` — `${comparison.left}` / `${comparison.right}` are not such a pair")
        }
    }
    val date = left.temporal ?: right.temporal
    if (date != null) {
        val partner = if (left.temporal != null) right.temporal else left.temporal
        val agrees = partner != null && (facts.hasBaseYear || (Component.YEAR in date.components) == (Component.YEAR in partner.components)) &&
            namesADate(date.components) == namesADate(partner.components) &&
            (!equality || namesATime(date.components) == namesATime(partner.components))
        return if (agrees) null else problem("MVK_INVALID_COMPARE_TO_DATE",
            "`${comparison.left}` / `${comparison.right}` need compatible date components: year, calendar presence and, for equality, clock presence")
    }
    val range = left.type as? OperationType.Range ?: right.type as? OperationType.Range
    if (range != null) {
        val partner = if (left.type is OperationType.Range) right.type else left.type
        return if (partner is OperationType.Range && range.components == partner.components) null
            else problem("MVK_INVALID_COMPARE_TO_DATE_RANGE", "a date range compares only to a date range declaring the same components")
    }
    if (!equality) return null
    val textual = setOf(OperationType.Text, OperationType.Enumeration)
    if (left.type in textual && right.numeric != null || right.type in textual && left.numeric != null)
        return problem("MVK_INVALID_COMPARE_TO_ENUM_OR_STRING",
            "one side of `${comparison.left}` / `${comparison.right}` is text or an enumeration and the other is a number — compare a number against a number")
    if (customField) return null
    for ((enumeration, partner) in listOf(left to right, right to left)) {
        val field = enumeration.typedBy?.takeIf { enumeration.type == OperationType.Enumeration } ?: continue
        val constant = (partner.domainConstant as? WrittenConstant.Text)?.value ?: continue
        val values = facts.enumerationBodyOf(field)?.declaredEnumValues() ?: continue
        if (constant !in values) return problem("MVK_INVALID_STRING_CONSTANT_FOR_ENUM_COMPARISON",
            "`${field.path}` declares ${values.joinToString { "`$it`" }}, and `$constant` is none of them — compare against a stored value, not its display label")
    }
    displayDefect(left, right)?.let { (code, reason) -> return problem(code, reason) }
    val number = left.numeric ?: right.numeric ?: return null
    val partnerValue = if (left.numeric != null) right else left
    if (partnerValue.constant is WrittenConstant.BooleanConstant) return problem("MVK_INCONSISTENT_TYPES_COMPARED",
        "`${comparison.left}` / `${comparison.right}` compare a number with a boolean constant; use a number on both sides")
    val partner = partnerValue.numeric ?: return null
    val suppressed = (element.body?.get("errorCodesToSuppress") as? JsonArray).orEmpty()
        .any { (it as? JsonPrimitive)?.content == SUPPRESSIBLE } || text.javaTrimmedStart().startsWith("@") &&
        text.substringBefore(')').contains(SUPPRESSIBLE)
    if (suppressed) return null
    val x = number.scale
    val y = partner.scale
    if (x != null && y != null && (x == y || (if (x < y) number else partner).expandable)) return null
    return problem(SUPPRESSIBLE,
        "`${comparison.left}` carries ${x ?: "an undetermined number of"} fractional digits and `${comparison.right}` carries ${y ?: "an undetermined number of"}; round one side to the other's scale, or compare with `<` / `>`")
}

/**
 * KF407's label comparison between two declarations in declared locales. Two Enumerations are compared by type
 * whatever is written around them; a text takes part only as an immediate field read, so a braced text partner
 * is outside the gate while a braced Enumeration is not (SPEC-2026-10-09-02).
 */
private fun OperationAnalysis.displayDefect(left: OperationValue, right: OperationValue): Pair<String, String>? {
    val a = left.typedBy ?: return null
    val b = right.typedBy ?: return null
    fun labels(value: OperationValue, field: TrackedDeclaration): Map<String, Map<String, String>>? {
        if (facts.effectiveFieldType(field) == FieldType.STRING) return if (value.field != null) emptyMap() else null
        val body = facts.enumerationBodyOf(field) ?: return null
        if (body["tableName"] != null) return null
        val values = body["values"] as? JsonArray ?: return null
        val entries = values.map { it as? JsonObject ?: return null }
        return facts.declaredLocales.associateWith { locale -> entries.associate { entry ->
            val token = entry.string("value") ?: return null
            val label = (entry["label"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
                .lastOrNull { it.string("locale")?.let(::localeKeyOrNull) == locale }?.string("text")
            token to (label ?: token)
        } }
    }
    val here = labels(left, a) ?: return null
    val there = labels(right, b) ?: return null
    fun displayed(labels: Map<String, Map<String, String>>) = labels.values.any { it.any { (token, label) -> token != label } }
    if (displayed(here) != displayed(there)) return "MVK_INVALID_COMPARE_ENUM_TO_STRING" to
        "`${a.path}` and `${b.path}` disagree about display values; compare two declarations that both label their values or neither does"
    if (!displayed(here)) return null
    for (locale in facts.declaredLocales) {
        val x = here[locale] ?: continue
        val y = there[locale] ?: continue
        val reverse = y.entries.associate { (token, label) -> label to token }
        if (x.any { (token, label) -> y[token]?.let { it != label } == true || reverse[label]?.let { it != token } == true })
            return "MVK_INVALID_COMPARE_ENUM_TO_ENUM" to
                "`${a.path}` and `${b.path}` have conflicting token/display mappings in `$locale` — use the same mappings"
    }
    return null
}

internal fun TrackedFactBase.operationFindings(element: TrackedDeclaration): List<Finding> =
    operationContributions(element).map { it.finding }

internal fun TrackedFactBase.operationContributions(element: TrackedDeclaration): List<ConditionFinding> {
    val execution = conditionExecution(element) ?: return emptyList()
    val analysis = OperationAnalysis(this, element, execution.carrier, execution.recognition)
    analysis.analyze()
    return analysis.contributions
}
