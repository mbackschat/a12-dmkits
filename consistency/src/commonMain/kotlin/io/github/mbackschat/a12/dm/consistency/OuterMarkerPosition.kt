package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.ReferenceSpan
import io.github.mbackschat.a12.dm.consistency.syntax.markedReferenceSpansIn
import io.github.mbackschat.a12.dm.consistency.syntax.withoutFilterConditions

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
            // `checkOuter` runs in the marked spec's own `checkEntitySpec`, once the spec is read.
            markedPathsOutsideAFilter(text)
                .filter { marked -> resolveEntity(group, marked.written, element.origin) is Reference.Resolved }
                .map { marked -> finding(marked.written, member, element, TextSpan(member, marked.start, marked.end)) }
        }
    }
}

/**
 * The paths of the `$`-marked references this text writes OUTSIDE every `Having` clause it carries.
 *
 * The filter conditions are blanked by position first ([withoutFilterConditions]), so what remains is exactly
 * the region A12 parses with `isInFilterCondition` false, the rest of a body after a nested filter included — a conjunct that happens to spell a body is outside it
 * and keeps its marker, as A12 reads it (`rulekit.validate.laws.FilterBodyTextRepeatedOutsideLawsTest`).
 */
private fun TrackedFactBase.markedPathsOutsideAFilter(text: String): List<ReferenceSpan> =
    markedReferenceSpansIn(withoutFilterConditions(text, conditionLanguage))

private fun finding(written: String, member: String, element: TrackedDeclaration, span: TextSpan) = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    "MVK_INVALID_OUTER_ITERATION",
    "in `$member`: `$OUTER_MARKER$written` marks an outer iteration outside any `Having` filter — the " +
        "marker reaches from a filter body back into the enclosing rule, so it means nothing where there " +
        "is no filter to reach out of; drop the marker or move the read into a filter",
    element.path,
    span,
)
