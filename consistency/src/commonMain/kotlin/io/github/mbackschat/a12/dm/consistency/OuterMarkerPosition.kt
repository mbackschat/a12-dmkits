package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.normalizedPath

internal fun TrackedFactBase.outerMarkerContributions(element: TrackedDeclaration): List<ConditionFinding> {
    if (element.declaredKind != DeclaredKind.RULE) return emptyList()
    return entityContributions(element, EntityCheck.OUTER_MARKER) { entity, span ->
        if (entity.path.first().text != "$" || entity.inFilter) return@entityContributions null
        val written = normalizedPath(entity.path.drop(1))
        if (resolveEntity(element.path.substringBeforeLast('/'), written, element.origin) !is Reference.Resolved)
            return@entityContributions null
        finding(written, span.member, element, span)
    }
}

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
internal fun TrackedFactBase.outerMarkerPositionFindings(): List<Finding> =
    declarations().flatMap { outerMarkerContributions(it) }.map { it.finding }

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
