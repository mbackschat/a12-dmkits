@file:OptIn(ExperimentalJsExport::class)

package io.github.mbackschat.a12.dm.consistency

import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport

/**
 * The checker's whole JavaScript surface: one function, strings in and out.
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
