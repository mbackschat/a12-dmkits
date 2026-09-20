package io.github.mbackschat.a12.dm.preflight

import io.github.mbackschat.a12.dm.consistency.CheckOutcome
import io.github.mbackschat.a12.dm.consistency.ConsistencyReport
import io.github.mbackschat.a12.dm.consistency.ConsistencyWorkspace
import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.outcome
import io.github.mbackschat.a12.dm.interpreter.WorkspaceModelSource
import kotlin.jvm.JvmStatic

/**
 * What the preflight established, and the report it established it from.
 *
 * **[outcome] is not an A12 validity claim, and no value of it is one.** The best answer available is
 * [CheckOutcome.ALL_CHECKS_PASSED] — every family the checker implements ran and none rejected the model —
 * which is a pass over the checker's own coverage and deliberately not over A12's constraints. [report]
 * carries how much ran, `checksCompleted` and `gaps`, which is the half that says how much weight that
 * answer can bear.
 *
 * *Amended 2026-09-18: this said "there is no value that would be one" and named [CheckOutcome.NO_REJECTION]
 * as the ceiling, which stopped being true when the scoped pass landed. The distinction it was reaching for
 * survives and is the one above — scoped to the checker, never to A12.*
 */
class PreflightReport internal constructor(val outcome: CheckOutcome, val report: ConsistencyReport)

/**
 * The static consistency precondition for an interpreter consumer — a DIFFERENT question from preparation.
 *
 * ```kotlin
 * val sources = listOf(WorkspaceModelSource.builder().json(orderJson).build())
 * when (ConsistencyPreflight.of(sources, "orders").outcome) {
 *     CheckOutcome.REJECTED -> …          // A12 rejects a declaration: evaluating it answers about a broken model
 *     CheckOutcome.ALL_CHECKS_PASSED -> … // every implemented family ran and none rejected — a scoped pass
 *     CheckOutcome.NO_REJECTION -> …      // some families ran and none rejected — NOT a pass
 *     CheckOutcome.NOT_ESTABLISHED -> …   // nothing was checked; the report says why
 * }
 * ```
 *
 * **Why this exists rather than reading preparation's result.** `ModelWorkspace.prepare` answers whether the
 * interpreter can BUILD an evaluator for a model, and `Interpreter.supportReport` answers which rules it can
 * execute. Neither is a statement about A12 consistency, and the failure this façade prevents is reading one
 * as if it were: a model A12 rejects can prepare perfectly well and report full support, because nothing in
 * either path consults A12's constraints. A consumer that gated on preparation would evaluate a broken model
 * and get answers about it.
 *
 * **It takes the consumer's own sources and builds nothing.** The input is exactly what
 * `ModelWorkspace.fromSources` takes, so asking the second question costs no second representation of the
 * model; and this façade creates no prepared graph, holds no interpreter, and changes no evaluator admission
 * — the interpreter accepts and refuses precisely what it did before, which is deliberate and is locked by
 * `AdmissionIsNotWidenedTest`.
 */
object ConsistencyPreflight {

    /**
     * Check [entryModelId]'s transitive closure within [sources].
     *
     * A source the checker cannot read, and an entry id the workspace does not carry, both come back as a
     * report rather than an exception — [CheckOutcome.NOT_ESTABLISHED] with the reason in
     * [PreflightReport.report]. A precondition that threw would be one more failure mode for a caller to
     * handle on a path whose whole job is to tell them what they are dealing with.
     */
    @JvmStatic
    fun of(sources: List<WorkspaceModelSource>, entryModelId: String): PreflightReport {
        val report = ConsistencyWorkspace
            .fromSources(sources.map { ModelSource(it.json, it.diagnosticLabel) })
            .check(entryModelId)
        return PreflightReport(report.outcome, report)
    }
}
