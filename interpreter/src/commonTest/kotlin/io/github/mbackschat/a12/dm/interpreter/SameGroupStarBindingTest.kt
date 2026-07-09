package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.ComputationAlternative
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multiplatform (JVM + JS) lock for IF93 — the star-binding anchor: the `*`-marked level always iterates
 * FULLY; only levels STRICTLY ABOVE the starred group bind to the enclosing iteration row (`$` inside a
 * `Having` is the only outer-row correlation). A bare same-group star in a per-row rule/computation spans ALL
 * rows (it previously collapsed to the current row), an ancestor star from a nested row spans all ancestor
 * rows, and a star nested BELOW the iterating row still binds that row. Kernel-verified by the differential
 * `adapter.laws.ComputeStarAggregatePoisonDiffTest` (which also pins the IG44 R2 consequence: a poisoned
 * sibling star cell now poisons a per-row dependent's aggregate read — the IF85 machinery, unchanged).
 */
class SameGroupStarBindingTest {

    private val HELPER = "/Shop/Pricing/Helper"
    private val PRICE = "/Shop/Pricing/Price"
    private val ROWFLAG = "/Shop/Pricing/RowFlag"

    // ── validation: a per-row rule's bare same-group star spans ALL rows ─────────────────────────────────

    private val ruleModel = ModelDef(
        fields = listOf(
            FieldDef(HELPER, Kind.NUMBER, scale = 2, minFractionalDigits = 2),
        ),
        rules = listOf(
            RuleDef("/Shop/Pricing/Q", "/Shop/Pricing",
                "MaxValue(../Pricing* / Helper) == [Helper]", "Q", "../Helper"),
        ),
        computations = emptyList(),
    )

    @Test fun sameGroupStarInAPerRowRuleFiresOnlyTheMaxRow() {
        val doc = ruleModel.newDocument().group("/Shop", 1)
            .group("/Shop/Pricing", 1, 1).field(HELPER, "10.00", 1, 1, 1)
            .group("/Shop/Pricing", 1, 2).field(HELPER, "3.10", 1, 2, 1)
            .group("/Shop/Pricing", 1, 3).field(HELPER, "7.00", 1, 3, 1)
        val fired = DmInterpreter(ruleModel).validateFull(doc).filter { it.code == "Q" }.map { it.errorPath }.sorted()
        assertEquals(listOf("/Shop[1]/Pricing[1]/Helper"), fired,
            "MaxValue over the same-group star spans all rows → only row 1 (the max) fires")
    }

    // ── compute: a poisoned sibling star cell poisons a per-row dependent's aggregate read (the R2 atom) ──

    private val computeModel = ModelDef(
        fields = listOf(
            FieldDef(PRICE, Kind.NUMBER, scale = 2, minFractionalDigits = 2),
            FieldDef(HELPER, Kind.NUMBER, scale = 2, minFractionalDigits = 2),
            FieldDef(ROWFLAG, Kind.NUMBER, scale = 0),
        ),
        rules = emptyList(),
        computations = listOf(
            ComputationDef(HELPER, "/Shop/Pricing", "[Price]"),
            ComputationDef(ROWFLAG, "/Shop/Pricing",
                listOf(ComputationAlternative("MaxValue(../Pricing* / Helper) == [Helper]", "1"))),
        ),
    )

    @Test fun poisonedSiblingStarCellPoisonsThePerRowDependent() {
        val doc = computeModel.newDocument().group("/Shop", 1)
            .group("/Shop/Pricing", 1, 1).field(PRICE, "10.00", 1, 1, 1).field(HELPER, "1.00", 1, 1, 1).field(ROWFLAG, "9", 1, 1, 1)
            .group("/Shop/Pricing", 1, 2).field(PRICE, "5.5", 1, 2, 1).field(HELPER, "1.00", 1, 2, 1).field(ROWFLAG, "9", 1, 2, 1)
        val out = DmInterpreter(computeModel).compute(doc)
        val sig = out.associate { "${it.path}@${it.indices.joinToString(",")}" to (it.outcome to it.value) }
        assertEquals(ComputeOutcome.VALUE to "10.00", sig["$HELPER@1,1,1"], "row-1 Helper computes")
        assertEquals(ComputeOutcome.CLEARED, sig["$HELPER@1,2,1"]?.first, "row-2 Helper poisons (invalid Price)")
        assertEquals(ComputeOutcome.CLEARED, sig["$ROWFLAG@1,1,1"]?.first,
            "row-1 RowFlag: the aggregate's star includes the poisoned row-2 cell → poisoned, NOT computed")
        assertEquals(ComputeOutcome.CLEARED, sig["$ROWFLAG@1,2,1"]?.first, "row-2 RowFlag poisons (own Helper)")
    }

    @Test fun validRowsComputeThePerRowDependentPerAllRowsSemantics() {
        val doc = computeModel.newDocument().group("/Shop", 1)
            .group("/Shop/Pricing", 1, 1).field(PRICE, "10.00", 1, 1, 1).field(HELPER, "1.00", 1, 1, 1).field(ROWFLAG, "9", 1, 1, 1)
            .group("/Shop/Pricing", 1, 2).field(PRICE, "3.10", 1, 2, 1).field(HELPER, "1.00", 1, 2, 1).field(ROWFLAG, "9", 1, 2, 1)
        val out = DmInterpreter(computeModel).compute(doc)
        val sig = out.associate { "${it.path}@${it.indices.joinToString(",")}" to (it.outcome to it.value) }
        assertEquals(ComputeOutcome.VALUE to "1", sig["$ROWFLAG@1,1,1"],
            "row 1 IS the max across ALL rows → its flag computes")
        assertEquals(ComputeOutcome.CLEARED, sig["$ROWFLAG@1,2,1"]?.first,
            "row 2 is NOT the max → precondition false → cleared (an own-row collapse would have computed 1 here)")
    }
}
