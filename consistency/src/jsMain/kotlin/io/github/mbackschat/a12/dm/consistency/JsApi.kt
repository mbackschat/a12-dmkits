@file:OptIn(ExperimentalJsExport::class)

package io.github.mbackschat.a12.dm.consistency

import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport

/**
 * The checker's whole JavaScript surface: two functions, strings in and out.
 *
 * **It is deliberately this small.** The checker is not feature-complete and its Kotlin types are still
 * moving, so exporting them would publish an ABI that has to change — and a JS consumer that destructures
 * `ConsistencyReport` today would break on every family that lands. A JSON string commits to the wire shape
 * in [toJson] and to nothing else, and that shape has one owner and a document.
 *
 * **The second reason is mechanical rather than editorial.** `@JsExport` cannot carry Kotlin collections or
 * a sealed interface across the boundary, which is most of what the report is — `List<Finding>`,
 * `Set<CheckFamily>`, `ConsistencyStatus`. An exported surface would therefore need a parallel hierarchy of
 * bridge classes, which is real cost for an API nobody has consumed yet. `:interpreter` carries exactly that
 * machinery because its surface is settled; this one earns it when it is.
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
