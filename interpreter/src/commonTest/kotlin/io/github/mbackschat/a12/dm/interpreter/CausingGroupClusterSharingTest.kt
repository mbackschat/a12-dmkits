package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Every message of one duplicate cluster names the cluster's key cells through ONE shared set.**
 *
 * A12 names each cluster cell on every message the cluster raises (KERNEL-FINDINGS KF683), so a dense N-row
 * cluster reports N groups of N cells. Building each message its own N-cell set is O(N²) allocation, the class
 * that exhausted the heap at 8,000 rows on the `referenced` channel (INTERPRETER-FINDINGS IF256); the engine
 * shares that channel's list per cluster (IF191), and the groups must share their cluster part the same way.
 * Allocation, not time, is the instrument: a timing guard stays green on this defect (IF256).
 */
class CausingGroupClusterSharingTest {

    @Test
    fun aPureClusterSharesOneSetAcrossItsMessages() {
        val groups = clusterGroups("RepetitionNotUnique(Items/Count)", count = { "5" })

        assertEquals(List(ROWS) { listOf(ROWS) }, groups.map { message -> message.map { it.size } })
        assertTrue(groups.all { it.single() === groups.first().single() }, "each message rebuilt the cluster's set")
    }

    /** A row's own entity joins the cluster's cells as a view over the shared set, never a copy of it. */
    @Test
    fun aRowEntityJoinsTheClusterWithoutCopyingIt() {
        val groups = clusterGroups("FieldFilled(Items/Sku) And RepetitionNotUnique(Items/Count)", count = { "5" })
            .map { it.single() }

        assertTrue(groups.all { it.size > ROWS }, "the row's own entity must join the cluster: ${groups.map { it.size }}")
        val shared = groups.map { (it as CausingUnion).cluster }
        assertTrue(shared.all { it === shared.first() }, "each message rebuilt the cluster's cells")
    }

    private fun clusterGroups(condition: String, count: (Int) -> String): List<List<Set<A12PartiallyKnownMultiPointer>>> {
        val model = ModelDef(
            fields = listOf(FieldDef(SKU, Kind.STRING), FieldDef(COUNT, Kind.NUMBER), FieldDef(NOTE, Kind.STRING)),
            rules = listOf(RuleDef("/Order/RU", "/Order", condition, "RU", "../Items/Count")),
            repeatableGroups = setOf(ITEMS),
            declaredReps = mapOf(ITEMS to ROWS),
            indexFields = setOf(SKU),
        )
        val document = model.newDocument().group("/Order", 1).also { doc ->
            (1..ROWS).forEach { row ->
                doc.group(ITEMS, 1, row).field(SKU, "K$row", 1, row, 1).field(COUNT, count(row), 1, row, 1)
            }
        }
        return DmInterpreter(model).validateFull(document).filter { it.code == "RU" }
            .map { it.groupsOfPossiblyCausingEntities }
    }

    private companion object {
        const val ROWS = 6
        const val ITEMS = "/Order/Items"
        const val SKU = "/Order/Items/Sku"
        const val COUNT = "/Order/Items/Count"
        const val NOTE = "/Order/Items/Note"
    }
}
