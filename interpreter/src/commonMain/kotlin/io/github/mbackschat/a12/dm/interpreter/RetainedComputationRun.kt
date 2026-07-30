package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.ComputationApplication
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.model.Computed

/** One immutable internal computation run: report-all outcomes plus its source-relative application actions. */
internal class RetainedComputationRun(
    val results: List<Computed>,
    val applications: List<ComputationApplication>,
) {
    fun applyTo(destination: Document): Document =
        destination.withComputationApplications(applications)
}
