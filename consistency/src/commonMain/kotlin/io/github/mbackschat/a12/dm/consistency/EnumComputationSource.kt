package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.callsIn
import io.github.mbackschat.a12.dm.consistency.syntax.soleFieldValueReadIn
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * What a computation whose TARGET is an enumeration may compute from.
 *
 * `NormalisierteBerechnungsFormHelper.checkBerechnungsFunktion` adds one gate no other target kind carries:
 * the operation must be a string constant the target declares, a field read of an enumeration whose values
 * the target COVERS, or a `FirstFilledValue` over such reads — and nothing else. The kernel's own note gives
 * the reason: a computed value outside the domain has no display value to show
 * ([KERNEL-FINDINGS KF411], `rulekit.validate.laws.EnumComputationSourceLawsTest`).
 *
 * **Coverage runs ONE way and a category substitutes its own tokens.** A source declaring FEWER values is
 * admitted and one declaring more is refused, so the law is subset containment rather than equality — and a
 * category access is judged by the category's token set, which is the one spelling that rescues a source the
 * target does not cover. Both halves are measured, because equality would pass every row a superset test
 * fails and reads identically on a mirrored pair.
 *
 * **This gate lives in the CALC module and runs after the parse, so three of its shapes never reach it** —
 * and that is what bounds the family rather than any choice of ours. A computation is lowered to a comparison
 * against its target, so the comparison walk answers first wherever it has a class: a constant outside the
 * domain is `MVK_INVALID_STRING_CONSTANT_FOR_ENUM_COMPARISON`, a display-bearing source against an
 * unlabelled target is `MVK_INVALID_COMPARE_ENUM_TO_STRING` ([KF407]), and a NUMBER source is
 * `MVK_INVALID_COMPARE_TO_ENUM_OR_STRING`. A TEXT source is the discriminating case: the comparison walk
 * ADMITS a plain text against a textless enumeration, so this gate is the only thing that catches it. The three
 * pre-empted shapes are the comparison families' to report, which read the lowered comparison
 * ([loweredComparisonOf], [KERNEL-FINDINGS KF483]).
 *
 * **Two operations are never judged here, because A12 never reaches this gate on them.** An operation that
 * reads the computed field draws the self-read class, and `checkBerechnungsFunktion` returns on it before this
 * gate; an unguarded alternative among several draws `MVK_ERROR_EMPTY_PRECONDITION` before its operation is
 * walked. Each stands down only its own operation: every other alternative is still judged, and a self-read in
 * a PRECONDITION is a separate walk that leaves the operation's gate standing
 * ([KERNEL-FINDINGS KF616]).
 *
 * **Silent on every operation shape this reader will not reduce**, which is a disclosed under-report: A12
 * refuses anything outside its three kinds, measured on a concatenation of the target's own declared value,
 * but the shapes between that and an aggregate were not measured and the comparison walk owns a class for
 * some of them. Silent too on a table-backed enumeration, on an unresolvable type definition or an `UnspecifiedType`, and
 * wherever the target or a source does not resolve; a resolvable definition is read as the type it defines.
 */
internal fun TrackedFactBase.enumComputationSourceContributions(element: TrackedDeclaration): List<DeclarationFinding> {
    if (element.declaredKind != DeclaredKind.COMPUTATION) return emptyList()
    val declared = element.declaredReference() ?: return emptyList()
    val targetPath = (declared.target as? Reference.Resolved)?.path ?: return emptyList()
    val target = declarationAt(targetPath) ?: return emptyList()
    val admitted = enumerationBodyOf(target)?.declaredEnumValues()?.toSet() ?: return emptyList()
    val body = element.body ?: return emptyList()
    val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
    // Asked only on a defect: the self-read walk behind it reads every member again.
    val unjudged by lazy { unguardedAmongSeveralAlternatives(body) + operationsReadingTheirTarget(element) }
    return conditionTexts(element.kind, body)
        .filter { (member, _) -> isOperationMember(member) }
        .mapNotNull { (member, text) ->
            sourceDefect(text, admitted, target, group, element)?.takeIf { member !in unjudged }?.let {
                DeclarationFinding(element.key, DeclarationStage.CALCULATION_ALTERNATIVES, finding(it, member, element),
                    walkPositionOf(member))
            }
        }
}

private const val FIRST_FILLED_VALUE = "FirstFilledValue"

/**
 * The defect this operation carries, or null where it carries none and where this reader may not judge.
 *
 * The two arms are A12's own: one source read, or a `FirstFilledValue` over several. Every other shape is
 * left alone — see the family doc for why that silence is a disclosed under-report rather than a verdict.
 */
private fun TrackedFactBase.sourceDefect(
    operation: String,
    admitted: Set<String>,
    target: TrackedDeclaration,
    declaringGroup: String,
    element: TrackedDeclaration,
): String? {
    firstFilledOperands(operation, element)?.let { operands ->
        return operands.firstNotNullOfOrNull { operand ->
            readDefect(operand, admitted, target, declaringGroup, element, aFirstFilledValueOperand = true)
        }
    }
    return readDefect(operation, admitted, target, declaringGroup, element, aFirstFilledValueOperand = false)
}

/** The operand texts of a sole `FirstFilledValue` call, or null when the operation is not one. */
private fun TrackedFactBase.firstFilledOperands(operation: String, element: TrackedDeclaration): List<String>? {
    val carrier = CONDITION_OPERATOR_KEYWORDS[conditionLanguage]?.get(FIRST_FILLED_VALUE) ?: return null
    val call = callsIn(operation, conditionLanguage, syntaxEntryOf("operation")).singleOrNull() ?: return null
    if (call.operator != carrier) {
        return null
    }
    return call.operands.map { it.text }
}

