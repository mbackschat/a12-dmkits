package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.CallOperand
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionCall
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * The single-entity value operators' kind and star gates, followed by each field operator's type gate.
 * `CurrentRepetition` requires a group where the other arms require a field (KF122); the generated
 * `PathGatePopulation.repetitionRows` locks kind before star, and read failure before either local gate.
 *
 * `CheckFeldOpImpl.check` gates the operand as a field without a repetition star and then switches on the
 * operator token — so the four share a head and part company completely after it
 * ([KERNEL-FINDINGS KF395], `rulekit.validate.laws.FieldValueOperatorOperandLawsTest`):
 *
 * | the operator | after the shared head | otherwise |
 * |---|---|---|
 * | `AbsValue` | no category, and a Number declaration | `MVK_CATEGORY_NOT_ALLOWED`, `MVK_NO_NUMBER` |
 * | `FieldValueAsString` | no category, and a Number declaration | `MVK_CATEGORY_NOT_ALLOWED`, `MVK_NO_NUMBER` |
 * | `Length` | no category, and a string-LIKE declaration | `MVK_CATEGORY_NOT_ALLOWED`, `MVK_NO_STRING` |
 * | `FieldValueAsNumber` | a declaration A12 can convert | `MVK_NO_CONVERT_IN_NUMBER` |
 *
 * **`FieldValueAsString` demands a NUMBER**, which inverts the reading its name invites: it stringifies a
 * numeric value, so a string operand is refused. Measured against all four declared classes.
 *
 * **`FieldValueAsNumber` is the one arm with no category gate**, deliberately — the kernel source carries a
 * standing note at the switch asking each new arm to decide that for itself. It reads the CATEGORY's values
 * when one is named and the enumeration's own otherwise, which is the row that tells the two readings apart.
 *
 * **Its string arm reads the DECLARATION and never a stored value**: a bounded `maxLength` of at most 15 and
 * exactly the pattern `[0-9]+`. A five-character string with no pattern is refused, and so is a `[0-9]+`
 * string with no length bound.
 *
 * **Two of this construct's classes are left to A12, both measured rather than assumed.**
 * `MVK_INVALID_RAW_TYPE` sits behind the conversion gate and fires only on a `noValueValidation` string,
 * which the conversion's own pattern demand excludes: declare both and A12 answers the pair
 * `MVK_STRING_TYPE_PARAMS_INCONSISTENT, MVK_INVALID_RAW_TYPE`, drop the pattern to make the declaration legal
 * and the conversion gate answers `MVK_NO_CONVERT_IN_NUMBER` alone. So the class never arrives on its own,
 * and reporting it would mean reporting a declaration A12 already refuses for its own inconsistency. And
 * `MVK_INVALID_TABLE_ENUM` needs an enumeration carrying a `tableName`, which makes a document model
 * UNREADABLE before any consistency check runs ([KERNEL-FINDINGS](../../../../../../../../../../docs/KERNEL-FINDINGS.md)
 * "the stored shape").
 *
 * **Silent on a declaration whose effective type this reader does not resolve** — a typedef, an unspecified
 * type — and on an enumeration whose declared values it cannot read as strings.
 */
internal fun TrackedFactBase.fieldValueOperatorOperandFindings(): List<Finding> = declarations().flatMap { element ->
    fieldValueOperatorContributions(element).map { it.finding }
}

internal fun TrackedFactBase.fieldValueOperatorContributions(
    element: TrackedDeclaration,
    execution: ConditionExecution? = conditionExecution(element),
): List<ConditionFinding> {
    val keywords = CONDITION_OPERATOR_KEYWORDS[conditionLanguage] ?: return emptyList()
    val carriers = ARM_OF.mapNotNull { (id, arm) -> keywords[id]?.let { it to arm } }.toMap()
    if (carriers.isEmpty()) {
        return emptyList()
    }
    val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
    return callContributions(element, execution) { member, call ->
        val arm = carriers[call.operator] ?: return@callContributions null
        valueOperandDefect(call, arm, group, element)?.let { finding(it, call, element, member) }
    }
}

/** One arm of A12's switch on the operator token. */
private enum class ValueOperatorArm { ABSOLUTE, LENGTH, AS_NUMBER, AS_STRING, REPETITION }

private val ARM_OF = mapOf(
    "CurrentRepetition" to ValueOperatorArm.REPETITION,
    "AbsValue" to ValueOperatorArm.ABSOLUTE,
    "Length" to ValueOperatorArm.LENGTH,
    "FieldValueAsNumber" to ValueOperatorArm.AS_NUMBER,
    "FieldValueAsString" to ValueOperatorArm.AS_STRING,
)

/** A12's `MAX_ALLOWED_DIGITS`, read straight off `CheckImplLenientBaseClass`. */
private const val MOST_CONVERTIBLE_DIGITS = 15

/** The one pattern A12's string conversion accepts, compared literally. */
private const val DIGIT_PATTERN = "[0-9]+"

/**
 * The declared types the number conversion refuses outright, without reading anything else about them.
 *
 * `checkIsConvertableToNumber` branches on `IStringType` then `IEnumerationType` and refuses every other
 * declaration, so this is that remainder, named rather than derived so a reader sees which classes were
 * measured. A typedef and an unspecified type are absent on purpose: this reader does not resolve them to an
 * effective type, so it leaves them to A12.
 */
private val PLAINLY_UNCONVERTIBLE_TYPES = setOf(
    FieldType.NUMBER,
    FieldType.BOOLEAN,
    FieldType.CONFIRM,
    FieldType.CUSTOM_FIELD,
    FieldType.DATE_RANGE,
) + TEMPORAL_DECLARED_TYPES

