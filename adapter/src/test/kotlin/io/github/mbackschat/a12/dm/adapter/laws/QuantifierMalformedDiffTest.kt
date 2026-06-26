package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
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
 * Differential — the field-list **quantifiers with a not-check-relevant (malformed) operand** (the sibling of
 * the `FieldFilled` 3VL fix). The kernel's `isWertAngegeben` is **3-valued**: a present-but-malformed field is
 * UNKNOWN, counted as NEITHER filled (TRUE) nor empty (FALSE). Over `(Quantity, BackorderQuantity)` with one
 * malformed: `AllFieldsFilled`/`MoreThanOneFieldFilled`/`NotExactlyOneFieldFilled`/`AtLeastOneFieldFilled`/
 * `FieldsNotCollectivelyFilled` need a real TRUE the malformed field doesn't supply; `NotAllFieldsFilled`/
 * `NoFieldFilled` key off a real FALSE (empty). A binary "present = filled" count (the bug) fires several of
 * these where the kernel does not. On the **non-repeatable** `/Order` (the negative quantifiers can't iterate a
 * repeatable group — `MVK_NEG_CONDITION_IN_ITERATION`); firing must agree.
 */
class QuantifierMalformedDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val fields = listOf(FieldDef(QTY, Kind.NUMBER, scale = 0), FieldDef(BACK, Kind.NUMBER, scale = 0))

    // The CONDITIONS are well-formed quantifiers, authored with the rulekit typed DSL ([m]) and rendered to the
    // canonical string fed to BOTH engines; the malformed VALUES live in the doc-building below (unchanged).
    private val m = BoundModel.ofJson(order)
    private fun render(c: Condition): String = DslRenderer().render(c)

    /** One `/Order` instance with Quantity/Backorder per arg (null = empty), run the quantifier rule, compare firing. */
    private fun agree(code: String, cond: String, qty: String?, back: String?, msg: String) {
        val kModel = RuntimeLaws.withRule(order, "/Order", code, "../Quantity", code, cond)
        val def = ModelDef(fields, listOf(RuleDef("/Order/$code", "/Order", cond, code, "../Quantity")))
        val kDoc = RuntimeLaws.doc().group("/Order", 1)
        val iDoc = Document(def.kinds, def.declaredReps, def.unsignedFields, def.rangeConfig, def.dateFormats, def.scales)
            .group("/Order", 1)   // instantiate the root explicitly so the all-empty case still has a row to evaluate
        if (qty != null) { kDoc.field(QTY, qty, 1, 1); iDoc.field(QTY, qty, 1, 1) }
        if (back != null) { kDoc.field(BACK, back, 1, 1); iDoc.field(BACK, back, 1, 1) }
        val kernel = RuntimeLaws.fires(kModel, code, kDoc)
        val interp = DmInterpreter(def).validateFull(iDoc).any { it.code == code }
        assertEquals(kernel, interp, "$msg  (kernel=$kernel interp=$interp)")
    }

    // A malformed operand is UNKNOWN — it does NOT count toward a TRUE-requiring quantifier.
    @Test fun allFieldsFilledNeedsRealTrues() =
        agree("AF", render(Dsl.allFieldsFilled(m.field(QTY), m.field(BACK))), "5", "abc", "Backorder malformed → not all TRUE → not fire")
    @Test fun atLeastOneNeedsARealTrue() =
        agree("A1", render(Dsl.atLeastOneFieldFilled(m.field(QTY), m.field(BACK))), "abc", null, "only operand malformed → t=0 → not fire")
    @Test fun moreThanOneNeedsTwoTrues() =
        agree("M1", render(Dsl.moreThanOneFieldFilled(m.field(QTY), m.field(BACK))), "5", "abc", "one TRUE + one malformed → t=1 → not fire")
    @Test fun notExactlyOneWithMalformed() =
        agree("X1", render(Dsl.notExactlyOneFieldFilled(m.field(QTY), m.field(BACK))), "5", "abc", "t=1, f=0 → neither all-empty nor ≥2 → not fire")
    @Test fun fieldsNotCollectivelyNeedsATrue() =
        agree("FC", render(Dsl.fieldsNotCollectivelyFilled(m.field(QTY), m.field(BACK))), "abc", null, "malformed + empty → no TRUE → not fire")

    // A malformed operand keys off a real FALSE (empty) for these.
    @Test fun notAllFiresOnARealEmpty() =
        agree("NA", render(Dsl.notAllFieldsFilled(m.field(QTY), m.field(BACK))), "abc", null, "Backorder empty (FALSE) → fires; malformed Quantity irrelevant")
    @Test fun noFieldFilledWithMalformed() =
        agree("NF", render(Dsl.noFieldFilled(m.field(QTY), m.field(BACK))), "abc", null, "Quantity malformed (not FALSE) → not all-empty → not fire")

    // All-valid / regression sanity (u = 0 reduces to the binary law).
    @Test fun allFieldsFilledFiresWhenBothValid() =
        agree("AF", render(Dsl.allFieldsFilled(m.field(QTY), m.field(BACK))), "5", "9", "both TRUE → fires")
    @Test fun notExactlyOneFiresWhenBothEmpty() =
        agree("X1", render(Dsl.notExactlyOneFieldFilled(m.field(QTY), m.field(BACK))), null, null, "both empty (f=n) → fires")

    private companion object {
        const val QTY = "/Order/Quantity"
        const val BACK = "/Order/BackorderQuantity"
    }
}
