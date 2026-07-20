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
 * Differential — the rounding functions: the operand-list `RoundDown`/`RoundUp`/`RoundAccounting([expr], n)`
 * and their bare-field `*Value(field[, n])` siblings (the cases here omit `n`, so they round to an integer). The headline exact-match risk is the
 * SIGN of the direction: the kernel's `RoundDown` is **FLOOR** (toward −∞, NOT toward zero — `RoundDown(-1.4, 0)
 * = -2`), `RoundUp` is **CEILING** (toward +∞ — `RoundUp(-1.4, 0) = -1`), and `RoundAccounting` is **HALF_UP**
 * away-from-zero on the `.5` boundary (`RoundAccounting(-2.5, 0) = -3`, NOT half-even's -2). Each case is
 * guarded with `FieldFilled(Price)` (an unguarded round would fire on the empty-as-0 row, which the kernel
 * rejects); ordering comparisons keep the scale check out of play (`==`-scale-match is scale-exempt for `<`/`>`).
 */
class RoundDiffTest {

    private val fuzz = RuntimeLaws.model("models/fuzz.dm.json")
    private val fields = listOf(
        FieldDef(ITEM, Kind.STRING), FieldDef(QTY, Kind.NUMBER, scale = 0), FieldDef(PRICE, Kind.NUMBER, scale = 2),
    )

    // Conditions are authored with the rulekit typed DSL ([m]) and rendered to the canonical string fed to BOTH
    // engines. `Dsl.roundDown(m.num(p).asNumber(), n)` / `Dsl.roundDownValue(m.num(p))` (and Up/Accounting/abs).
    private val m = BoundModel.ofJson(fuzz)
    private fun render(c: Condition): String = DslRenderer().render(c)

    private fun agree(cond: String, item: String, qty: String, price: String, msg: String) {
        val errorRel = "../Price"
        val kModel = RuntimeLaws.withRule(fuzz, "/Cart/Lines", "RR", errorRel, "RR", cond)
        val def = ModelDef(fields, listOf(RuleDef("/Cart/Lines/RR", "/Cart/Lines", cond, "RR", errorRel)),
            repeatableGroups = setOf("/Cart/Lines"), declaredReps = mapOf("/Cart/Lines" to 1000), indexFields = setOf(ITEM))
        val kDoc = RuntimeLaws.doc().group("/Cart", 1)
            .group("/Cart/Lines", 1, 1).field(ITEM, item, 1, 1, 1).field(QTY, qty, 1, 1, 1).field(PRICE, price, 1, 1, 1)
        val iDoc = def.newDocument()
            .field(ITEM, item, 1, 1, 1).field(QTY, qty, 1, 1, 1).field(PRICE, price, 1, 1, 1)
        val kernel = RuntimeLaws.fires(kModel, "RR", kDoc)
        val interp = DmInterpreter(def).validateFull(iDoc).any { it.code == "RR" }
        assertEquals(kernel, interp, "$msg  (kernel=$kernel interp=$interp)")
    }

    // RoundDown = FLOOR (toward −∞). Negative is the catcher: toward-zero would give -1 (not < -1).
    @Test fun roundDownNegativeIsFloor() =
        agree(render(m.num(PRICE).filled().and(Dsl.roundDown(m.num(PRICE).asNumber(), 0).lt(-1))), "A", "1", "-1.40", "FLOOR(-1.40) = -2 < -1")
    @Test fun roundDownPositive() =
        agree(render(m.num(PRICE).filled().and(Dsl.roundDown(m.num(PRICE).asNumber(), 0).lt(2))), "A", "1", "1.80", "FLOOR(1.80) = 1 < 2")
    @Test fun roundDownToOnePlace() =
        agree(render(m.num(PRICE).filled().and(Dsl.roundDown(m.num(PRICE).asNumber(), 1).lt("-1.4"))), "A", "1", "-1.45", "FLOOR(-1.45, 1) = -1.5 < -1.4")

    // RoundUp = CEILING (toward +∞). Negative is the catcher: away-from-zero would give -2 (not > -2).
    @Test fun roundUpNegativeIsCeiling() =
        agree(render(m.num(PRICE).filled().and(Dsl.roundUp(m.num(PRICE).asNumber(), 0).gt(-2))), "A", "1", "-1.40", "CEILING(-1.40) = -1 > -2")
    @Test fun roundUpPositive() =
        agree(render(m.num(PRICE).filled().and(Dsl.roundUp(m.num(PRICE).asNumber(), 0).gt(1))), "A", "1", "1.20", "CEILING(1.20) = 2 > 1")

    // RoundAccounting = HALF_UP away-from-zero. The .5 boundary catches half-even.
    @Test fun roundAccountingNegativeHalf() =
        agree(render(m.num(PRICE).filled().and(Dsl.roundAccounting(m.num(PRICE).asNumber(), 0).lt(-2))), "A", "1", "-2.50", "HALF_UP(-2.50) = -3 < -2")
    @Test fun roundAccountingPositiveHalf() =
        agree(render(m.num(PRICE).filled().and(Dsl.roundAccounting(m.num(PRICE).asNumber(), 0).gt(2))), "A", "1", "2.50", "HALF_UP(2.50) = 3 > 2")
    @Test fun roundAccountingExpression() =
        agree(render(m.num(PRICE).filled().and(Dsl.roundAccounting(m.num(PRICE).asNumber().times(m.num(QTY).asNumber()), 1).gt("4.6"))), "A", "3", "1.55", "HALF_UP(4.65, 1) = 4.7 > 4.6")

    // One-argument *Value forms — round a bare field to an integer (0 places), same direction as their siblings.
    @Test fun roundDownValueNegative() =
        agree(render(m.num(PRICE).filled().and(Dsl.roundDownValue(m.num(PRICE)).lt(-1))), "A", "1", "-1.40", "RoundDownValue(-1.40) = -2 < -1")
    @Test fun roundUpValueNegative() =
        agree(render(m.num(PRICE).filled().and(Dsl.roundUpValue(m.num(PRICE)).gt(-2))), "A", "1", "-1.40", "RoundUpValue(-1.40) = -1 > -2")
    @Test fun roundAccountingValueNegative() =
        agree(render(m.num(PRICE).filled().and(Dsl.roundAccountingValue(m.num(PRICE)).lt(-2))), "A", "1", "-2.50", "RoundAccountingValue(-2.50) = -3 < -2")

    // A clean non-firing case (the rounded value does not satisfy the comparison).
    @Test fun roundDownDoesNotFire() =
        agree(render(m.num(PRICE).filled().and(Dsl.roundDown(m.num(PRICE).asNumber(), 0).gt(5))), "A", "1", "4.90", "FLOOR(4.90) = 4 not > 5")

    private companion object {
        const val ITEM = "/Cart/Lines/ItemId"
        const val QTY = "/Cart/Lines/Qty"
        const val PRICE = "/Cart/Lines/Price"
    }
}
