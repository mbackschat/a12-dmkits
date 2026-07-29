package io.github.mbackschat.a12.dm.interpreter.conformance

import io.github.mbackschat.a12.dm.interpreter.gen.PlacementSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The multiplatform conformance-case (de)serialization ([CaseJson]) — round-trip + shape on BOTH targets
 * (JVM + JS; `:interpreter:check` runs both). The model lives in a standalone file the case references by
 * `modelRef`; every field survives a `write` → `parse` round trip; and [replayInto] drives the [PlacementSink]
 * in order. [CaseJson.writeModel] pretty-prints a model file preserving every literal.
 */
class CaseJsonTest {

    private val modelRef = "models/policy-claims.json"

    @Test
    fun roundTripsAValidateFullCase() {
        val case = ConformanceCase(
            meta = CaseMeta("claim-min-001", "30.8.1", CaseSource.CURATED, listOf("§9"),
                tags = listOf("aggregates"), notes = "MinValue over one filled row"),
            modelRef = modelRef,
            document = """{"Policy":{"Claims":[{"Amount":"20.00","Note":null}]}}""",
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
            modelRef = modelRef,
            document = """{"Policy":{}}""",
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
            modelRef = modelRef,
            document = """{"Order":{}}""",
            op = Op.Compute,
            computeExpected = listOf("/Order[1]/Items[1]/StockQty|VALUE|20", "/Order[1]/Items[2]/StockQty|VALUE|30"),
        )
        assertEquals(case, CaseJson.parse(CaseJson.write(case)))
    }

    @Test
    fun writingACaseParsesItsDocumentMemberWithTheSharedA12Codec() {
        val case = ConformanceCase(
            meta = CaseMeta("commented-document-004", "30.8.1", CaseSource.CURATED, listOf("§2")),
            modelRef = modelRef,
            document = """
                {
                  // A12 accepts comments in Document JSON; the case envelope itself remains strict.
                  "Policy": {"Note": "kept"}
                }
            """.trimIndent(),
            op = Op.ValidateFull,
            expected = emptyList(),
        )

        assertEquals(
            """{"Policy":{"Note":"kept"}}""",
            CaseJson.parse(CaseJson.write(case)).document,
            "the A12 member loses only comment syntax and retains its value",
        )
    }

    @Test
    fun parsesTheSpecShapeAndKeepsOptionalsAbsent() {
        val text = """
            {
              "meta": { "id": "c", "kernelVersion": "30.8.1", "source": "curated", "sections": ["§9"] },
              "modelRef": "models/policy.json",
              "document": { "Policy": {} },
              "op": { "kind": "validateFull" },
              "expected": []
            }
        """.trimIndent()
        val case = CaseJson.parse(text)
        assertEquals("c", case.meta.id)
        assertEquals("models/policy.json", case.modelRef)
        assertEquals(CaseSource.CURATED, case.meta.source)
        assertTrue(case.meta.tags.isEmpty())
        assertNull(case.clock)
        assertNull(case.computeExpected)
        assertEquals(emptyList(), case.expected)   // an empty expected is a real assertion, not "absent"
        assertEquals(Op.ValidateFull, case.op)
    }

    @Test
    fun missingModelRefThrowsCorrectiveError() {
        val text = """{"meta":{"id":"c","kernelVersion":"30.8.1","source":"curated","sections":["§9"]},
            "document":{},"op":{"kind":"validateFull"},"expected":[]}"""
        val ex = assertFailsWith<IllegalArgumentException> { CaseJson.parse(text) }
        assertTrue("modelRef" in (ex.message ?: ""), "the error names the missing modelRef key")
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
            "modelRef":"models/x.json","document":{},"op":{"kind":"validateFull"},"expected":[]}"""
        assertFailsWith<IllegalArgumentException> { CaseJson.parse(text) }
    }
}
