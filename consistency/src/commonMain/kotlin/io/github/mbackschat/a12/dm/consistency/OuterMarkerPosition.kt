package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.Token
import io.github.mbackschat.a12.dm.consistency.syntax.TokenKind
import io.github.mbackschat.a12.dm.consistency.syntax.callsIn
import io.github.mbackschat.a12.dm.consistency.syntax.tokenize

/**
 * Where the `$` outer marker may stand: inside a `Having` filter, and nowhere else.
 *
 * `CheckEntitySpecImpl.checkOuter` is the marker's single locus and names no carrier —
 * `ctx.outer != null && !isInFilterCondition()` refuses with `MVK_INVALID_OUTER_ITERATION` — so the law is
 * POSITIONAL rather than operator-specific. Measured on a bare comparison and on two aggregate slots alike
 * ([KERNEL-FINDINGS KF374], `rulekit.validate.laws.OuterMarkerPositionLawsTest`,
 * `…GroupOperandCarrierAdmissionLawsTest`), which is what lets one family read the whole condition instead
 * of each operator's slots.
 *
 * **RESOLUTION ANSWERS FIRST, and that is the row a checker needs rather than the law itself.** `checkOuter`
 * runs inside `checkCommon`, after the reference has been built, so a marked path naming nothing draws
 * `MVK_INVALID_ENTITY` and never reaches this gate — measured, against the same path unmarked as the
 * control. So a marker is reported only once its own address resolves, and a text-position reading that
 * skipped that would name this class on every mistyped marked path.
 *
 * **Inside a filter the marker is legal and still not free**: a marked reference is SKIPPED by the filter's
 * index walk, so it contributes no ridden level and cannot clear the scope gate on its own
 * ([filterBodyGateFindings]). That is a different family's class and this one stays out of it — which is why
 * the measured grid carries the marker inside a filter twice, alone and beside an in-scope reference.
 *
 * **Rules only.** Every measured row is a rule condition. `isInFilterCondition` is parser state that a
 * computation's own slots have not been measured against here, and the calculation module refuses several
 * constructs outright, so a computation carrying a marker is left alone rather than judged on transfer.
 */
internal fun TrackedFactBase.outerMarkerPositionFindings(): List<Finding> {
    val filterKeyword = CONDITION_OPERATOR_KEYWORDS[conditionLanguage]?.get(HAVING) ?: return emptyList()
    val unsound = declarationsWithUnsoundDsl()
    return declarations().filter { it.kind == "Rule" }.flatMap { element ->
        val body = element.body ?: return@flatMap emptyList()
        if (element.path in unsound) {
            return@flatMap emptyList()
        }
        val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
        conditionTexts(element.kind, body).flatMap { (member, text) ->
            if (OUTER_MARKER !in text) {
                return@flatMap emptyList()
            }
            markedPathsOutsideAFilter(text, member, filterKeyword)
                .filter { written -> resolveEntity(group, written, element.origin) is Reference.Resolved }
                .map { written -> finding(written, member, element) }
        }
    }
}

private const val HAVING = "Having"

/**
 * The paths of the `$`-marked references this text writes OUTSIDE every `Having` clause it carries.
 *
 * The clause bodies are cut from the text first, so what remains is the region A12 parses with
 * `isInFilterCondition` false. Cutting by the clause's own text removes every identical occurrence rather
 * than just the one, which can only drop a marker from consideration — the safe direction for a gate whose
 * wrong answer would be an over-rejection.
 */
private fun TrackedFactBase.markedPathsOutsideAFilter(
    text: String,
    member: String,
    filterKeyword: String,
): List<String> {
    var outside = text
    filterClauseTextsIn(text, member, filterKeyword).forEach { clause -> outside = outside.replace(clause, " ") }
    if (OUTER_MARKER !in outside) {
        return emptyList()
    }
    val tokens = tokenize(outside).filter { it.kind != TokenKind.COMMENT }
    return tokens.indices
        .filter { tokens[it].text == OUTER_MARKER }
        .mapNotNull { at -> pathRunAt(tokens, at + 1) }
}

/** Every `Having` clause body this text carries, as the operand reader sees it. */
private fun TrackedFactBase.filterClauseTextsIn(
    text: String,
    member: String,
    filterKeyword: String,
): List<String> =
    callsIn(text, conditionLanguage, syntaxEntryOf(member))
        .flatMap { call -> call.operands }
        .flatMap { operand -> operand.clauses }
        .filter { clause -> clause.keyword == filterKeyword }
        .map { clause -> clause.text }

/**
 * The path written by the run of tokens beginning at [from], or null where none begins there.
 *
 * Whitespace is insignificant between path tokens ([KERNEL-FINDINGS KF76]), so the run is rebuilt from the
 * token texts rather than sliced out of the source — a marker written `$ /Claim/Excess` names the same path
 * as one written tight against it.
 */
private fun pathRunAt(tokens: List<Token>, from: Int): String? {
    val run = StringBuilder()
    var at = from
    while (at < tokens.size) {
        val token = tokens[at]
        val isName = token.kind == TokenKind.NAME || token.kind == TokenKind.QUOTED_NAME
        if (!isName && token.text !in PATH_STEPS) {
            break
        }
        run.append(token.text)
        at++
    }
    return run.toString().takeIf { it.isNotEmpty() && it.any { c -> c.isLetterOrDigit() } }
}

private val PATH_STEPS = setOf("/", "..", ".", "*")

private fun finding(written: String, member: String, element: TrackedDeclaration): Finding = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    "MVK_INVALID_OUTER_ITERATION",
    "in `$member`: `$OUTER_MARKER$written` marks an outer iteration outside any `Having` filter — the " +
        "marker reaches from a filter body back into the enclosing rule, so it means nothing where there " +
        "is no filter to reach out of; drop the marker or move the read into a filter",
    element.path,
)
