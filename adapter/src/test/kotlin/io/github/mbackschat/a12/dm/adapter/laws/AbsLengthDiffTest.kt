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
 * Differential — the numeric functions **`Abs`** (`Abs([expr])`, bracketed), **`AbsValue`** (`AbsValue(field)`,
 * the bare-field sibling) = |x|, and **`Length`** (`Length(field)`, the character count of a string field's
 * value; bare field). The interpreter treats `Abs`/`AbsValue` identically (both → |operand value|). Each case
 * fires identically in both engines. (Index field `ItemId` is always filled so no `mandatoryField` intrudes;
 * `Length` rules are guarded with `FieldFilled` — the kernel rejects a rule that could fire on an empty row.)
 */
class AbsLengthDiffTest {

    private val fuzz = RuntimeLaws.model("models/fuzz.dm.json")
    private val fields = listOf(
        FieldDef(ITEM, Kind.STRING), FieldDef(QTY, Kind.NUMBER, scale = 0), FieldDef(PRICE, Kind.NUMBER, scale = 2),
    )

    // Conditions are authored with the rulekit typed DSL ([m]) and rendered to the canonical string fed to BOTH
    // engines (kernel injection + the interpreter) — no free-text condition literals.
    private val m = BoundModel.ofJson(fuzz)
    private fun render(c: Condition): String = DslRenderer().render(c)

    private fun agree(cond: String, errorRel: String, item: String, qty: String, price: String, msg: String) {
        val kModel = RuntimeLaws.withRule(fuzz, "/Cart/Lines", "RR", errorRel, "RR", cond)
        val def = ModelDef(fields, listOf(RuleDef("/Cart/Lines/RR", "/Cart/Lines", cond, "RR", errorRel)),
            repeatableGroups = setOf("/Cart/Lines"), declaredReps = mapOf("/Cart/Lines" to 1000), indexFields = setOf(ITEM))
        val kDoc = RuntimeLaws.doc().group("/Cart", 1)
            .group("/Cart/Lines", 1, 1).field(ITEM, item, 1, 1, 1).field(QTY, qty, 1, 1, 1).field(PRICE, price, 1, 1, 1)
        val iDoc = Document(def.kinds, def.declaredReps, def.unsignedFields, def.rangeConfig, def.dateFormats, def.scales)
            .field(ITEM, item, 1, 1, 1).field(QTY, qty, 1, 1, 1).field(PRICE, price, 1, 1, 1)
        val kernel = RuntimeLaws.fires(kModel, "RR", kDoc)
        val interp = DmInterpreter(def).validateFull(iDoc).any { it.code == "RR" }
        assertEquals(kernel, interp, "$msg  (kernel=$kernel interp=$interp)")
    }

    @Test fun absNegativeFires() = agree(render(Dsl.abs(m.num(PRICE).asNumber()).gt(4)), "../Price", "A", "1", "-5", "|−5| = 5 > 4")
    @Test fun absPositiveDoesNotFire() = agree(render(Dsl.abs(m.num(PRICE).asNumber()).gt(4)), "../Price", "A", "1", "3", "|3| = 3 not > 4")
    @Test fun absValueBareFieldFires() = agree(render(Dsl.absValue(m.num(PRICE)).gt(4)), "../Price", "A", "1", "-5", "AbsValue(-5) = 5 > 4")
    @Test fun absOfExpression() = agree(render(Dsl.abs(m.num(PRICE).asNumber().minus(m.num(QTY).asNumber())).gt(5)), "../Price", "A", "9", "2", "|2 − 9| = 7 > 5")
    @Test fun lengthEquals() = agree(render(m.field(ITEM).filled().and(m.str(ITEM).length().eq(4))), "../ItemId", "ABCD", "1", "1", "len(ABCD) = 4")
    @Test fun lengthDoesNotFire() = agree(render(m.field(ITEM).filled().and(m.str(ITEM).length().gt(4))), "../ItemId", "ABCD", "1", "1", "len(ABCD) = 4 not > 4")

    private companion object {
        const val ITEM = "/Cart/Lines/ItemId"
        const val QTY = "/Cart/Lines/Qty"
        const val PRICE = "/Cart/Lines/Price"
    }
}
