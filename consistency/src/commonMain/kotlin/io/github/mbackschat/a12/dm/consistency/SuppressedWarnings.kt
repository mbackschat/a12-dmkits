package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A computation's `errorCodesToSuppress` — a stored LIST that A12 can only ever honour one entry of.
 *
 * **It is not a set A12 inspects; it is TEXT A12 renders and then re-parses.**
 * `ComputationAlternativesJoiner.createPreamble` builds `@SuppressWarning(<codes joined by ",">)` and puts it
 * in front of the generated rule condition, so every law here follows from that rendering rather than from
 * any set semantics. Reading the member as a collection is what gets it wrong.
 *
 * **A second entry is a parse error whatever it spells.** The pragma takes one code, so two entries render
 * `@SuppressWarning(a,b)` and fail as `MVK_UNEXPECTED_TOKEN` — measured with the SUPPORTED code carried
 * twice, so nothing about validity can account for it (`ComputationSuppressionLawsTest`, kernel 30.8.1,
 * 2026-09-12). A blank entry renders `@SuppressWarning()` and a numeric one `@SuppressWarning(7)`, both the
 * same failure. This is the one place a plural member is single-valued, and a checker that admitted a list
 * would admit models A12 refuses.
 *
 * **Only `MVK_INVALID_COMPARE_DEC_PLACES` is suppressible**, which the Kernel grammar states and the rows
 * confirm: any other code draws `MVK_INVALID_SUPPRESSED_WARNING`, with the offending code echoed in the
 * message. The Java builder's allowlist therefore has a measured basis and is not an over-rejection.
 *
 * **An explicit `null` refuses the READER here**, unlike the rounding pair beside it ([KERNEL-FINDINGS
 * KF297]), because the member is bound `@NonNull List<String>` rather than through an `Optional` accessor —
 * a property of the binding, not of DM-JSON, and measured rather than carried across.
 */
fun PreparedModel.suppressedWarningFindings(): List<Finding> =
    elements.filter { it.declaredKind == DeclaredKind.COMPUTATION }.flatMap { suppressionFindings(it) }

/**
 * The single code A12's `@SuppressWarning` pragma accepts — the static assignment-scale gate.
 *
 * The checker compares an authored code against this one; it never reports it, because no family measures
 * decimal-place agreement. `CorrectiveTest` names it for that reason: it is the one code the corrective
 * projection carries that no report can deliver.
 */
internal const val SUPPRESSIBLE = "MVK_INVALID_COMPARE_DEC_PLACES"

private fun suppressionFindings(element: DeclaredElement): List<Finding> {
    // An envelope A12 cannot bind is the alternatives family's finding; naming it again would report one
    // unreadable computation twice in a single report.
    val body = element.body ?: return emptyList()
    val declared = body["errorCodesToSuppress"] ?: return emptyList()
    // `JsonNull` is not a `JsonArray`, so this one test catches it too; the two are told apart in the message
    // below, where the difference is load-bearing — `null` means delete the member, a container means rewrite it.
    if (declared !is JsonArray) {
        return listOf(
            unbuildable(
                element,
                "`errorCodesToSuppress` is ${if (declared is JsonNull) "an explicit `null`" else "not a JSON array"}" +
                    ", which A12's own reader refuses — omitting the member is what declares no suppression",
            ),
        )
    }
    if (declared.isEmpty()) {
        return emptyList()
    }
    // The rendering, not the list, is what A12 judges: everything below reads the pragma this array becomes.
    val spelled = declared.map { (it as? JsonPrimitive)?.content }
    if (declared.size > 1) {
        return listOf(
            Finding(
                FindingCategory.A12_CONSTRAINT,
                FindingSeverity.ERROR,
                "MVK_UNEXPECTED_TOKEN",
                "this computation suppresses ${declared.size} codes, and A12 renders them into a single " +
                    "`@SuppressWarning(...)` pragma that takes exactly one — a second entry fails to parse " +
                    "whatever it spells. Keep at most `$SUPPRESSIBLE`.",
                element.path,
            ),
        )
    }
    val only = spelled.single()
    if (only == null || only.isBlank() || !only.isCodeToken()) {
        return listOf(
            Finding(
                FindingCategory.A12_CONSTRAINT,
                FindingSeverity.ERROR,
                "MVK_UNEXPECTED_TOKEN",
                "`errorCodesToSuppress` renders into `@SuppressWarning(${only ?: ""})`, which A12's condition " +
                    "parser cannot read — the entry must be a bare code token, and the only suppressible one " +
                    "is `$SUPPRESSIBLE`",
                element.path,
            ),
        )
    }
    if (only == SUPPRESSIBLE) {
        return emptyList()
    }
    return listOf(
        Finding(
            FindingCategory.A12_CONSTRAINT,
            FindingSeverity.ERROR,
            "MVK_INVALID_SUPPRESSED_WARNING",
            "`$only` is not a warning A12 lets a computation suppress — `$SUPPRESSIBLE` is the only one",
            element.path,
        ),
    )
}

/**
 * Whether the entry would survive the condition parser as the pragma's single argument.
 *
 * A code token is what the grammar admits there, so a numeric or punctuated entry is a parse failure rather
 * than an unsupported code — the two draw different A12 verdicts and must not be merged.
 */
private fun String.isCodeToken(): Boolean =
    isNotEmpty() && first().isLetter() && all { it.isLetterOrDigit() || it == '_' }

private fun unbuildable(element: DeclaredElement, explain: String): Finding = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    CK_MODEL_NOT_BUILDABLE,
    "$explain. A12 cannot build the model from this, so no consistency verdict about it exists",
    element.path,
)