/**
 * What one SOURCE read is to this gate, or null where the parser answers first and where it cannot be read.
 *
 * For a sole read the display check is the parser's, not this gate's — it draws its own class and pre-empts
 * this one — so a bearing mismatch is silence there. A `FirstFilledValue` operand is the opposite: A12 asks
 * the display check of it only in this gate (`checkDisplayValues`' own note), so a bearing mismatch there is
 * this class in either direction ([KERNEL-FINDINGS KF617]). Two operands that both bear labels are compared
 * label by label, which this reader does not do, so that case stays silent.
 */
private fun TrackedFactBase.readDefect(
    text: String,
    admitted: Set<String>,
    target: TrackedDeclaration,
    declaringGroup: String,
    element: TrackedDeclaration,
    aFirstFilledValueOperand: Boolean,
): String? {
    // The bracket's own reader spells a climbing `..`, which the bare-spec reader refuses as a token.
    // The source is the whole operation, so no filter body can hold it.
    val (written, keyed, category) = categorySpecIn(text, declaringGroup, element, inAFilterBody = false)
        ?: categorySpecIn(text, declaringGroup, element, inAFilterBody = false) {
            soleFieldValueReadIn(text, conditionLanguage)?.path
        }
        ?: return null
    val addressed = addressedTargetOf(written, declaringGroup, element, keyed, everyLevelAddressed = false)
        ?: return null
    val source = declarationAt(addressed)?.takeIf { it.declaredKind == DeclaredKind.FIELD } ?: return null
    val unassignable = when (effectiveFieldType(source)) {
        FieldType.STRING -> "a text field"
        FieldType.CUSTOM_FIELD -> "a custom-typed field"
        FieldType.ENUMERATION -> null
        else -> return null
    }
    if (unassignable != null) {
        return "reads `${source.path}`, which is $unassignable — A12 assigns an enumeration only from a " +
            "constant it declares, an enumeration it covers, or a `$FIRST_FILLED_VALUE` over those"
    }
    val body = enumerationBodyOf(source) ?: return null
    if (body["tableName"] != null) {
        return null
    }
    val values = sourceValues(body, category) ?: return null
    if (category == null && bearsDisplayValues(source) != bearsDisplayValues(target)) {
        if (!aFirstFilledValueOperand) return null
        val (bearing, plain) = if (bearsDisplayValues(source)) source.path to target.path else target.path to source.path
        return "reads `${source.path}` inside `$FIRST_FILLED_VALUE`, and `$bearing` carries display labels while " +
            "`$plain` carries none — A12 checks that agreement for a `$FIRST_FILLED_VALUE` operand here. Give " +
            "both the same labels, or neither"
    }
    val uncovered = values - admitted
    if (uncovered.isEmpty()) {
        return null
    }
    val where = if (category == null) "`${source.path}`" else "the category `$category` of `${source.path}`"
    return "reads $where, which declares ${uncovered.sorted().joinToString { "`$it`" }} — a value " +
        "`${target.path}` does not, and A12 refuses an assignment whose source can produce a value the " +
        "target cannot show. Project the source through a category whose tokens the target covers, or widen " +
        "the target's value list"
}

/**
 * The value set a source contributes: its own tokens, or the CATEGORY's where one is projected.
 *
 * Null wherever either list cannot be read, which leaves the source unjudged — the pairing is positional and
 * a category naming no such name contributes nothing this gate may reason about.
 */
private fun sourceValues(body: JsonObject, category: String?): Set<String>? {
    if (category == null) {
        return body.declaredEnumValues()?.toSet()
    }
    val categories = body["categories"] as? JsonArray ?: return null
    val named = categories.filterIsInstance<JsonObject>()
        .firstOrNull { (it["name"] as? JsonPrimitive)?.takeIf { name -> name.isString }?.content == category }
        ?: return null
    val tokens = named["values"] as? JsonArray ?: return null
    return tokens.map { token ->
        when (token) {
            is JsonPrimitive -> token.takeIf { it.isString }?.content ?: return null
            is JsonObject -> (token["value"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            else -> return null
        }
    }.toSet()
}

/**
 * Whether this enumeration bears DISPLAY values — a text differing from its own token, A12's own test
 * (`IEnumerationType.hasDisplayValues`), so labels that only repeat their tokens bear none. An enumeration copy
 * and `+` both ask it of a raw declaration ([KF396]).
 *
 * Not shared with the comparison family, which asks it of a per-locale map it has already built. The predicate is
 * one line and the shapes are not, so a shared reader would have to carry both.
 */
internal fun TrackedFactBase.bearsDisplayValues(field: TrackedDeclaration): Boolean {
    val values = enumerationBodyOf(field)?.get("values") as? JsonArray ?: return false
    return values.filterIsInstance<JsonObject>().any { entry ->
        val token = (entry["value"] as? JsonPrimitive)?.contentOrNull
        (entry["label"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
            .any { label -> (label["text"] as? JsonPrimitive)?.contentOrNull.let { it != null && it != token } }
    }
}

private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()

private fun finding(detail: String, member: String, element: TrackedDeclaration): Finding = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    "MVK_ERROR_INVALID_ENUM_CALCULATION",
    "in `$member`: the computation $detail",
    element.path,
)
