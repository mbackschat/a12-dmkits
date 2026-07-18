package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — the value coercions **`FieldValueAsString(numberField)`** and
 * **`FieldValueAsNumber(string/numeric-enum field)`**, over `order.dm.json` (`/Order` is non-repeatable;
 * `/Order/Quantity` is a scale-0 number, `/Order/RushLevel` a numeric-valued enum `"1"/"2"/"3"`).
 *
 * Exact-match catches (kernel `feldWertAlsString` = the RAW stored value; `feldWertAlsZahl`):
 * - `FieldValueAsString` returns the field's **raw stored value** as a string (no re-canonicalization); an empty
 *   field → `""` → an empty-operand comparison does not fire;
 * - `FieldValueAsNumber` parses the value to a number, and an **empty** field reads as **`0`** — so on a numeric
 *   enum the coercion equals its code's number. Firing must agree.
 */
class FieldValueCoercionDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val fields = listOf(FieldDef(QTY, Kind.NUMBER, scale = 0), FieldDef(RUSH, Kind.STRING))

    // Conditions are authored with the rulekit typed DSL ([m]) and rendered to the canonical string fed to BOTH
    // engines — no free-text condition literals. `Dsl.fieldValueAsString`/`fieldValueAsNumber` are the coercions.
    private val m = BoundModel.ofJson(order)
    private fun render(c: Condition): String = DslRenderer().render(c)
    private val asStringEq5 =
        render(m.field(QTY).filled().and(Dsl.fieldValueAsString(m.field(QTY)).eq("5")))
    private val asNumberGe2 =
        render(m.field(RUSH).filled().and(Dsl.fieldValueAsNumber(m.field(RUSH)).ge(2)))

    private fun agree(cond: String, errorRel: String, qty: String?, rush: String?, msg: String) {
        val kModel = RuntimeLaws.withRule(order, "/Order", "FC", errorRel, "FC", cond)
        val def = ModelDef(fields, listOf(RuleDef("/Order/FC", "/Order", cond, "FC", errorRel)))
        val kDoc = RuntimeLaws.doc().group("/Order", 1)
        val iDoc = def.newDocument()
        if (qty != null) { kDoc.field(QTY, qty, 1, 1); iDoc.field(QTY, qty, 1, 1) }
        if (rush != null) { kDoc.field(RUSH, rush, 1, 1); iDoc.field(RUSH, rush, 1, 1) }
        val kernel = RuntimeLaws.fires(kModel, "FC", kDoc)
        val interp = DmInterpreter(def).validateFull(iDoc).any { it.code == "FC" }
        assertEquals(kernel, interp, "$msg  (kernel=$kernel interp=$interp)")
    }

    // FieldValueAsString — the raw stored value as a string.
    @Test fun asStringMatchesRawValue() =
        agree(asStringEq5, "../Quantity", "5", null, "raw \"5\" == \"5\"")
    @Test fun asStringMismatchDoesNotFire() =
        agree(asStringEq5, "../Quantity", "7", null, "raw \"7\" != \"5\"")

    // FieldValueAsNumber — the numeric enum's code as a number.
    @Test fun asNumberOfEnumFires() =
        agree(asNumberGe2, "../RushLevel", null, "2", "enum \"2\" → 2 >= 2")
    @Test fun asNumberOfEnumDoesNotFire() =
        agree(asNumberGe2, "../RushLevel", null, "1", "enum \"1\" → 1, not >= 2")

    private companion object {
        const val QTY = "/Order/Quantity"
        const val RUSH = "/Order/RushLevel"
    }
}
