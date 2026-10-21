package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.callsIn
import io.github.mbackschat.a12.dm.consistency.syntax.maxLengthDeclaration

/**
 * What `Length` over a RAW-TYPE field may look like.
 *
 * A STRING declaring `noValueValidation` is a raw type: its value is never interned, so A12 closes every
 * value window on it at the model tier. `Length` is the one near-value read it admits, and only as the whole
 * rule condition `Length(f) > c` or its mirror `c < Length(f)` — which is not a rule but a disguised
 * max-length declaration, lifted out of the generated validation ([KERNEL-FINDINGS KF35],
 * `rulekit.validate.laws.NvvRawTypeLawsTest`). Any other layout is `MVK_INVALID_LENGTH_OF_RAW_TYPE`, and a
 * COMPUTATION may not take the length of such a field in any member at all — not even in the layout a rule
 * admits.
 *
 * **Two gates in A12's order, because reporting them in the other one would name a code A12 never reached.**
 * The SHAPE is decided first; only a text that passes it reaches the constant domain, where a decimal point
 * or a signed-32-bit overflow draws `MVK_INTERNAL_ERROR` from `AnalyseService.extractConstant`. A text with
 * both defects draws the shape code, measured.
 *
 * **Redundant parentheses are TRANSPARENT to the shape gate.** `(Length(f) > 5)` is admitted, so the reader
 * this family uses strips enclosing pairs before matching; a token-exact match would refuse a legal model.
 *
 * **The restriction is the FIELD's, not the layout's.** An ordinary string takes `Length` in any position
 * with any constant, so every row here has its ordinary-field control — the axis that separates "this shape
 * is wrong" from "this field may not be read".
 *
 * **What it deliberately does NOT report.** A12 closes the other value windows with `MVK_INVALID_RAW_TYPE` —
 * a comparison, a value list, a computation's bare copy. Deciding those needs to know which operand
 * POSITIONS of which operator are value positions, which is the per-operator catalog this checker does not
 * reproduce, so the class is left to the Kernel and recorded as a gap rather than guessed at. Silence here
 * under-reports, which is safe; a guess would not be.
 */
internal fun TrackedFactBase.rawTypeLengthFindings(): List<Finding> =
    declarations().flatMap { rawTypeLengthFindings(it) }

internal fun TrackedFactBase.rawLengthContributions(
    element: TrackedDeclaration,
    execution: ConditionExecution? = conditionExecution(element),
): List<ConditionFinding> = execution?.postParse(PostParseCheck.RAW_LENGTH) {
    rawTypeLengthFindings(element)
}.orEmpty()

private fun TrackedFactBase.rawTypeLengthFindings(element: TrackedDeclaration): List<Finding> {
    val length = CONDITION_OPERATOR_KEYWORDS[conditionLanguage]?.get("Length") ?: return emptyList()
    val body = element.body ?: return emptyList()
    val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
    return conditionTexts(element.kind, body).mapNotNull { (member, text) ->
        val raw = rawTypeRead(text, member, group, element.origin, length) ?: return@mapNotNull null
        rawLengthOffence(text, member, raw, length, element)
    }
}

/** The raw-type field a `Length` call in [text] names, or null when none does. */
private fun TrackedFactBase.rawTypeRead(
    text: String,
    member: String,
    declaringGroup: String,
    origin: DeclarationOrigin,
    length: String,
): String? = callsIn(text, conditionLanguage, syntaxEntryOf(member))
    .filter { it.operator == length }
    .firstNotNullOfOrNull { call ->
        val written = call.operands.singleOrNull()?.path ?: return@firstNotNullOfOrNull null
        val resolved = resolveEntity(declaringGroup, written, origin) as? Reference.Resolved
            ?: return@firstNotNullOfOrNull null
        resolved.path.takeIf { isRawType(it) }
    }

/** Whether [path] declares a STRING with `noValueValidation` — the only kind A12 treats as a raw type. */
private fun TrackedFactBase.isRawType(path: String): Boolean {
    val body = declarationAt(path, DeclaredKind.FIELD)
        ?.stringTypeBody() ?: return false
    return body.flag("noValueValidation") == true
}

private fun rawLengthOffence(
    text: String,
    member: String,
    raw: String,
    length: String,
    element: TrackedDeclaration,
): Finding? {
    // A computation may not reach a raw type through `Length` in ANY member — the max-length reading is
    // A12's rule-side gate alone, so the shape a rule admits is refused here.
    if (element.kind != "Rule") {
        return finding(
            MVK_INVALID_LENGTH_OF_RAW_TYPE,
            "in `$member`: `$raw` declares `noValueValidation`, and a computation may not take its length " +
                "in any member — not even in the whole-condition form a rule admits",
            element.path,
        )
    }
    val constant = maxLengthDeclaration(text, length)
        ?: return finding(
            MVK_INVALID_LENGTH_OF_RAW_TYPE,
            "in `$member`: `$raw` declares `noValueValidation`, so its length is readable only as the WHOLE " +
                "condition `$length($raw) > <constant>` or the mirrored `<constant> < $length($raw)` — the " +
                "max-length declaration A12 lifts out of the generated validation, not a comparison to embed",
            element.path,
        )
    if (constant.asciiIntOrNull() != null) {
        return null
    }
    return finding(
        MVK_INTERNAL_ERROR,
        "in `$member`: the max-length declaration over `$raw` names `$constant`, and A12 reads this " +
            "constant with `Integer.parseInt` — it must be a decimal-point-free signed-32-bit integer, so " +
            "`-2147483648` through `2147483647` and no decimal form, `5.0` included",
        element.path,
    )
}

private fun finding(code: String, explain: String, at: String) =
    Finding(FindingCategory.A12_CONSTRAINT, FindingSeverity.ERROR, code, explain, at)

private const val MVK_INVALID_LENGTH_OF_RAW_TYPE = "MVK_INVALID_LENGTH_OF_RAW_TYPE"

/** A12's own key for the constant-domain failure, reached from `AnalyseService.extractConstant`. */
private const val MVK_INTERNAL_ERROR = "MVK_INTERNAL_ERROR"
