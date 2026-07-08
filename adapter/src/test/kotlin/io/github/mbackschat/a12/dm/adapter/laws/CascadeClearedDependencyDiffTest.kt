package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.model.ComputationAlternative
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential for CASCADE CLEARING (IF77, found via the IG44 VCV shrink): when a computation CLEARS its target
 * (a false precondition / no matching alternative), a DOWNSTREAM computation that reads it must see the target
 * as **EMPTY** — not the stale STORED (seeded) value. Before the fix the interpreter's cascade overlay recorded
 * only computed VALUES, so a dependent read the seed cell of a just-cleared field: its `FieldFilled(dep)` read
 * stale-filled and it computed a value where the kernel (seeing the dependency empty) clears.
 *
 * Two chained computations on the order fixture: `Mid = [Quantity]` gated by `FieldFilled(Guard)`, then
 * `Out = [Mid]` gated by `FieldFilled(Mid)`. The target cells are seeded to a sentinel so a clear is a reported
 * CHANGE (comparable to the kernel's change-only compute report).
 */
class CascadeClearedDependencyDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val QTY = "/Order/Quantity"
    private val GUARD = "/Order/Guard"
    private val MID = "/Order/Mid"
    private val OUT = "/Order/Out"

    private val intField = """{ "type": "Field", "id": "%s", "name": "%s", "Field": { "fieldType": {
        "type": "NumberType", "NumberType": { "maxFractionalDigits": 0 } } } },"""

    /** Splice Guard/Mid/Out (integer) + the two chained computations into /Order; return the kernel model JSON. */
    private fun model(): String {
        var json = order
        for ((path, name) in listOf(GUARD to "Guard", MID to "Mid", OUT to "Out")) {
            val g = json.indexOf("\"id\" : \"/Order\"").let { if (it < 0) json.indexOf("\"id\": \"/Order\"") else it }
            val br = json.indexOf('[', json.indexOf("\"elements\"", g))
            json = json.substring(0, br + 1) + "\n" + intField.format(path, name).replace("\n", " ") + json.substring(br + 1)
        }
        json = RuntimeLaws.withTieredComputation(json, "/Order", "CompMid", "../Mid",
            "FieldFilled(Guard)", listOf(RuntimeLaws.CompAlt(null, "[Quantity]")))
        json = RuntimeLaws.withTieredComputation(json, "/Order", "CompOut", "../Out",
            "FieldFilled(Mid)", listOf(RuntimeLaws.CompAlt(null, "[Mid]")))
        return json
    }

    private val def = ModelDef(
        listOf(FieldDef(QTY, Kind.NUMBER), FieldDef(GUARD, Kind.NUMBER), FieldDef(MID, Kind.NUMBER), FieldDef(OUT, Kind.NUMBER)),
        emptyList(),
        computations = listOf(
            ComputationDef(MID, "/Order", listOf(ComputationAlternative(null, "[Quantity]")), "FieldFilled(Guard)"),
            ComputationDef(OUT, "/Order", listOf(ComputationAlternative(null, "[Mid]")), "FieldFilled(Mid)"),
        ),
    )

    private fun kernelOutcomes(model: String, values: Map<String, String>): Map<String, String> {
        val doc = RuntimeLaws.doc().group("/Order", 1).field(MID, "77", 1, 1).field(OUT, "88", 1, 1)
        values.forEach { (p, v) -> doc.field(p, v, 1, 1) }
        return RuntimeLaws.compute(model, doc).filter { it.path() == MID || it.path() == OUT }
            .associate { it.path() to if (it.cleared()) "CLEARED" else if (it.errored()) "ERRORED" else "VALUE|${it.value().orElse("")}" }
    }

    private fun interpOutcomes(values: Map<String, String>): Map<String, String> {
        val doc = Document(def.kinds, def.declaredReps, def.unsignedFields, def.rangeConfig, def.dateFormats, def.scales)
            .group("/Order", 1)
        doc.field(MID, "77", 1, 1); doc.field(OUT, "88", 1, 1)
        values.forEach { (p, v) -> doc.field(p, v, 1, 1) }
        return DmInterpreter(def).compute(doc).filter { it.path == MID || it.path == OUT }.associate {
            it.path to when (it.outcome) {
                ComputeOutcome.CLEARED -> "CLEARED"; ComputeOutcome.ERRORED -> "ERRORED"; ComputeOutcome.VALUE -> "VALUE|${it.value}"
            }
        }
    }

    private fun agree(values: Map<String, String>, msg: String) {
        val m = model()
        assertEquals(kernelOutcomes(m, values), interpOutcomes(values), "$msg — kernel vs interpreter (Mid,Out)")
    }

    @Test fun clearPropagatesToDependent() =
        // Guard empty → Mid CLEARED → Out must clear too (NOT compute from the stale seeded Mid=77).
        agree(mapOf(QTY to "5"), "Guard empty → Mid cleared → Out cleared")

    @Test fun bothComputeWhenGuardFilled() =
        agree(mapOf(GUARD to "1", QTY to "5"), "Guard filled → Mid=5 → Out=5")

    @Test fun emptyOperandPropagatesAsZero() =
        // Guard filled, Quantity empty → Mid = empty-as-0 = 0 (a VALUE) → Out reads 0 → Out = 0 (cascade, not cleared).
        agree(mapOf(GUARD to "1"), "Quantity empty → Mid=0 → Out=0")
}
