package io.github.mbackschat.a12.dm.interpreter.conformance

import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [kernelComputeGranularity] — the projection of the interpreter's report-ALL compute signatures onto the
 * KERNEL's compute reporting granularity (CONFORMANCE-CORPUS-SPEC §7), on BOTH targets (JVM + JS). The kernel's
 * `IDocumentComputationResult` reports a VALUE only when it CHANGED vs the input document (a TYPED-value
 * equality), a CLEARED instance only when the input had it FILLED, and an ERRORED instance unconditionally.
 * On a placement-built input document the cell values are raw STRINGS, so the typed equality can hold only
 * for a STRING-kind computed target (a number/date/boolean computes to a typed value, never equal to the raw
 * string) — kernel-probe-verified (KERNEL-FINDINGS "compute reporting granularity").
 */
class ComputeGranularityTest {

    private val model = ModelDef(
        listOf(
            FieldDef("/Order/Total", Kind.NUMBER, scale = 0),
            FieldDef("/Order/Label", Kind.STRING),
            FieldDef("/Order/Note", Kind.STRING),
            FieldDef("/Order/Items/Name", Kind.STRING),
        ),
        emptyList(),
    )

    private val placements = listOf(
        Placement(PlacementKind.GROUP, "/Order", listOf(1)),
        Placement(PlacementKind.FIELD, "/Order/Total", listOf(1, 1), "20"),
        Placement(PlacementKind.FIELD, "/Order/Label", listOf(1, 1), "AB-X"),
        Placement(PlacementKind.GROUP, "/Order/Items", listOf(1, 2)),
        Placement(PlacementKind.FIELD, "/Order/Items/Name", listOf(1, 2, 1), "B-X"),
        Placement(PlacementKind.EMPTY, "/Order/Note", listOf(1, 1)),
    )

    private fun project(vararg sigs: String): List<String> =
        kernelComputeGranularity(sigs.toList(), placements, model)

    @Test
    fun dropsAStringValueEqualToItsSeededInput() {
        assertEquals(
            emptyList(),
            project("/Order[1]/Label|VALUE|AB-X"),
            "a STRING computed back to its seed is a kernel NON-change (typed String == seed string) — not reported",
        )
    }

    @Test
    fun keepsANumberValueEqualToItsSeededInput() {
        assertEquals(
            listOf("/Order[1]/Total|VALUE|20"),
            project("/Order[1]/Total|VALUE|20"),
            "a NUMBER cell's typed value (BigDecimal) never equals the raw seed string — the kernel reports it",
        )
    }

    @Test
    fun keepsAStringValueDifferingFromItsSeed() {
        assertEquals(
            listOf("/Order[1]/Label|VALUE|CD-X"),
            project("/Order[1]/Label|VALUE|CD-X"),
        )
    }

    @Test
    fun keepsAValueOnAnUnseededCell() {
        assertEquals(
            listOf("/Order[1]/Note|VALUE|AB"),
            project("/Order[1]/Note|VALUE|AB"),
            "an EMPTY placement is present but not filled — computing a value into it is a change",
        )
    }

    @Test
    fun dropsAClearOnACellTheInputDidNotFill() {
        assertEquals(
            emptyList(),
            project("/Order[1]/Grand|CLEARED", "/Order[1]/Note|CLEARED"),
            "the kernel reports CLEARED only for instances FILLED in the input",
        )
    }

    @Test
    fun keepsAClearOnASeededCell() {
        assertEquals(
            listOf("/Order[1]/Total|CLEARED"),
            project("/Order[1]/Total|CLEARED"),
        )
    }

    @Test
    fun keepsErroredRegardlessOfTheInput() {
        assertEquals(
            listOf("/Order[1]/Grand|ERRORED", "/Order[1]/Total|ERRORED"),
            project("/Order[1]/Grand|ERRORED", "/Order[1]/Total|ERRORED"),
            "the kernel reports an ERRORED computed instance unconditionally",
        )
    }

    @Test
    fun matchesSeedsPerRow() {
        assertEquals(
            listOf("/Order[1]/Items[1]/Name|VALUE|B-X"),
            project("/Order[1]/Items[1]/Name|VALUE|B-X", "/Order[1]/Items[2]/Name|VALUE|B-X"),
            "the seed is matched at the exact row pointer — only row 2 was seeded with B-X",
        )
    }

    @Test
    fun preparedProjectionDoesNotRescanInputPlacementsPerResult() {
        val counted = ReadCountingList(placements)
        val projection = KernelComputeProjection.prepare(counted, model)
        val preparationReads = counted.reads

        repeat(3) {
            assertEquals(emptyList(), projection.project(listOf("/Order[1]/Label|VALUE|AB-X")))
        }

        assertEquals(placements.size, preparationReads, "preparation indexes each placement once")
        assertEquals(preparationReads, counted.reads, "result projection reuses the prepared input index")
    }

    private class ReadCountingList<T>(private val values: List<T>) : AbstractList<T>() {
        var reads: Int = 0
            private set

        override val size: Int get() = values.size

        override fun get(index: Int): T = values[index].also { reads++ }
    }
}
