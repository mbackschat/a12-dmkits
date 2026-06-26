package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import io.github.mbackschat.a12.dm.rulekit.dsl.Tolerance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * `a DiffersWithToleranceRangeN b` against the kernel. Probed semantics: fires iff `|a − b| > N` (a STRICT
 * band — a difference exactly equal to the band does NOT fire), an empty numeric operand reads as 0, a
 * malformed operand suppresses. Polarity is EQ-like (kernel-probed): both operands present → **VALUE**; an
 * empty (fillable) operand whose filling could close the gap → **OMISSION**. The single [agree] helper compares
 * the fired message **TYPE** (null = not fired), so each case locks BOTH the firing and the polarity at once.
 * On /Order `Quantity`/`BackorderQuantity` (signed, scale 0).
 */
class ToleranceDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val fields = listOf(FieldDef(QTY, Kind.NUMBER, scale = 0), FieldDef(BACK, Kind.NUMBER, scale = 0))

    // Conditions are authored with the rulekit typed DSL ([m]) and rendered to the canonical string fed to BOTH
    // engines. `m.num(a).differsWithTolerance(Tolerance.RANGE_N, m.num(b))` is `[a] DiffersWithToleranceRangeN [b]`.
    private val m = BoundModel.ofJson(order)
    private fun render(c: Condition): String = DslRenderer().render(c)

    /** Inject [cond] at /Order; set Quantity=[q]/BackorderQuantity=[b] (null = empty); assert the fired type agrees. */
    private fun agree(cond: String, q: String?, b: String?, msg: String) {
        val model = RuntimeLaws.withRule(order, "/Order", "DT", "../Quantity", "DT", cond)
        val def = ModelDef(fields, listOf(RuleDef("/Order/DT", "/Order", cond, "DT", "../Quantity")))
        val kDoc = RuntimeLaws.doc().group("/Order", 1)
        val iDoc = Document(def.kinds, def.declaredReps, def.unsignedFields, def.rangeConfig, def.dateFormats, def.scales)
            .group("/Order", 1)
        if (q != null) { kDoc.field(QTY, q, 1, 1); iDoc.field(QTY, q, 1, 1) }
        if (b != null) { kDoc.field(BACK, b, 1, 1); iDoc.field(BACK, b, 1, 1) }
        val kernel = RuntimeLaws.typeOf(model, "DT", kDoc).map { it.name }.orElse(null)
        val interp = DmInterpreter(def).validate(iDoc).messages.firstOrNull { it.code == "DT" }?.type
            ?.let { if (it == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR" }
        assertEquals(kernel, interp, "$msg  [kernel=$kernel interp=$interp]")
    }

    private val t1 = render(m.num(QTY).differsWithTolerance(Tolerance.RANGE_1, m.num(BACK)))
    private val t5 = render(m.num(QTY).differsWithTolerance(Tolerance.RANGE_5, m.num(BACK)))

    @Test fun diffAboveBandFiresValue() = agree(t1, "5", "9", msg = "|5−9|=4 > 1, both present → VALUE")
    @Test fun diffAtBandDoesNotFire() = agree(t1, "5", "6", msg = "|5−6|=1, not > 1 → no fire")
    @Test fun diffJustAboveBandFiresValue() = agree(t1, "5", "7", msg = "|5−7|=2 > 1 → VALUE")
    @Test fun equalDoesNotFire() = agree(t1, "5", "5", msg = "|0| not > 1 → no fire")
    @Test fun emptyOperandFiresOmission() = agree(t1, "9", null, msg = "Backorder empty→0, |9−0|=9 > 1, fillable → OMISSION")
    @Test fun emptyOperandWithinBandDoesNotFire() = agree(t1, "1", null, msg = "|1−0|=1 not > 1 → no fire")
    @Test fun bothEmptyDoesNotFire() = agree(t1, null, null, msg = "|0−0|=0 → no fire")
    @Test fun malformedOperandSuppresses() = agree(t1, "abc", "9", msg = "Quantity malformed → DT suppressed")
    @Test fun band5BoundaryDoesNotFire() = agree(t5, "10", "5", msg = "|10−5|=5 not > 5 → no fire")
    @Test fun band5AboveFiresValue() = agree(t5, "11", "5", msg = "|11−5|=6 > 5 → VALUE")

    private companion object {
        const val QTY = "/Order/Quantity"
        const val BACK = "/Order/BackorderQuantity"
    }
}
