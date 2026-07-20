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
 * Locks the seed generator's structural-validity boundary: every requested repeatable-row count is checked
 * against the model before the first placement reaches the consumer.
 */
class SeedGeneratorRowContractTest {

    @Test
    fun siblingOverflowRefusesBeforeEarlierSiblingMutatesTheSink() {
        val model = siblingModel(aCap = 2, bCap = 2)
        val sink = RecordingSink()
        var sampledValues = 0

        val error = assertFailsWith<IllegalArgumentException> {
            SeedGenerator(model).populate(
                sink,
                rowsOf = { group, _, _ -> if (group == A) 1 else 3 },
                value = { _, _, _ -> "value".also { sampledValues++ } },
            )
        }

        assertEquals(
            "row count for repeatable group '$B' exceeds declared repeatability: requested 3, cap 2",
            error.message,
        )
        assertEquals(0, sink.placements, "the valid first sibling must not leak before the later refusal")
        assertEquals(0, sampledValues, "row preflight must not speculatively invoke the value strategy")
    }

    @Test
    fun nestedOverflowOnSecondParentRefusesBeforeAnySinkMutation() {
        val lines = "$ORDERS/Lines"
        val model = ModelDef(
            fields = listOf(FieldDef("$lines/Value", Kind.STRING)),
            rules = emptyList(),
            declaredReps = mapOf(ORDERS to 2, lines to 2),
        )
        val sink = RecordingSink()

        val error = assertFailsWith<IllegalArgumentException> {
            SeedGenerator(model).populate(sink, rowsOf = { group, parentReps, _ ->
                when (group) {
                    ORDERS -> 2
                    lines -> if (parentReps.last() == 1) 2 else 3
                    else -> 0
                }
            })
        }

        assertEquals(
            "row count for repeatable group '$lines' exceeds declared repeatability: requested 3, cap 2",
            error.message,
        )
        assertEquals(0, sink.placements, "the complete variable-count plan must validate before emission")
    }

    @Test
    fun fixedDefaultIsCheckedAgainstEveryGroupsOwnCap() {
        val model = siblingModel(aCap = 5, bCap = 2)
        val sink = RecordingSink()

        val error = assertFailsWith<IllegalArgumentException> {
            SeedGenerator(model).populate(sink, 0L, mapOf(A to 1), 3)
        }

        assertEquals(
            "row count for repeatable group '$B' exceeds declared repeatability: requested 3, cap 2",
            error.message,
        )
        assertEquals(0, sink.placements)
    }

    @Test
    fun fixedExplicitCountIsCheckedAgainstItsGroupsCap() {
        val model = siblingModel(aCap = 5, bCap = 2)
        val sink = RecordingSink()

        val error = assertFailsWith<IllegalArgumentException> {
            SeedGenerator(model).populate(sink, 0L, mapOf(B to 3), 0)
        }

        assertEquals(
            "row count for repeatable group '$B' exceeds declared repeatability: requested 3, cap 2",
            error.message,
        )
        assertEquals(0, sink.placements)
    }

    @Test
    fun negativeDynamicCountNamesTheGroupAndRefusesBeforeMutation() {
        val model = siblingModel(aCap = 5, bCap = 2)
        val sink = RecordingSink()

        val error = assertFailsWith<IllegalArgumentException> {
            SeedGenerator(model).populate(sink, rowsOf = { group, _, _ ->
                if (group == A) -1 else 0
            })
        }

        assertEquals(
            "row count for repeatable group '$A' must be non-negative: requested -1",
            error.message,
        )
        assertEquals(0, sink.placements)
    }

    @Test
    fun unknownExplicitGroupIsRejectedInsteadOfIgnored() {
        val model = siblingModel(aCap = 2, bCap = 2)
        val sink = RecordingSink()
        val unknown = "/Case/Missing"

        val error = assertFailsWith<IllegalArgumentException> {
            SeedGenerator(model).populate(sink, 0L, mapOf(unknown to 1), 0)
        }

        assertEquals(
            "row count requested for '$unknown', but it is not a declared repeatable group",
            error.message,
        )
        assertEquals(0, sink.placements)
    }

    @Test
    fun absentDeclaredMaximumMeansUnboundedWhileAnExactMaximumIsAccepted() {
        val group = "/Case/Rows"
        val unbounded = ModelDef(
            fields = listOf(FieldDef("$group/Value", Kind.STRING)),
            rules = emptyList(),
            repeatableGroups = setOf(group),
        )
        val bounded = unbounded.copy(declaredReps = mapOf(group to 4))

        val unboundedSink = RecordingSink()
        SeedGenerator(unbounded).populate(unboundedSink, rowsOf = { requested, _, _ ->
            if (requested == group) 4 else 0
        })
        val boundedSink = RecordingSink()
        SeedGenerator(bounded).populate(boundedSink, rowsOf = { requested, _, _ ->
            if (requested == group) 4 else 0
        })

        assertEquals(4, unboundedSink.groups.count { it == group })
        assertEquals(4, boundedSink.groups.count { it == group })
    }

    private fun siblingModel(aCap: Int, bCap: Int) = ModelDef(
        fields = listOf(FieldDef("$A/Value", Kind.STRING), FieldDef("$B/Value", Kind.STRING)),
        rules = emptyList(),
        declaredReps = mapOf(A to aCap, B to bCap),
    )

    private class RecordingSink : PlacementSink {
        val groups = mutableListOf<String>()
        var placements = 0
            private set

        override fun group(path: String, vararg reps: Int) {
            groups += path
            placements++
        }

        override fun field(path: String, value: String, vararg reps: Int) {
            placements++
        }

        override fun emptyField(path: String, vararg reps: Int) {
            placements++
        }
    }

    private companion object {
        const val A = "/Case/A"
        const val B = "/Case/B"
        const val ORDERS = "/Case/Orders"
    }
}
