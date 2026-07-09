package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multiplatform (JVM + JS) lock for IF96 — PARALLEL ITERATION for computations: operands referencing
 * out-of-scope sibling repeatable groups join them to the target's scope by the shared INDEX field
 * (join-by-key, never physical row position); an unmatched side reads empty (empty-as-0); an INVALID index
 * (duplicate/empty key) in ANY joined group clears EVERY instance; the calc never creates a repetition.
 * Kernel-verified by `adapter.laws.ComputeParallelIterationDiffTest`.
 */
class ComputeParallelIterationTest {

    private val TOTAL = "/Ledger/Total/Amount"

    private fun model(): ModelDef {
        val fields = mutableListOf<FieldDef>()
        for (g in listOf("Sales", "Refunds", "Fees", "Total")) {
            fields += FieldDef("/Ledger/$g/Category", Kind.STRING)
            fields += FieldDef("/Ledger/$g/Amount", Kind.NUMBER, scale = 0)
        }
        return ModelDef(
            fields = fields,
            rules = emptyList(),
            computations = listOf(ComputationDef(TOTAL, "/Ledger/Total",
                "[../Sales/Amount] + [../Refunds/Amount] + [../Fees/Amount]")),
            declaredReps = listOf("Sales", "Refunds", "Fees", "Total").associate { "/Ledger/$it" to 99 },
            indexFields = listOf("Sales", "Refunds", "Fees", "Total").map { "/Ledger/$it/Category" }.toSet(),
        )
    }

    private fun doc(m: ModelDef, rows: List<Triple<String, Int, Pair<String, String?>>>) =
        m.newDocument().group("/Ledger", 1).also { d ->
            for ((g, r, kv) in rows) {
                d.group("/Ledger/$g", 1, r).field("/Ledger/$g/Category", kv.first, 1, r, 1)
                kv.second?.let { d.field("/Ledger/$g/Amount", it, 1, r, 1) }
            }
        }

    private fun outcomes(m: ModelDef, d: io.github.mbackschat.a12.dm.interpreter.eval.Document) =
        DmInterpreter(m).compute(d).filter { it.path == TOTAL }
            .associate { it.indices.joinToString(",") to (it.outcome to it.value) }

    @Test fun joinIsByIndexKeyNotRowPosition() {
        val m = model()
        val out = outcomes(m, doc(m, listOf(
            Triple("Sales", 1, "K1" to "100"), Triple("Sales", 2, "K2" to "200"),
            Triple("Refunds", 1, "K1" to "10"), Triple("Refunds", 2, "K2" to "20"),
            Triple("Fees", 1, "K2" to "2"), Triple("Fees", 2, "K1" to "1"),   // physically scrambled
            Triple("Total", 1, "K1" to "9"), Triple("Total", 2, "K2" to "9"))))
        assertEquals(ComputeOutcome.VALUE to "111", out["1,1,1"], "Total@K1 = 100+10+1 (Fees joined by KEY)")
        assertEquals(ComputeOutcome.VALUE to "222", out["1,2,1"], "Total@K2 = 200+20+2")
    }

    @Test fun anUnmatchedSideReadsEmptyAsZero() {
        val m = model()
        val out = outcomes(m, doc(m, listOf(
            Triple("Sales", 1, "K1" to "100"), Triple("Sales", 2, "K2" to "200"),
            Triple("Refunds", 1, "K1" to "10"), Triple("Refunds", 2, "K2" to "20"),
            Triple("Fees", 1, "K1" to "1"),
            Triple("Total", 1, "K1" to "9"), Triple("Total", 2, "K2" to "9"))))
        assertEquals(ComputeOutcome.VALUE to "220", out["1,2,1"], "Fees has no K2 row → empty-as-0")
    }

    @Test fun anInvalidIndexAnywhereClearsEveryInstance() {
        val m = model()
        val out = outcomes(m, doc(m, listOf(
            Triple("Sales", 1, "K1" to "100"), Triple("Sales", 2, "K1" to "150"),   // duplicate side keys
            Triple("Refunds", 1, "K1" to "10"),
            Triple("Fees", 1, "K1" to "1"),
            Triple("Total", 1, "K1" to "9"), Triple("Total", 2, "K2" to "9"))))
        assertEquals(setOf<ComputeOutcome?>(ComputeOutcome.CLEARED), out.values.map { it.first }.toSet(),
            "a duplicated key in ANY joined group clears every instance — even the clean K2")
    }
}
