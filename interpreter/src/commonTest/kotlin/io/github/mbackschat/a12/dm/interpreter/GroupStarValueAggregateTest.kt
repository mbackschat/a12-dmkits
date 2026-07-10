package io.github.mbackschat.a12.dm.interpreter

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
 * Multiplatform (JVM + JS) lock for IF99 — a value aggregate over a **GROUP star** (`Sum(Lines*)`, the star on
 * the group, no field segment) expands to the group's contained FIELDS, recursively incl. nested subgroups
 * (the kernel `ExpandService` — the fill quantifiers' IF49/IF51 rule, now on the aggregate path too). A
 * malformed contained cell poisons the compute aggregate; an empty group is the empty-selection 0. (A `Having`
 * on a group star is REJECTED by the kernel — `MVK_NO_GROUPS_ALLOWED` — so only the unfiltered expansion is
 * reachable.) Kernel-verified by the tri-check differential `adapter.laws.GroupStarValueAggregateDiffTest`.
 */
class GroupStarValueAggregateTest {

    private val TOTAL = "/Invoice/Total"
    private val AMOUNT = "/Invoice/Lines/Amount"
    private val FEE = "/Invoice/Charges/Fee"
    private val TAX = "/Invoice/Charges/Tax"
    private val SURCHARGE = "/Invoice/Charges/Extras/Surcharge"

    private fun model(operation: String) = ModelDef(
        fields = listOf(
            FieldDef(TOTAL, Kind.NUMBER, scale = 2), FieldDef(AMOUNT, Kind.NUMBER, scale = 2),
            FieldDef(FEE, Kind.NUMBER, scale = 2), FieldDef(TAX, Kind.NUMBER, scale = 2),
            FieldDef(SURCHARGE, Kind.NUMBER, scale = 2),
        ),
        rules = emptyList(),
        computations = listOf(ComputationDef(TOTAL, "/Invoice", listOf(ComputationAlternative(null, operation)))),
        repeatableGroups = setOf("/Invoice/Lines", "/Invoice/Charges", "/Invoice/Charges/Extras"),
    )

    private fun total(def: ModelDef, build: (io.github.mbackschat.a12.dm.interpreter.eval.Document) -> Unit): String {
        val doc = def.newDocument().group("/Invoice", 1)
        build(doc)
        val c = DmInterpreter(def).compute(doc).first { it.path == TOTAL }
        return when (c.outcome) {
            ComputeOutcome.VALUE -> "VALUE|${c.value}"
            ComputeOutcome.CLEARED -> "CLEARED"
            ComputeOutcome.ERRORED -> "ERRORED"
        }
    }

    @Test fun groupStarSumExpandsToTheContainedField() {
        val v = total(model("Sum(/Invoice/Lines*)")) { doc ->
            doc.group("/Invoice/Lines", 1, 1).field(AMOUNT, "10.50", 1, 1, 1)
            doc.group("/Invoice/Lines", 1, 2).field(AMOUNT, "20.25", 1, 2, 1)
        }
        assertEquals("VALUE|30.75", v)
    }

    @Test fun groupStarExpansionIsRecursiveAcrossFieldsAndSubgroups() {
        val v = total(model("Sum(/Invoice/Charges*)")) { doc ->
            doc.group("/Invoice/Charges", 1, 1).field(FEE, "1.00", 1, 1, 1).field(TAX, "2.00", 1, 1, 1)
            doc.group("/Invoice/Charges", 1, 2).field(FEE, "3.00", 1, 2, 1)
            doc.group("/Invoice/Charges/Extras", 1, 1, 1).field(SURCHARGE, "0.50", 1, 1, 1, 1)
        }
        assertEquals("VALUE|6.5", v)
    }

    @Test fun malformedContainedCellPoisonsTheComputeAggregate() {
        val v = total(model("Sum(/Invoice/Lines*)")) { doc ->
            doc.group("/Invoice/Lines", 1, 1).field(AMOUNT, "10.50", 1, 1, 1)
            doc.group("/Invoice/Lines", 1, 2).field(AMOUNT, "5.123", 1, 2, 1)
        }
        assertEquals("CLEARED", v, "a 3-frac cell poisons the compute aggregate (IF85)")
    }

    @Test fun emptyGroupStarIsTheEmptySelectionZero() {
        assertEquals("VALUE|0", total(model("Sum(/Invoice/Lines*)")) { })
    }
}
