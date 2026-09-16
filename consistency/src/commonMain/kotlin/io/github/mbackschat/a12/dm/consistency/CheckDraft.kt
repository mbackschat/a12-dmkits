package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.JsonObject

/**
 * Check DSL text an editor has typed but not saved, in the model it belongs to.
 *
 * **The draft is applied to the FACT BASE, not to the source, and that is the point.** Text being typed is
 * not in any model yet; an editor that wrote it into the source to find out would have to undo the write on
 * every keystroke, and a consumer that checked the text alone would lose the model context every reference in
 * it resolves against. So the declaration at [at] is re-declared in the prepared fact base with [member]
 * carrying [text], and that fact base is checked.
 *
 * **The answer is attributed, exactly as [checkEdit] attributes a saved edit.** A draft's verdict may not
 * absorb its neighbours' defects: an author typing into a model that is already broken elsewhere would
 * otherwise be told their draft is invalid, which is the over-rejection this module forbids. The neighbours'
 * findings ride along on [EditReport.baseline] as a disclosure.
 *
 * **Half-typed text is INCOMPLETE, not invalid**, and A12 has a code of its own for it — the state an editor
 * is in most of the time. Nothing here promotes that to a rejection.
 *
 * One preparation serves both sides: the baseline and the drafted fact base are the same prepared model with
 * one declaration replaced, so a keystroke costs one admission and one preparation rather than two.
 *
 * @param at the declaration's path, as a report names it.
 * @param member the DSL member being typed, spelled as a report names it — `errorCondition`,
 *     `commonPrecondition`, `computationAlternatives[0].precondition`, `computationAlternatives[0].operation`.
 * @return the draft's own attribution. A draft that could not be applied at all — an unreadable source, a
 *     path this model does not declare, a member this declaration cannot carry — attributes NOTHING and says
 *     why as a [CoverageGap], rather than raising or guessing.
 */
fun checkDraft(source: ModelSource, at: String, member: String, text: String): EditReport {
    val admitted = admit(source) as? Admission.Admitted
        ?: check(source).let { report ->
            return refused(report, CoverageGap(
                null,
                "the source could not be read, so there is no model for this draft to be checked in",
            ))
        }
    val prepared = prepare(admitted, providers = emptyMap())
    val baseline = checkPrepared(prepared, admitted.modelId, inherited = emptyList())
    val element = prepared.elements.firstOrNull { it.path == at }
    val body = element?.body
    if (element == null || body == null) {
        return refused(baseline, CoverageGap(
            at,
            "this model declares no rule or computation there, so the draft has nothing to be typed into",
        ))
    }
    val draftable = draftableMembers(element.kind, body)
    if (member !in draftable) {
        return refused(baseline, CoverageGap(
            at,
            "a `${element.kind}` carries no DSL member `$member`; this one can carry " +
                draftable.joinToString(", ") { "`$it`" }.ifEmpty { "no DSL text at all" },
        ))
    }
    val drafted = prepared.withDraft(element, body, member, text)
        ?: return refused(baseline, CoverageGap(
            at,
            "`$member` could not be replaced in this declaration, so the draft was not analysed",
        ))
    return attribute(baseline, checkPrepared(drafted, admitted.modelId, inherited = emptyList()))
}

/**
 * The same fact base with one declaration's DSL member replaced.
 *
 * The element's whole declaration is rebuilt rather than its body mutated, because `body` is DERIVED from
 * `declaration[kind]` — writing to a body object would leave the declaration every other reader sees
 * unchanged, which is the silent half of that indirection.
 */
private fun PreparedModel.withDraft(
    element: DeclaredElement,
    body: JsonObject,
    member: String,
    text: String,
): PreparedModel? {
    val drafted = replaceConditionText(body, member, text) ?: return null
    val redeclared = element.copy(declaration = JsonObject(element.declaration + (element.kind to drafted)))
    return copy(elements = elements.map { if (it.path == element.path) redeclared else it })
}

/**
 * A draft that was not analysed: nothing attributed, and the reason stated.
 *
 * `edited` repeats the baseline deliberately. The alternative would be to publish no edited report at all,
 * and a consumer reading `edited` as "the model with my draft in it" would then have to distinguish absent
 * from empty — where what actually happened is that the model was checked and the draft never reached it.
 */
private fun refused(baseline: ConsistencyReport, gap: CoverageGap) = EditReport(
    baseline = baseline,
    edited = baseline,
    introduced = emptyList(),
    resolved = emptyList(),
    status = ConsistencyStatus.Inconclusive,
    gaps = listOf(gap),
)
