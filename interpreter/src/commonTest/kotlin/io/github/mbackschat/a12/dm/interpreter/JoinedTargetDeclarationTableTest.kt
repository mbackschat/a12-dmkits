package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.Document
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
 * Multiplatform (JVM + JS) lock for IF437: in a target some declaration JOINS to another keyed group, a
 * partnerless declaration leading the table clears it at every row, and one behind a joined lead is passed over
 * unread, so a later joined declaration computes even where its guard holds; a row past capacity clears in a table
 * of joined declarations alone. The Kernel answers are `adapter.laws.JoinedTargetDeclarationTableDiffTest`'s.
 */
class JoinedTargetDeclarationTableTest {

    private fun model(capacity: Int, vararg comps: ComputationDef) = ModelDef(
        fields = listOf("Allocated", "Buffered", "Skipped", "Passed", "Pooled").map { FieldDef("/Fulfilment/Orders/$it", Kind.NUMBER) } +
            listOf(
                FieldDef("/Fulfilment/Orders/Sku", Kind.STRING),
                FieldDef("/Fulfilment/Orders/Qty", Kind.NUMBER),
                FieldDef("/Fulfilment/Stock/Sku", Kind.STRING),
                FieldDef("/Fulfilment/Stock/OnHand", Kind.NUMBER),
            ),
        rules = emptyList(),
        computations = comps.toList(),
        declaredReps = mapOf("/Fulfilment/Orders" to capacity, "/Fulfilment/Stock" to 9),
        indexFields = setOf("/Fulfilment/Orders/Sku", "/Fulfilment/Stock/Sku"),
    )

    private fun declaration(target: String, guard: String, operation: String) =
        ComputationDef("/Fulfilment/Orders/$target", "/Fulfilment/Orders", listOf(ComputationAlternative(guard, operation)))

    private val fromStock = "[../Stock/OnHand]"

    /** Orders A (one unit) and B (two), both stocked, plus [extra] orders of one unit each. */
    private fun orders(m: ModelDef, vararg extra: String): Document = m.newDocument().group("/Fulfilment", 1).also { d ->
        (listOf("A" to "1", "B" to "2") + extra.map { it to "1" }).forEachIndexed { i, (sku, qty) ->
            d.group("/Fulfilment/Orders", 1, i + 1).field("/Fulfilment/Orders/Sku", sku, 1, i + 1, 1)
                .field("/Fulfilment/Orders/Qty", qty, 1, i + 1, 1)
            d.group("/Fulfilment/Stock", 1, i + 1).field("/Fulfilment/Stock/Sku", sku, 1, i + 1, 1)
                .field("/Fulfilment/Stock/OnHand", "${i + 3}", 1, i + 1, 1)
        }
    }

    private fun rows(m: ModelDef, d: Document, target: String): List<String> =
        DmInterpreter(m).compute(d).filter { it.path == "/Fulfilment/Orders/$target" }
            .sortedBy { it.indices[1] }
            .map { if (it.outcome == ComputeOutcome.VALUE) "VALUE|${it.value}" else it.outcome.name }

    @Test fun aPartnerlessDeclarationBehindAJoinedLeadIsPassedOverEvenWhereItsGuardHolds() {
        val m = model(3,
            declaration("Passed", "[Qty] == 1", fromStock),
            declaration("Passed", "[Qty] >= 1", "[Qty] + 100"),
            declaration("Passed", "[Qty] >= 1", "$fromStock + 1000"))
        assertEquals(listOf("VALUE|3", "VALUE|1004"), rows(m, orders(m), "Passed"))
    }

    @Test fun aPassedOverDeclarationWithNoJoinedOneAfterItLeavesTheTargetCleared() {
        val m = model(3,
            declaration("Allocated", "[Qty] == 1", fromStock),
            declaration("Allocated", "[Qty] >= 1", "[Qty] + 100"))
        assertEquals(listOf("VALUE|3", "CLEARED"), rows(m, orders(m), "Allocated"))
    }

    @Test fun aPartnerlessDeclarationBehindAJoinedLeadIsPassedOverWhereItsGuardFailsAlike() {
        val m = model(3,
            declaration("Skipped", "[Qty] == 1", fromStock),
            declaration("Skipped", "[Qty] >= 5", "[Qty] + 100"),
            declaration("Skipped", "[Qty] >= 1", "$fromStock + 1000"))
        assertEquals(listOf("VALUE|3", "VALUE|1004"), rows(m, orders(m), "Skipped"))
    }

    @Test fun aPartnerlessLeadClearsEveryRowWhateverItsGuard() {
        val m = model(3,
            declaration("Buffered", "[Qty] >= 5", "[Qty] + 100"),
            declaration("Buffered", "[Qty] >= 1", fromStock))
        assertEquals(listOf("CLEARED", "CLEARED"), rows(m, orders(m), "Buffered"))
    }

    @Test fun aRowPastCapacityClearsInATableOfJoinedDeclarations() {
        val m = model(2, declaration("Pooled", "[Qty] >= 1", fromStock))
        assertEquals(listOf("VALUE|3", "VALUE|4", "CLEARED"), rows(m, orders(m, "C"), "Pooled"))
    }
}