private class ValueOperandDefect(val code: String, val detail: String)

/** The first gate this call fails, in A12's own order, or null where none fails and where it may not judge. */
private fun TrackedFactBase.valueOperandDefect(
    call: ConditionCall,
    arm: ValueOperatorArm,
    declaringGroup: String,
    element: TrackedDeclaration,
): ValueOperandDefect? {
    val operand = call.operands.singleOrNull() ?: return null
    val (written, keyed, category) = operandSpec(operand, declaringGroup, element) ?: return null
    val target = addressedTargetOf(written, declaringGroup, element, keyed, call.inAFilterBody) ?: return null
    val declared = declarationAt(target) ?: return null
    val requiredKind = if (arm == ValueOperatorArm.REPETITION) DeclaredKind.GROUP else DeclaredKind.FIELD
    if (declared.declaredKind != requiredKind) {
        return when (declared.declaredKind) {
            DeclaredKind.GROUP -> ValueOperandDefect("MVK_NO_GROUPS_ALLOWED", "reads `$target`, which is a group — read one field of it")
            DeclaredKind.FIELD -> ValueOperandDefect("MVK_NO_FIELDS_ALLOWED", "reads `$target`, which is a field — read its group's repetition")
            else -> null
        }
    }
    if ('*' in written) {
        return ValueOperandDefect(
            "MVK_NO_WILDCARDS_ALLOWED",
            "reads `$target` through a repetition star, and this operator reads one addressed value",
        )
    }
    if (arm == ValueOperatorArm.REPETITION) return null
    if (arm != ValueOperatorArm.AS_NUMBER && category != null) {
        return ValueOperandDefect(
            "MVK_CATEGORY_NOT_ALLOWED",
            "reads the category `$category` of `$target`, and only `FieldValueAsNumber` reads one",
        )
    }
    val type = declared.declaredFieldType() ?: return null
    return when (arm) {
        ValueOperatorArm.REPETITION -> null
        ValueOperatorArm.ABSOLUTE, ValueOperatorArm.AS_STRING ->
            if (type in PLAINLY_NON_NUMBER_TYPES) {
                ValueOperandDefect(
                    "MVK_NO_NUMBER",
                    "reads `$target`, declared `$type` — this operator reads a number",
                )
            } else {
                null
            }
        ValueOperatorArm.LENGTH ->
            if (type in PLAINLY_NON_STRING_TYPES) {
                ValueOperandDefect(
                    "MVK_NO_STRING",
                    "reads `$target`, declared `$type` — this operator reads a string or a custom type",
                )
            } else {
                null
            }
        ValueOperatorArm.AS_NUMBER -> conversionDefect(declared, type, category, target)
    }
}

/** The conversion arm's three branches, in the order `checkIsConvertableToNumber` takes them. */
private fun conversionDefect(
    declared: TrackedDeclaration,
    type: FieldType,
    category: String?,
    target: String,
): ValueOperandDefect? {
    fun refuse(because: String) =
        ValueOperandDefect("MVK_NO_CONVERT_IN_NUMBER", "reads `$target`, which $because")
    return when (type) {
        FieldType.STRING -> {
            val body = FieldType.STRING.bodyIn(declared.body?.get("fieldType") as? JsonObject)
            val maxLength = (body?.get("maxLength") as? JsonPrimitive)?.intOrNull
            val pattern = (body?.get("pattern") as? JsonPrimitive)?.contentOrNull
            when {
                maxLength == null || maxLength > MOST_CONVERTIBLE_DIGITS ->
                    refuse(
                        "declares no `maxLength` of at most $MOST_CONVERTIBLE_DIGITS — the conversion reads " +
                            "the declaration, never a stored value",
                    )
                pattern != DIGIT_PATTERN ->
                    refuse("does not declare the pattern `$DIGIT_PATTERN`, the only one this conversion takes")
                else -> null
            }
        }
        FieldType.ENUMERATION -> {
            val values = declared.convertibleValues(category) ?: return null
            val offending = values.firstOrNull { !it.readsAsANumber() } ?: return null
            refuse("declares the value `$offending`, which is not a number this conversion can read")
        }
        in PLAINLY_UNCONVERTIBLE_TYPES ->
            refuse("is declared `$type`, and the conversion takes a string or an enumeration only")
        else -> null
    }
}

/**
 * The values this conversion reads — the named category's own, or the enumeration's, as A12 chooses them.
 *
 * Null where the declaration is not the shape this reader knows, which leaves the call unjudged rather than
 * refusing on a reading.
 */
private fun TrackedDeclaration.convertibleValues(category: String?): List<String>? {
    val body = enumerationBody() ?: return null
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
 * Whether this spelling is one A12's conversion certainly reads as a number.
 *
 * Deliberately narrower than `NumberUtils.isParsable` in the SAFE direction: a spelling this returns true for
 * may still be refused by A12 (`1.2.3` passes here), which under-reports, while anything carrying a character
 * outside the numeric alphabet is refused by both. The digit bound is A12's own, counted after stripping the
 * sign and the point exactly as `checkParsableToNumber` does.
 */
private fun String.readsAsANumber(): Boolean {
    if (isEmpty() || any { it !in "0123456789.-+" }) {
        return false
    }
    return replace(".", "").replace("-", "").length <= MOST_CONVERTIBLE_DIGITS
}

private fun finding(
    defect: ValueOperandDefect,
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
