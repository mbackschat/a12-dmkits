package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.ComputationApplication
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.model.Computed
import io.github.mbackschat.a12.dm.interpreter.model.Message
import io.github.mbackschat.a12.dm.interpreter.model.UnsupportedRule

/** One immutable internal computation run: outcomes, diagnostics, support, and source-relative actions. */
internal class RetainedComputationRun(
    val results: List<Computed>,
    val applications: List<ComputationApplication>,
    val formalErrorsInOperands: List<Message>,
    val computedErrors: Map<Pair<String, List<Int>>, Message>,
    val unsupported: List<UnsupportedRule>,
) {
    fun applyTo(destination: Document): Document =
        destination.withComputationApplications(applications)
}
