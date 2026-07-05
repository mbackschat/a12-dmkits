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
 * Differential — arithmetic **division `/`** and **power `^`** (operators.json `Arithmetic`; KERNEL-SEMANTICS
 * "Division by zero and power-operator edge cases evaluate quietly"). Precedence `^` > `*`/`/` > `+`/`-`,
 * grouped with `{ }`. The exact-match subtleties: division carries `MathContext(50)` precision; **division by
 * zero is quiet** (the expression is non-evaluable → the rule does not fire, never an error); a power's
 * exponent is whole, and `0` to a negative power or an exponent outside `[-1000, 1000]` is likewise quiet.
 * Each case fires identically in both engines.
 */
class DivPowDiffTest {

    private val fuzz = RuntimeLaws.model("models/fuzz.dm.json")
    private val fields = listOf(
        FieldDef(ITEM, Kind.STRING), FieldDef(QTY, Kind.NUMBER, scale = 0), FieldDef(PRICE, Kind.NUMBER, scale = 2),
    )

    // Conditions are authored with the rulekit typed DSL ([m]) and rendered to the canonical string fed to BOTH
    // engines — no free-text condition literals. The renderer emits flat infix arithmetic (no `{ }`); the
    // explicit curly braces in the original text only fenced the whole arithmetic operand and didn't override
    // internal precedence, so the brace-free render parses back to the same expression tree (kernel `^` > `*`/`/`).
    private val m = BoundModel.ofJson(fuzz)
    private fun render(c: Condition): String = DslRenderer().render(c)
    private fun price() = m.num(PRICE).asNumber()
    private fun qty() = m.num(QTY).asNumber()

    private fun agree(cond: String, qty: String, price: String, msg: String) {
        val kModel = RuntimeLaws.withRule(fuzz, "/Cart/Lines", "RR", "../Qty", "RR", cond)
        val def = ModelDef(fields, listOf(RuleDef("/Cart/Lines/RR", "/Cart/Lines", cond, "RR", "../Qty")),
            repeatableGroups = setOf("/Cart/Lines"), declaredReps = mapOf("/Cart/Lines" to 1000), indexFields = setOf(ITEM))
        val kDoc = RuntimeLaws.doc().group("/Cart", 1)
            .group("/Cart/Lines", 1, 1).field(ITEM, "A", 1, 1, 1).field(QTY, qty, 1, 1, 1).field(PRICE, price, 1, 1, 1)
        val iDoc = Document(def.kinds, def.declaredReps, def.unsignedFields, def.rangeConfig, def.dateFormats, def.scales)
            .field(ITEM, "A", 1, 1, 1).field(QTY, qty, 1, 1, 1).field(PRICE, price, 1, 1, 1)
        val kernel = RuntimeLaws.fires(kModel, "RR", kDoc)
        val interp = DmInterpreter(def).validateFull(iDoc).any { it.code == "RR" }
        assertEquals(kernel, interp, "$msg  (kernel=$kernel interp=$interp)")
    }

    @Test fun divFires() = agree(render(price().dividedBy(qty()).gt(3)), "3", "10", "10/3 > 3")
    @Test fun divDoesNotFire() = agree(render(price().dividedBy(qty()).gt(4)), "3", "10", "10/3 not > 4")
    @Test fun divByZeroIsQuiet() =
        agree(render(m.field(QTY).filled().and(price().dividedBy(qty()).gt(0))), "0", "10", "division by zero → no fire")
    @Test fun divHighPrecision() =
        agree(render(price().dividedBy(qty()).gt("3.333333333333333333")), "3", "10", "10/3 vs eighteen 3s")
    @Test fun powFires() = agree(render(qty().toThePowerOf(Dsl.number("2")).gt(5)), "3", "1", "3^2 = 9 > 5")
    @Test fun powDoesNotFire() = agree(render(qty().toThePowerOf(Dsl.number("2")).gt(10)), "3", "1", "9 not > 10")
    @Test fun powZeroExponent() = agree(render(qty().toThePowerOf(Dsl.number("0")).gt(0)), "7", "1", "7^0 = 1 > 0")
    @Test fun powNegativeExponent() = agree(render(qty().toThePowerOf(Dsl.number("-1")).gt(0)), "4", "1", "4^-1 = 0.25 > 0")
    @Test fun powZeroToNegativeIsQuiet() =
        agree(render(m.field(QTY).filled().and(qty().toThePowerOf(Dsl.number("-1")).gt(0))), "0", "1", "0^-1 quiet → no fire")
    @Test fun powExponentOutOfRangeIsQuiet() =
        agree(render(m.field(QTY).filled().and(qty().toThePowerOf(Dsl.number("2000")).gt(0))), "2", "1", "|exp|>1000 quiet → no fire")
    @Test fun powBindsTighterThanMultiply() =
        agree(render(Dsl.number("2").times(qty().toThePowerOf(Dsl.number("2"))).gt(10)), "3", "1", "2 * 3^2 = 18 (^ first) > 10")

    private companion object {
        const val ITEM = "/Cart/Lines/ItemId"
        const val QTY = "/Cart/Lines/Qty"
        const val PRICE = "/Cart/Lines/Price"
    }
}
