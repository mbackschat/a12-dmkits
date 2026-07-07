package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.formatPointer
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.ComputationAlternative
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multiplatform (JVM + JS) lock for IF66: a computation's implicit validation points at its computed field even when
 * that field is NOT a descendant of the computation's group — a cross-root (`header`/`content`) or sibling-subtree
 * target. The synthetic error-entity relpath must resolve to the exact absolute target, not a `..//abs/path`
 * double-slash that renders an over-long mangled pointer. The kernel differential (both strategies) is
 * `adapter.laws.MultiRootComputationPointerDiffTest`; this pins the pointer form on both interpreter targets.
 */
class MultiRootComputationPointerTest {

    // /Policy/RiskTier + /Assessment/Note; computation on /Assessment writes /Policy/RiskTier (the OTHER root).
    private fun multiRoot() = ModelDef(
        fields = listOf(FieldDef("/Policy/RiskTier", Kind.STRING), FieldDef("/Assessment/Note", Kind.STRING)),
        rules = emptyList(),
        computations = listOf(ComputationDef("/Policy/RiskTier", "/Assessment",
            listOf(ComputationAlternative(null, "\"standard\"")), name = "SetTier")),
    )

    // /Claim/Incident/Note + /Claim/Payout/Status; computation on /Claim/Incident writes /Claim/Payout/Status.
    private fun siblingSubtree() = ModelDef(
        fields = listOf(FieldDef("/Claim/Incident/Note", Kind.STRING), FieldDef("/Claim/Payout/Status", Kind.STRING)),
        rules = emptyList(),
        computations = listOf(ComputationDef("/Claim/Payout/Status", "/Claim/Incident",
            listOf(ComputationAlternative(null, "\"open\"")), name = "SetStatus")),
    )

    private fun doc(m: ModelDef, group: String, target: String, stored: String): Document =
        m.newDocument().group(group, *IntArray(group.trim('/').split('/').size) { 1 }).also { d ->
            d.field(target, stored, *IntArray(target.trim('/').split('/').size) { 1 })
        }

    @Test fun multiRootPointerIsTheOtherRootsField() {
        val m = multiRoot()
        val msgs = DmInterpreter(m).validateFull(doc(m, "/Assessment", "/Policy/RiskTier", "premium"))
            .filter { it.code == "SetTier" }
        assertEquals(listOf("/Policy[1]/RiskTier"), msgs.map { it.errorPath }, "cross-root implicit-validation pointer")
    }

    @Test fun siblingSubtreePointerIsTheSiblingField() {
        val m = siblingSubtree()
        val msgs = DmInterpreter(m).validateFull(doc(m, "/Claim/Incident", "/Claim/Payout/Status", "closed"))
            .filter { it.code == "SetStatus" }
        assertEquals(listOf("/Claim[1]/Payout[1]/Status"), msgs.map { it.errorPath }, "sibling-subtree implicit-validation pointer")
    }

    @Test fun crossRootComputePointerRendersTheTarget() {
        val m = multiRoot()
        val computed = DmInterpreter(m).compute(doc(m, "/Assessment", "/Policy/RiskTier", "premium"))
            .filter { it.path == "/Policy/RiskTier" && it.outcome == ComputeOutcome.VALUE }
        assertEquals(listOf("/Policy[1]/RiskTier|standard"), computed.map { "${formatPointer(it.path, it.indices)}|${it.value}" },
            "cross-root compute pointer/value")
    }
}
