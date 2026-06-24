package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Applies the [KernelProbe] **battery as a DIFFERENTIAL** over ALREADY-implemented row/aggregate/iteration
 * operators — kernel vs interpreter on every scenario of the standard row matrix (0/1/2/3 rows × empty /
 * valid / malformed positions). The per-operator `*DiffTest`s pin hand-picked cases and the fuzz hits the
 * matrix randomly; this sweeps it **systematically**, so an edge gap can't hide between the curated cases.
 * (This is the harness that would have caught the `CurrentRepetition` malformed-operand gap deterministically —
 * the `malformed, valid` row is in the battery.) It asserts only **agreement**, so thresholds need no tuning.
 */
class OperatorBatteryDiffTest {

    private val fuzz = RuntimeLaws.model("models/fuzz.dm.json")
    private val fields = listOf(FieldDef(ITEM, Kind.STRING), FieldDef(QTY, Kind.NUMBER, scale = 0))

    /** Sweep `condition` (rule [code] @ [group], error [errorRel]) over the row battery; assert kernel == interp per scenario. */
    private fun agreeAcrossBattery(group: String, code: String, errorRel: String, condition: String) {
        val kModel = RuntimeLaws.withRule(fuzz, group, code, errorRel, code, condition)
        val def = ModelDef(fields, listOf(RuleDef("$group/$code", group, condition, code, errorRel)),
            repeatableGroups = setOf("/Cart/Lines"), declaredReps = mapOf("/Cart/Lines" to 1000), indexFields = setOf(ITEM))
        val interp = DmInterpreter(def)
        for ((name, rows) in KernelProbe.rowPatterns(valid = "5", malformed = "abc")) {
            val kDoc = RuntimeLaws.doc().group("/Cart", 1)
            val iDoc = Document(def.kinds, def.declaredReps, def.unsignedFields, def.rangeConfig, def.dateFormats, def.scales)
                .group("/Cart", 1)
            rows.forEachIndexed { idx, v ->
                val r = idx + 1
                kDoc.group("/Cart/Lines", 1, r).field(ITEM, "K$r", 1, r, 1)
                iDoc.field(ITEM, "K$r", 1, r, 1)
                if (v != null) { kDoc.field(QTY, v, 1, r, 1); iDoc.field(QTY, v, 1, r, 1) }
            }
            val kernel = RuntimeLaws.fires(kModel, code, kDoc)
            val interpFires = interp.validateFull(iDoc).any { it.code == code }
            assertEquals(kernel, interpFires, "[$condition] scenario '$name'")
        }
    }

    // Aggregates over a repeatable field (at /Cart, iterating once, folding Lines*).
    @Test fun sumBattery() = agreeAcrossBattery("/Cart", "SM", "../Lines/Qty", "Sum(Lines*/Qty) > 7")
    @Test fun maxValueBattery() = agreeAcrossBattery("/Cart", "MX", "../Lines/Qty", "MaxValue(Lines*/Qty) > 3")
    @Test fun minValueBattery() = agreeAcrossBattery("/Cart", "MN", "../Lines/Qty", "MinValue(Lines*/Qty) > 3")
    @Test fun numberOfDifferentValuesBattery() = agreeAcrossBattery("/Cart", "ND", "../Lines/Qty", "NumberOfDifferentValues(Lines*/Qty) > 1")
    @Test fun firstFilledValueBattery() = agreeAcrossBattery("/Cart", "FF", "../Lines/Qty", "FirstFilledValue(Lines*/Qty) > 3")

    // Iteration + per-row (at /Cart and /Cart/Lines).
    @Test fun currentRepetitionBattery() = agreeAcrossBattery("/Cart", "CR", "../Lines/Qty", "FieldFilled(Lines/Qty) And CurrentRepetition(Lines) > 1")
    @Test fun perRowComparisonBattery() = agreeAcrossBattery("/Cart/Lines", "HQ", "../Qty", "FieldFilled(Qty) And [Qty] > 3")

    private companion object {
        const val ITEM = "/Cart/Lines/ItemId"
        const val QTY = "/Cart/Lines/Qty"
    }
}
