package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.gen.PlacementSink
import io.github.mbackschat.a12.dm.interpreter.gen.SeedGenerator
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Locks profile-scale seeding: an exact target-row quota is distributed over the target group's repeatable
 * ancestor chain without exceeding any per-parent declared repeatability.
 */
class SeedGeneratorExactLeafRowsTest {

    @Test
    fun twoLevelNonDivisibleQuotaIsBalancedAcrossParents() {
        val model = model(
            leaf = ITEMS,
            caps = mapOf(BATCHES to 3, ITEMS to 4),
        )
        val sink = RecordingSink()

        SeedGenerator(model).populateExactLeafRows(sink, ITEMS, totalLeafRows = 10)

        assertEquals(
            listOf(
                listOf(1, 1, 1),
                listOf(1, 1, 2),
                listOf(1, 1, 3),
                listOf(1, 1, 4),
                listOf(1, 2, 1),
                listOf(1, 2, 2),
                listOf(1, 2, 3),
                listOf(1, 3, 1),
                listOf(1, 3, 2),
                listOf(1, 3, 3),
            ),
            sink.repetitionsOf(ITEMS),
        )
    }

    @Test
    fun threeLevelQuotaUsesEveryCapAndRemainsBalancedPerParent() {
        val model = model(
            leaf = ENTRIES,
            caps = mapOf(BATCHES to 2, PACKS to 3, ENTRIES to 4),
        )
        val sink = RecordingSink()

        SeedGenerator(model).populateExactLeafRows(sink, ENTRIES, totalLeafRows = 17)

        assertEquals(
            buildList {
                for (pack in 1..3) for (entry in 1..3) add(listOf(1, 1, pack, entry))
                for (pack in 1..2) for (entry in 1..4) add(listOf(1, 2, pack, entry))
            },
            sink.repetitionsOf(ENTRIES),
        )
    }

    @Test
    fun impossibleCapacityRefusesBeforeAnyPlacement() {
        val model = model(
            leaf = ENTRIES,
            caps = mapOf(BATCHES to 2, PACKS to 3, ENTRIES to 4),
        )
        val sink = RecordingSink()

        val error = assertFailsWith<IllegalArgumentException> {
            SeedGenerator(model).populateExactLeafRows(sink, ENTRIES, totalLeafRows = 25)
        }

        assertEquals(
            "leaf row count for '$ENTRIES' is impossible: requested 25, maximum declared capacity 24",
            error.message,
        )
        assertEquals(emptyList(), sink.events)
    }

    @Test
    fun invalidTargetAndNegativeCountsAreRejectedLoudly() {
        val model = model(
            leaf = ITEMS,
            caps = mapOf(BATCHES to 3, ITEMS to 4),
        )
        val generator = SeedGenerator(model)

        assertEquals(
            "exact leaf-row target '/Case/Missing' is not a declared repeatable group",
            assertFailsWith<IllegalArgumentException> {
                generator.populateExactLeafRows(RecordingSink(), "/Case/Missing", totalLeafRows = 1)
            }.message,
        )
        assertEquals(
            "exact leaf-row target '/Case' is not a declared repeatable group",
            assertFailsWith<IllegalArgumentException> {
                generator.populateExactLeafRows(RecordingSink(), "/Case", totalLeafRows = 1)
            }.message,
        )
        assertEquals(
            "leaf row count for '$ITEMS' must be non-negative: requested -1",
            assertFailsWith<IllegalArgumentException> {
                generator.populateExactLeafRows(RecordingSink(), ITEMS, totalLeafRows = -1)
            }.message,
        )
        assertEquals(
            "unrelated repeatable default must be non-negative: requested -1",
            assertFailsWith<IllegalArgumentException> {
                generator.populateExactLeafRows(RecordingSink(), ITEMS, totalLeafRows = 1, unrelatedRows = -1)
            }.message,
        )
    }

    @Test
    fun unrelatedDefaultOverflowRefusesBeforeTargetMutation() {
        val model = model(
            leaf = ITEMS,
            caps = mapOf(BATCHES to 3, ITEMS to 4, AUDITS to 1),
            additionalFields = listOf(FieldDef("$AUDITS/Note", Kind.STRING)),
        )
        val sink = RecordingSink()

        val error = assertFailsWith<IllegalArgumentException> {
            SeedGenerator(model).populateExactLeafRows(
                sink,
                ITEMS,
                totalLeafRows = 10,
                unrelatedRows = 2,
            )
        }

        assertEquals(
            "row count for repeatable group '$AUDITS' exceeds declared repeatability: requested 2, cap 1",
            error.message,
        )
        assertEquals(emptyList(), sink.events)
    }

    @Test
    fun placementsAreDeterministicAndUnrelatedSiblingsUseTheConfiguredDefault() {
        val model = model(
            leaf = ITEMS,
            caps = mapOf(BATCHES to 3, ITEMS to 4, AUDITS to 3),
            additionalFields = listOf(FieldDef("$AUDITS/Note", Kind.STRING)),
        )
        val generator = SeedGenerator(model)
        val first = RecordingSink()
        val second = RecordingSink()

        generator.populateExactLeafRows(first, ITEMS, totalLeafRows = 10, seed = 42L, unrelatedRows = 2)
        generator.populateExactLeafRows(second, ITEMS, totalLeafRows = 10, seed = 42L, unrelatedRows = 2)

        assertEquals(first.events, second.events)
        assertEquals(listOf(listOf(1, 1), listOf(1, 2)), first.repetitionsOf(AUDITS))
        assertEquals(10, first.repetitionsOf(ITEMS).size)
    }

    private fun model(
        leaf: String,
        caps: Map<String, Int>,
        additionalFields: List<FieldDef> = emptyList(),
    ) = ModelDef(
        fields = listOf(FieldDef("$leaf/Value", Kind.STRING)) + additionalFields,
        rules = emptyList(),
        declaredReps = caps,
    )

    private data class Placement(
        val kind: PlacementKind,
        val path: String,
        val repetitions: List<Int>,
        val value: String? = null,
    )

    private enum class PlacementKind { GROUP, FIELD, EMPTY }

    private class RecordingSink : PlacementSink {
        val events = mutableListOf<Placement>()

        fun repetitionsOf(path: String): List<List<Int>> =
            events.filter { it.kind == PlacementKind.GROUP && it.path == path }.map { it.repetitions }

        override fun group(path: String, vararg reps: Int) {
            events += Placement(PlacementKind.GROUP, path, reps.toList())
        }

        override fun field(path: String, value: String, vararg reps: Int) {
            events += Placement(PlacementKind.FIELD, path, reps.toList(), value)
        }

        override fun emptyField(path: String, vararg reps: Int) {
            events += Placement(PlacementKind.EMPTY, path, reps.toList())
        }
    }

    private companion object {
        const val BATCHES = "/Case/Batches"
        const val PACKS = "$BATCHES/Packs"
        const val ITEMS = "$BATCHES/Items"
        const val ENTRIES = "$PACKS/Entries"
        const val AUDITS = "/Case/Audits"
    }
}
