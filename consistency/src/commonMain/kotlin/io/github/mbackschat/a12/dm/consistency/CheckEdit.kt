package io.github.mbackschat.a12.dm.consistency

/**
 * What an edit did to a model's consistency, apart from what the model already carried.
 *
 * [status] is a verdict about the EDIT and is derived from [introduced] alone. That is the whole point of this
 * type: this checker reports over a whole model, so a consumer shown the edited model's report would read
 * `Invalid` whenever the baseline carried an ERROR of its own — a verdict about another declaration delivered
 * as a verdict about the edit, which is an over-rejection. The baseline's findings are still published, on
 * [baseline], because an author editing an already-broken model needs to know that; they are a disclosure and
 * never part of the edit's verdict.
 *
 * Like every verdict this module produces, [status] can be `Invalid` or `Inconclusive` and never `Valid`.
 *
 * @param introduced the findings present after the edit and absent before it — the edit's own contribution.
 * @param resolved the findings the edit removed, reported because an editor wants to see a repair land.
 * @param gaps what this ATTRIBUTION could not establish, distinct from either report's own coverage gaps.
 */
data class EditReport(
    val baseline: ConsistencyReport,
    val edited: ConsistencyReport,
    val introduced: List<Finding>,
    val resolved: List<Finding>,
    val status: ConsistencyStatus,
    val gaps: List<CoverageGap>,
)

/**
 * Check an [edited] model and attribute the difference against its [baseline].
 *
 * The two sources are checked independently and their findings compared as sets — [Finding] is a data class,
 * so its content is its identity, and the baseline's findings were measured to survive an edit unchanged
 * (2026-09-16; the plan's F1-b record carries the 2×2 and the Kernel control). Attribution by set difference
 * was chosen over attribution by each finding's `at` path because it assumes strictly less: the path form
 * additionally needs every edit-caused finding to land under the edited declaration, which nothing has
 * measured.
 *
 * **It refuses rather than guesses when the baseline was never analysed.** With no baseline there is no
 * "already carried", so every finding in [edited] would read as introduced and a pre-existing defect would be
 * blamed on the edit — the same over-rejection this operation exists to prevent, reached from the other side.
 * The refusal is reported as a [CoverageGap] on [EditReport.gaps]; the edited model's own report still rides
 * along, so the caller keeps everything that WAS established.
 */
fun checkEdit(baseline: ModelSource, edited: ModelSource): EditReport =
    attribute(check(baseline), check(edited))

/**
 * Attribute [after]'s findings against [before]'s — the comparison both edit operations share.
 *
 * It is one function rather than two because the attribution IS the semantics: [checkDraft] answers about
 * unsaved text and [checkEdit] about a second source, and a second copy of the comparison is where one of
 * them would quietly start blaming a baseline defect on the author.
 */
internal fun attribute(before: ConsistencyReport, after: ConsistencyReport): EditReport {
    // An analysis that completed no family established no baseline. `status` cannot serve as this test: an
    // Inconclusive baseline is the ORDINARY outcome here, since the checker never reports Valid.
    if (before.checksCompleted.isEmpty()) {
        return EditReport(
            baseline = before,
            edited = after,
            introduced = emptyList(),
            resolved = emptyList(),
            status = ConsistencyStatus.Inconclusive,
            gaps = listOf(
                CoverageGap(
                    null,
                    "the baseline could not be analysed, so nothing in the edited model can be attributed " +
                        "to the edit rather than to what was already there",
                ),
            ),
        )
    }

    // Compared by DEFECT rather than by finding: a span moves when a character is typed earlier in the
    // same text, and comparing whole findings would then report one defect as resolved and an identical one
    // as introduced on an edit that changed nothing about it.
    val had = before.findings.mapTo(mutableSetOf()) { it.sameDefect }
    val has = after.findings.mapTo(mutableSetOf()) { it.sameDefect }
    val introduced = after.findings.filter { it.sameDefect !in had }
    val resolved = before.findings.filter { it.sameDefect !in has }
    return EditReport(
        baseline = before,
        edited = after,
        introduced = introduced,
        resolved = resolved,
        status = statusOf(introduced),
        gaps = emptyList(),
    )
}
