@file:OptIn(ExperimentalJsExport::class)

package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport

/**
 * The checker's JavaScript surface: strings in, strings out, plus one opaque handle for the stateful half.
 *
 * **Every payload stays a JSON string, and that is the durable decision.** The checker's Kotlin types are
 * still moving, so exporting them would publish an ABI that has to change — a JS consumer destructuring
 * `ConsistencyReport` would break on every family that lands. A JSON string commits to the wire shape in
 * [toJson] and to nothing else, and that shape has one owner and a document. The mechanical half of the same
 * point: `@JsExport` cannot carry Kotlin collections or a sealed interface, which is most of what a report
 * is — `List<Finding>`, `Set<CheckFamily>`, `ConsistencyStatus` — so exporting the TYPES would need a
 * parallel hierarchy of bridge classes. None of that is needed to export a HANDLE, which is why the stateful
 * surface costs almost nothing here.
 *
 * *This file carried exactly two functions until 2026-09-18, on the stated ground that a wider surface was
 * "real cost for an API nobody has consumed yet". That ground was measured false: the plan's own N acceptance
 * matrix requires every application feature G–J and L to be "exported through the documented typed package
 * surface and exercised from a separate packed-package Node project", and `checkDraft`, `impactOf` and
 * `snapshotOf` were reachable only from Kotlin — which that matrix names as a feature "represented only by
 * an internal-only API". The consumer that had not asked was this project.*
 *
 * A caller gets a report for every input, never a thrown exception: an unreadable source is reported at the
 * source level rather than raised, so a Node consumer needs no try/catch to get an answer.
 */
@JsExport
fun checkModelJson(modelJson: String, diagnosticLabel: String? = null): String =
    check(ModelSource(modelJson, diagnosticLabel)).toJson()

/**
 * Check an EDIT and attribute the difference against its baseline — strings in, the edit's wire shape out.
 *
 * This is the surface an editor wants before saving: [checkModelJson] answers whether a model is acceptable,
 * which is not the question "did MY change break something". Because the checker reports over a whole model,
 * a consumer that called [checkModelJson] on the edited text alone would read INVALID whenever the baseline
 * was already broken, and would blame the author's edit for someone else's declaration.
 *
 * The result's `status` is the EDIT's verdict and is decided by `introduced` alone; `baseline` rides along as
 * a disclosure. See [checkEdit] for the attribution's own contract, including what it refuses to attribute.
 */
@JsExport
fun checkEditJson(baselineJson: String, editedJson: String, diagnosticLabel: String? = null): String =
    checkEdit(ModelSource(baselineJson, diagnosticLabel), ModelSource(editedJson, diagnosticLabel)).toJson()

/**
 * Check DSL text an author is TYPING, attributed against the model it sits in — slice I's surface.
 *
 * This is the per-keystroke question, and it is not [checkModelJson] on the edited text: half-typed input is
 * `MVK_INCOMPLETE_INPUT` rather than a defect, and a draft's verdict must not absorb a defect another
 * declaration already had. `at` is the declaration's path, `member` the member being edited (its condition
 * or operation), `text` the current contents.
 *
 * For an editor calling this per keystroke, prefer [snapshotOfJson] and the handle's `draftJson`: this
 * function admits and prepares the source on every call, where the handle pays that once.
 */
@JsExport
fun checkDraftJson(
    modelJson: String,
    at: String,
    member: String,
    text: String,
    diagnosticLabel: String? = null,
): String = checkDraft(ModelSource(modelJson, diagnosticLabel), at, member, text).toJson()

/**
 * A frozen analysis a JS consumer can HOLD and update — slice L's surface, and the reason it is a class.
 *
 * **Opaque on purpose.** The constructor is internal, which Kotlin/JS renders as TypeScript's
 * `private constructor()`, so a consumer can only obtain one from [snapshotOfJson] and cannot fabricate a
 * handle that never analysed anything. Nothing about the snapshot's internals crosses the boundary: every
 * payload here is a JSON string on the shape [toJson] owns.
 *
 * **A handle is a value, like the snapshot it wraps.** [withSource] returns a NEW handle and leaves this one
 * answering exactly what it answered before, so a consumer may keep an older analysis and get the older
 * answer. Holding one retains the prepared model and its cell store, which is the cost that buys the reuse —
 * a consumer that holds handles it no longer needs keeps that memory live, and JS gives us no `close()` to
 * enforce otherwise.
 */
@JsExport
class ConsistencySnapshotHandle internal constructor(private val snapshot: ConsistencySnapshot) {

    /** The entry model's report, computed when this handle was made. */
    fun reportJson(): String = snapshot.report.toJson()

    /** What making THIS handle reused from the one it came from — L's claim, made observable. */
    fun reuseJson(): String = snapshot.reuse.asJson().toString()

    /** The same analysis with [modelJson] replacing whatever declared its id, reusing every other parse. */
    fun withSource(modelJson: String, diagnosticLabel: String? = null): ConsistencySnapshotHandle =
        ConsistencySnapshotHandle(snapshot.withSource(ModelSource(modelJson, diagnosticLabel)))

    /** The same analysis without the source declaring [modelId], reusing every parse that remains. */
    fun withoutSource(modelId: String): ConsistencySnapshotHandle =
        ConsistencySnapshotHandle(snapshot.withoutSource(modelId))

    /**
     * [checkDraftJson]'s answer without its cost: the draft is applied to the fact base this handle already
     * holds and attributed against the report it already computed.
     *
     * Returns the EDIT's shape, the same one [checkDraftJson] returns, so a consumer can move between the
     * stateless and stateful calls without reshaping its reader. Use [draftWithReuseJson] when the reuse
     * itself is the subject.
     */
    fun draftJson(at: String, member: String, text: String): String =
        snapshot.draft(at, member, text).edit.toJson()

    /**
     * The same answer WITH what producing it reused: `{"edit": …, "reuse": …}`.
     *
     * Separate from [draftJson] rather than beside it, because a draft is one computation and asking for the
     * two halves through two calls would perform it twice — which is precisely the cost this handle exists to
     * remove. An earlier version of this class did exactly that.
     */
    fun draftWithReuseJson(at: String, member: String, text: String): String {
        val result = snapshot.draft(at, member, text)
        return buildJsonObject {
            put("edit", Json.parseToJsonElement(result.edit.toJson()))
            put("reuse", result.reuse.asJson())
        }.toString()
    }
}

/**
 * Analyse [entryModelId]'s transitive closure within [modelJsons] and return a handle that holds it.
 *
 * The sources are whole model documents; the entry id is the `header.id` of the one being checked. An entry
 * the workspace does not carry comes back as a handle whose report says so, never as a throw, on the same
 * rule the stateless calls follow.
 */
@JsExport
fun snapshotOfJson(modelJsons: Array<String>, entryModelId: String): ConsistencySnapshotHandle =
    ConsistencySnapshotHandle(snapshotOf(modelJsons.map { ModelSource(it) }, entryModelId))
