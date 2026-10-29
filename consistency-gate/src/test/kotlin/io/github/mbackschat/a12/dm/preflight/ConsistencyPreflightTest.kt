package io.github.mbackschat.a12.dm.preflight

import io.github.mbackschat.a12.dm.consistency.CheckOutcome
import io.github.mbackschat.a12.dm.interpreter.ModelPreparationResult
import io.github.mbackschat.a12.dm.interpreter.ModelWorkspace
import io.github.mbackschat.a12.dm.interpreter.WorkspaceModelSource
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * The F3-c preflight: an interpreter consumer's static precondition, and the two things it must NOT be.
 *
 * The outcome rows are cheap. The rows that matter are the two independence ones, because they state the
 * defect this façade exists to prevent — a consumer gating evaluation on preparation, which answers a
 * different question and answers it about a model A12 rejects just as readily as about one it accepts.
 */
class ConsistencyPreflightTest {

    @Test
    fun aRejectedModelIsReportedAsRejected() {
        val result = ConsistencyPreflight.of(sources(order(condition = "FieldFilled(NoSuchField)")), "orders")

        assertEquals(CheckOutcome.REJECTED, result.outcome)
        assertTrue(result.report.findings.any { it.code == "MVK_INVALID_ENTITY" },
            "the checker's own code rides along: ${result.report.findings}")
    }

    @Test
    fun anAcceptedModelPassesEveryCheckTheCheckerImplements() {
        val result = ConsistencyPreflight.of(sources(order()), "orders")

        // Renamed from `anAcceptedModelIsNoRejectionRatherThanAPass` on 2026-09-18: the old name asserted
        // that no pass existed, which was the contract until `ALL_CHECKS_PASSED` landed. The distinction it
        // protected is still real and still worth a row — it is `complete` below, which stays false because
        // that one tracks A12's coverage rather than the checker's.
        assertEquals(CheckOutcome.ALL_CHECKS_PASSED, result.outcome)
        assertTrue(result.report.checksCompleted.isNotEmpty(), "and something actually ran")
        assertTrue(
            !result.report.status.complete,
            "the scoped pass must not be read as A12 coverage — `complete` is the field that would say so " +
                "and it stays false",
        )
    }

    @Test
    fun anEntryTheWorkspaceDoesNotCarryIsNotEstablishedRatherThanAFailure() {
        val result = ConsistencyPreflight.of(sources(order()), "nosuchmodel")

        assertEquals(CheckOutcome.NOT_ESTABLISHED, result.outcome)
        assertTrue(result.report.diagnostics.isNotEmpty(), "with the reason stated: ${result.report}")
    }

    @Test
    fun anUnreadableSourceIsNotEstablishedRatherThanAThrow() {
        val result = ConsistencyPreflight.of(sources("{\"header\":"), "orders")

        assertEquals(CheckOutcome.NOT_ESTABLISHED, result.outcome)
        assertTrue(result.report.diagnostics.isNotEmpty())
    }

    /**
     * The whole point. A model A12 rejects prepares perfectly well, because preparation consults none of A12's
     * constraints — so a consumer who gated on preparation would evaluate it and get answers about a broken
     * model. The preflight is the question preparation does not ask.
     */
    @Test
    fun aModelTheInterpreterPreparesCanStillBeRejectedByThePreflight() {
        val rejected = order(condition = "FieldFilled(NoSuchField)")

        val prepared = ModelWorkspace.fromSources(sources(rejected)).tryPrepare("orders")
        assertTrue(prepared is ModelPreparationResult.Success, "preparation succeeds on it: $prepared")
        assertEquals(CheckOutcome.REJECTED, ConsistencyPreflight.of(sources(rejected), "orders").outcome,
            "and the preflight still refuses it — the two axes are independent")
    }

    /**
     * The other direction, which is what "evaluation admission is not silently widened" means in practice:
     * this façade adds no path by which a preflight answer could change what the interpreter admits. A model
     * the preflight finds nothing wrong with is admitted exactly as it was before this module existed.
     */
    @Test
    fun thePreflightChangesNothingAboutWhatTheInterpreterAdmits() {
        val sound = sources(order())
        val before = prepares(sound)

        ConsistencyPreflight.of(sound, "orders")

        assertEquals(before, prepares(sound))
        assertTrue(before, "and the control: this model does prepare, so the row above is not vacuous")
    }

    private fun prepares(sources: List<WorkspaceModelSource>) =
        ModelWorkspace.fromSources(sources).tryPrepare("orders") is ModelPreparationResult.Success

    private fun sources(vararg json: String) =
        json.map { WorkspaceModelSource.builder().json(it).diagnosticLabel("orders.dm.json").build() }

    private fun order(condition: String = "FieldFilled(Code)") = """
        {"header":{"modelVersion":"28.4.0","id":"orders","locales":[{"code":"en_US"}]},
         "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
            {"type":"Field","id":"f-code","name":"Code","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Rule","id":"r-code","name":"CodeRequired","Rule":{
              "errorCondition":"$condition","errorEntityRelPath":"../Code",
              "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"the order code"}]}}
          ]}}
         ]}}}
    """.trimIndent()
}
