package io.github.mbackschat.a12.dm.interpreter.conformance

import io.github.mbackschat.a12.dm.interpreter.gen.PlacementSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The multiplatform conformance-case (de)serialization ([CaseJson]) — round-trip + shape on BOTH targets
 * (JVM + JS; `:interpreter:check` runs both). The model sub-object is carried through verbatim; every other
 * field survives a `write` → `parse` round trip; and [replayInto] drives the [PlacementSink] in order.
 */
class CaseJsonTest {

    // Model text is COMPACT (kotlinx JsonElement.toString form) so the full-object round trip holds — the parser
    // stores model as the canonical tree string, and the writer re-embeds it verbatim.
    private val model = """{"header":{"id":"m","modelType":"document"},"content":{"modelRoot":{"rootGroups":[]}}}"""

    @Test
    fun roundTripsAValidateFullCase() {
        val case = ConformanceCase(
            meta = CaseMeta("claim-min-001", "30.8.1", CaseSource.CURATED, listOf("§9"),
                tags = listOf("aggregates"), notes = "MinValue over one filled row"),
            model = model,
            placements = listOf(
                Placement(PlacementKind.GROUP, "/Policy", listOf(1)),
                Placement(PlacementKind.GROUP, "/Policy/Claims", listOf(1, 1)),
                Placement(PlacementKind.FIELD, "/Policy/Claims/Amount", listOf(1, 1, 1), "20.00"),
                Placement(PlacementKind.EMPTY, "/Policy/Claims/Note", listOf(1, 1, 1)),
            ),
            op = Op.ValidateFull,
            expected = listOf("CLAIM_MIN|VALUE_ERROR|/Policy[1]/Claims[1]/Amount"),
        )
        assertEquals(case, CaseJson.parse(CaseJson.write(case)))
    }

    @Test
    fun roundTripsAPartialCaseWithClockAndDivergences() {
        val case = ConformanceCase(
            meta = CaseMeta("phantom-row-002", "30.8.1", CaseSource.FUZZ_SEED, listOf("§10"),
                tags = listOf("partial", "phantom-row"), seed = 42, scenario = "policyClaims"),
            model = model,
            placements = listOf(Placement(PlacementKind.GROUP, "/Policy", listOf(1))),
            op = Op.ValidatePart(listOf(RelevantPointer("/Policy/Claims/Amount", listOf(1, 1, 1)))),
            clock = "2026-07-06T12:00:00",
            expected = listOf("CLAIM_MIN|VALUE_ERROR|/Policy[1]/Claims[1]/Amount"),
            divergences = listOf(Divergence("kernel-java-static", expected = emptyList())),
        )
        assertEquals(case, CaseJson.parse(CaseJson.write(case)))
    }

    @Test
    fun roundTripsAComputeCase() {
        val case = ConformanceCase(
            meta = CaseMeta("compute-perrow-003", "30.8.1", CaseSource.CURATED, listOf("§2")),
            model = model,
            placements = listOf(Placement(PlacementKind.GROUP, "/Order", listOf(1))),
            op = Op.Compute,
            computeExpected = listOf("/Order[1]/Items[1]/StockQty|VALUE|20", "/Order[1]/Items[2]/StockQty|VALUE|30"),
        )
        assertEquals(case, CaseJson.parse(CaseJson.write(case)))
    }

    @Test
    fun parsesTheSpecShapeAndKeepsOptionalsAbsent() {
        val text = """
            {
              "meta": { "id": "c", "kernelVersion": "30.8.1", "source": "curated", "sections": ["§9"] },
              "model": { "header": { "id": "m" } },
              "placements": [ { "kind": "GROUP", "path": "/Policy", "reps": [1] } ],
              "op": { "kind": "validateFull" },
              "expected": []
            }
        """.trimIndent()
        val case = CaseJson.parse(text)
        assertEquals("c", case.meta.id)
        assertEquals(CaseSource.CURATED, case.meta.source)
        assertTrue(case.meta.tags.isEmpty())
        assertNull(case.clock)
        assertNull(case.computeExpected)
        assertEquals(emptyList(), case.expected)   // an empty expected is a real assertion, not "absent"
        assertEquals(Op.ValidateFull, case.op)
    }

    @Test
    fun replaysPlacementsInOrder() {
        val calls = mutableListOf<String>()
        val sink = object : PlacementSink {
            override fun group(path: String, vararg reps: Int) { calls += "G $path ${reps.toList()}" }
            override fun field(path: String, value: String, vararg reps: Int) { calls += "F $path=$value ${reps.toList()}" }
            override fun emptyField(path: String, vararg reps: Int) { calls += "E $path ${reps.toList()}" }
        }
        listOf(
            Placement(PlacementKind.GROUP, "/Policy", listOf(1)),
            Placement(PlacementKind.FIELD, "/Policy/Total", listOf(1, 1), "9.90"),
            Placement(PlacementKind.EMPTY, "/Policy/Note", listOf(1, 1)),
        ).replayInto(sink)
        assertEquals(listOf("G /Policy [1]", "F /Policy/Total=9.90 [1, 1]", "E /Policy/Note [1, 1]"), calls)
    }

    @Test
    fun malformedJsonThrowsIllegalArgument() {
        assertFailsWith<IllegalArgumentException> { CaseJson.parse("{ not json") }
    }

    @Test
    fun unknownSourceThrows() {
        val text = """{"meta":{"id":"c","kernelVersion":"30.8.1","source":"nope","sections":[]},
            "model":{},"placements":[],"op":{"kind":"validateFull"},"expected":[]}"""
        assertFailsWith<IllegalArgumentException> { CaseJson.parse(text) }
    }
}
