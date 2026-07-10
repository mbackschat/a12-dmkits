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
 * Multiplatform (JVM + JS) lock for IF98 — a downstream computation's `Having` filter reads a COMPUTED
 * dependency through the CASCADE: a cleared upstream target reads EMPTY (IF77) and a poisoned one poisons the
 * reader (IF85) — never the stale stored cell. Requires BOTH halves: the cascade dependency order includes a
 * `Having` filter's refs (and a precondition's starred refs), and the overlay wraps the row contexts a filter
 * iterates. Kernel-verified by the tri-check differential `adapter.laws.HavingOverComputedDependencyDiffTest`.
 */
class HavingOverComputedDependencyTest {

    private val END = "/Shop/Affiliates/EndDate"
    private val COMMISSION = "/Shop/Affiliates/Commission"
    private val WEIGHT = "/Shop/Affiliates/Weight"
    private val FLAG = "/Shop/Affiliates/ActiveFlag"
    private val TOTAL = "/Shop/TotalCommission"

    private fun model(activeCond: String = "FieldNotFilled(EndDate)", guard: String? = null) = ModelDef(
        fields = listOf(
            FieldDef(END, Kind.DATE), FieldDef(COMMISSION, Kind.NUMBER, scale = 2),
            FieldDef(WEIGHT, Kind.NUMBER, scale = 2), FieldDef(FLAG, Kind.BOOLEAN),
            FieldDef(TOTAL, Kind.NUMBER, scale = 2),
        ),
        rules = emptyList(),
        computations = listOf(
            // declared downstream-FIRST on purpose: only the dependency order runs the flag computation first
            ComputationDef(TOTAL, "/Shop", listOf(ComputationAlternative(guard,
                "Sum(/Shop/Affiliates*/Commission Having [/Shop/Affiliates/ActiveFlag] == True)"))),
            ComputationDef(FLAG, "/Shop/Affiliates", listOf(ComputationAlternative(activeCond, "True"))),
        ),
        repeatableGroups = setOf("/Shop/Affiliates"),
    )

    private fun outcomes(def: ModelDef, build: (io.github.mbackschat.a12.dm.interpreter.eval.Document) -> Unit): Map<String, String> {
        val doc = def.newDocument().group("/Shop", 1)
        doc.field(TOTAL, "999.99", 1, 1)
        build(doc)
        return DmInterpreter(def).compute(doc).associate {
            "${it.path}@${it.indices.joinToString(",")}" to when (it.outcome) {
                ComputeOutcome.VALUE -> "VALUE|${it.value}"
                ComputeOutcome.CLEARED -> "CLEARED"
                ComputeOutcome.ERRORED -> "ERRORED"
            }
        }
    }

    @Test fun clearedFlagReadsEmptyInTheDownstreamFilter() {
        val out = outcomes(model()) { doc ->
            doc.group("/Shop/Affiliates", 1, 1).field(COMMISSION, "100.00", 1, 1, 1)
                .field(FLAG, "true", 1, 1, 1).field(END, "2024-03-05", 1, 1, 1)
        }
        assertEquals("CLEARED", out["$FLAG@1,1,1"], "ended affiliate → the flag computation clears")
        assertEquals("VALUE|0", out["$TOTAL@1,1"], "the stale stored flag must not keep the row — Sum 0")
    }

    @Test fun mixedRowsSumOnlyTheCascadeActiveOne() {
        val out = outcomes(model()) { doc ->
            doc.group("/Shop/Affiliates", 1, 1).field(COMMISSION, "40.00", 1, 1, 1).field(FLAG, "false", 1, 1, 1)
            doc.group("/Shop/Affiliates", 1, 2).field(COMMISSION, "100.00", 1, 2, 1)
                .field(FLAG, "true", 1, 2, 1).field(END, "2024-03-05", 1, 2, 1)
        }
        assertEquals("VALUE|true", out["$FLAG@1,1,1"], "no end date → active")
        assertEquals("CLEARED", out["$FLAG@1,2,1"], "ended → cleared")
        assertEquals("VALUE|40", out["$TOTAL@1,1"], "the filter keeps the CASCADE-active row, not the stale seeds")
    }

    @Test fun poisonedFlagPoisonsTheDownstreamFilterRead() {
        val out = outcomes(model(activeCond = "[Weight] >= 0")) { doc ->
            doc.group("/Shop/Affiliates", 1, 1).field(COMMISSION, "100.00", 1, 1, 1)
                .field(FLAG, "true", 1, 1, 1).field(WEIGHT, "5.123", 1, 1, 1)
        }
        assertEquals("CLEARED", out["$FLAG@1,1,1"], "the invalid weight poisons the flag computation (IF85)")
        assertEquals("CLEARED", out["$TOTAL@1,1"], "the filter's read of the poisoned flag poisons the sum")
    }

    @Test fun preconditionQuantifierHavingReadsTheCascadeToo() {
        val out = outcomes(model(guard =
            "AtLeastOneFieldFilled(/Shop/Affiliates*/Commission Having [/Shop/Affiliates/ActiveFlag] == True)")) { doc ->
            doc.group("/Shop/Affiliates", 1, 1).field(COMMISSION, "100.00", 1, 1, 1)
                .field(FLAG, "true", 1, 1, 1).field(END, "2024-03-05", 1, 1, 1)
        }
        assertEquals("CLEARED", out["$FLAG@1,1,1"])
        assertEquals("CLEARED", out["$TOTAL@1,1"], "the guard quantifier sees the cleared flag as empty → false → CLEARED")
    }
}
