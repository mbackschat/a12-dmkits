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
 * Multiplatform (JVM + JS/Node) lock for IF220 — an OVER-LIMIT row is outside the parallel-iteration join
 * (the index-keyed outer join, KERNEL-SEMANTICS §9): its index value is not a join key, so a reference to it
 * reads UNMATCHED (empty-as-0), never its poisoned cell. Sibling groups `Demand`/`Supply`/`Total` (cap 2, shared
 * `Category` index) under `/Ledger`. Kernel-verified (both strategies) by
 * `adapter.laws.OverLimitParallelIterationDiffTest`; this replays the interpreter engine on both platforms.
 */
class OverLimitParallelIterationTest {

    private fun model(computation: ComputationDef) = ModelDef(
        fields = listOf("Demand", "Supply", "Total").flatMap {
            listOf(FieldDef("/Ledger/$it/Category", Kind.STRING), FieldDef("/Ledger/$it/Amount", Kind.NUMBER, scale = 0))
        },
        rules = emptyList(),
        computations = listOf(computation),
        declaredReps = mapOf("/Ledger/Demand" to 2, "/Ledger/Supply" to 2, "/Ledger/Total" to 2),
        indexFields = setOf("/Ledger/Demand/Category", "/Ledger/Supply/Category", "/Ledger/Total/Category"),
    )

    private fun doc(m: ModelDef, rows: Map<String, List<Pair<String, String>>>) =
        m.newDocument().group("/Ledger", 1).also { d ->
            rows.forEach { (g, list) ->
                list.forEachIndexed { i, (k, v) ->
                    d.group("/Ledger/$g", 1, i + 1).field("/Ledger/$g/Category", k, 1, i + 1, 1).field("/Ledger/$g/Amount", v, 1, i + 1, 1)
                }
            }
        }

    /** Total[Extra] joins Demand's Extra key, but Demand's Extra row is OVER-LIMIT (row 3, cap 2) → outside the
     *  join → the side reads empty-as-0, so Total[Extra] computes 0 (not poisoned/cleared). */
    @Test fun computeOverLimitJoinedSideReadsEmptyAsZero() {
        val m = model(ComputationDef("/Ledger/Total/Amount", "/Ledger/Total", "[../Demand/Amount] + [../Supply/Amount]"))
        val d = doc(m, mapOf(
            "Demand" to listOf("Sales" to "5", "Eng" to "3", "Extra" to "9"),   // Extra is row 3, over cap 2
            "Supply" to listOf("Sales" to "5"),
            "Total" to listOf("Sales" to "0", "Extra" to "0"),
        ))
        val out = DmInterpreter(m).compute(d).filter { it.path == "/Ledger/Total/Amount" }
            .associate { it.indices.joinToString(",") to (it.outcome to it.value) }
        assertEquals(ComputeOutcome.VALUE to "10", out["1,1,1"], "Total@Sales = 5 + 5")
        assertEquals(ComputeOutcome.VALUE to "0", out["1,2,1"], "Total@Extra: Demand's Extra is over-limit → empty-as-0, not poisoned")
    }
}
