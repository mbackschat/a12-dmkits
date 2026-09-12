package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Preparation reuses what it already holds — the parse-once half of the checker's snapshot contract
 * ([PORTABLE-CONSISTENCY-PROPOSAL §5.6](../../../../../../../docs/PORTABLE-CONSISTENCY-PROPOSAL.md)).
 *
 * **Identity is the instrument, because it is the only honest counter available.** A reparse or a defensive
 * copy produces an equal tree, so equality cannot tell the two apart; a retained node is the SAME object and
 * a reparsed one never is. That makes `assertSame` here a measurement of how many times the source was read,
 * not a statement about object graphs.
 *
 * The rows are the two halves of §5.6's sentence about a declaration mounted at several scopes: reuse its
 * syntax, resolve each mount separately. A checker that copied per mount would pay for every mount instance;
 * one that shared the binding would resolve a provider's reference against the wrong subtree — the defect the
 * mount-instance denominator already cost once ([KF287](../../../../../../../docs/KERNEL-FINDINGS.md#kf287)).
 *
 * The workspace is the Kernel-locked `same-alias-mounted-twice` case rather than a fixture written here, so a
 * shape this claim depends on cannot be quietly simplified; [WorkspaceMatrixReconciliationTest] holds its
 * placement against the Kernel.
 */
class PreparationReuseTest {

    @Test
    fun aProviderMountedTwiceIsRetainedOnceAndBoundTwice() {
        val prepared = prepare(admitted("same-alias-mounted-twice"))

        val score = prepared.elements.filter { it.name == "Score" && it.kind == "Field" }
        assertEquals(
            listOf("/Permit/Primary/Score", "/Permit/Secondary/Score"), score.map { it.path },
            "the fixture must mount one provider at two scopes for this row to mean anything")
        assertSame(score[0].declaration, score[1].declaration,
            "one authored declaration, read once and retained — not parsed or copied per mount")
        assertNotEquals(score[0].origin.mountedPrefix, score[1].origin.mountedPrefix,
            "each mount instance binds separately, which is what the shared syntax must not collapse")
    }

    @Test
    fun preparingTheSameAdmittedSourcesAgainReadsNothingAgain() {
        val sources = admitted("imported-typedef-nested-single")

        val first = prepare(sources).elements
        val second = prepare(sources).elements

        assertTrue(first.size > 1, "an empty fact base would make the identity below vacuous")
        assertEquals(first.map { it.path }, second.map { it.path })
        first.zip(second).forEach { (before, after) ->
            assertSame(before.declaration, after.declaration,
                "${before.path} was read again on the second preparation")
        }
    }

    /** The case's sources, admitted once — the state a workspace holds between checks. */
    private fun admitted(caseId: String): Pair<Admission.Admitted, Map<String, Admission.Admitted>> {
        val all = WorkspaceMatrixJson.sources(caseId)
            .map { (modelId, json) -> admit(ModelSource(json, diagnosticLabel = modelId)) }
            .filterIsInstance<Admission.Admitted>()
            .associateBy { it.modelId }
        val entryId = WorkspaceMatrixJson.entryModelId(caseId)
        return all.getValue(entryId) to (all - entryId)
    }

    private fun prepare(sources: Pair<Admission.Admitted, Map<String, Admission.Admitted>>) =
        prepare(sources.first, sources.second)
}
