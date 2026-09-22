package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — the empty-row gate. On a TRULY EMPTY row (a `/Order` instance with no filled field) the
 * kernel still evaluates a rule whose condition *can fire empty* — the **negative presence** conditions
 * (`NoFieldFilled`, `NotAllFieldsFilled`, `NotExactlyOneFieldFilled`, `FieldNotFilled`) fire; the positive
 * ones and a bare comparison do NOT (the comparison's empty-as-0 must stay suppressed). The interpreter must
 * agree. (The other quantifier tests pad a sibling so the row is non-empty; this one exercises the empty
 * row itself.) Driven from parsed text.
 */
class EmptyRowGateDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val fields = listOf(FieldDef(A, Kind.STRING), FieldDef(B, Kind.STRING), FieldDef(Q, Kind.NUMBER, scale = 0))

    // Conditions are authored with the rulekit typed DSL ([m]) and rendered to the canonical string fed to BOTH
    // engines — no free-text condition literals.
    private val m = BoundModel.ofJson(order)
    private fun render(c: Condition): String = RuntimeLaws.render(c)
    private fun noneFilled() = Dsl.noFieldFilled(m.field(A), m.field(B))
    private fun notAllFilled() = Dsl.notAllFieldsFilled(m.field(A), m.field(B))
    private fun qtyUnder100() = m.num(Q).lt(100)

    /** Evaluate over an EMPTY /Order row (group instantiated, no field values) — interp must match the kernel. */
    private fun agree(condition: Condition, errField: String, msg: String) {
        val cond = render(condition)
        val k = RuntimeLaws.fires(RuntimeLaws.withRule(order, "/Order", "R", errField, "EG", cond), "EG",
            RuntimeLaws.doc().group("/Order", 1))
        val i = DmInterpreter(ModelDef(fields, listOf(RuleDef("/Order/R", "/Order", cond, "EG", errField))))
            .validate(Document(kinds).group("/Order", 1)).messages.any { it.code == "EG" }
        assertEquals(k, i, "$msg")
    }

    @Test fun negativeConditionsFireOnEmptyRow() {
        agree(noneFilled(), "../CustomerName", "NoFieldFilled fires on empty")
        agree(notAllFilled(), "../CustomerName", "NotAllFieldsFilled fires on empty")
        agree(Dsl.notExactlyOneFieldFilled(m.field(A), m.field(B)), "../CustomerName", "NotExactlyOneFieldFilled fires on empty")
        agree(Dsl.fieldNotFilled(m.field(A)), "../CustomerName", "FieldNotFilled fires on empty")
    }

    @Test fun positiveConditionsDoNotFireOnEmptyRow() {
        agree(Dsl.fieldFilled(m.field(A)), "../CustomerName", "FieldFilled does not fire on empty")
        agree(Dsl.allFieldsFilled(m.field(A), m.field(B)), "../CustomerName", "AllFieldsFilled does not fire on empty")
        agree(Dsl.atLeastOneFieldFilled(m.field(A), m.field(B)), "../CustomerName", "AtLeastOneFieldFilled does not fire on empty")
        agree(Dsl.fieldsNotCollectivelyFilled(m.field(A), m.field(B)), "../CustomerName", "FieldsNotCollectivelyFilled does not fire on empty (needs >=1 filled)")
    }

    @Test fun comparisonEmptyAsZeroStaysSuppressedOnEmptyRow() {
        // [Quantity] empty → 0; 0 < 100 would be TRUE, but the kernel does NOT evaluate a bare comparison on an empty row.
        agree(qtyUnder100(), "../Quantity", "a comparison does not fire on an empty row")
    }

    @Test fun compositionsOnEmptyRow() {
        // Or with a negative branch → evaluated → fires via the negative branch (even though the comparison is empty-as-0 true).
        agree(noneFilled().or(qtyUnder100()), "../CustomerName", "Or via negative branch fires")
        // Or of only non-empty-firing branches → not evaluated → no fire.
        agree(Dsl.fieldFilled(m.field(A)).or(qtyUnder100()), "../CustomerName", "Or of non-firing branches does not fire")
        // And of two negatives that both fire on empty → fires.
        agree(noneFilled().and(notAllFilled()), "../CustomerName", "And of negatives fires")
    }

    private val kinds = mapOf(A to Kind.STRING, B to Kind.STRING, Q to Kind.NUMBER)

    private companion object {
        const val A = "/Order/CustomerName"
        const val B = "/Order/ProductCode"
        const val Q = "/Order/Quantity"
    }
}
