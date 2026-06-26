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
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — the **directional, sign-aware polarity of a starred `Sum`** with an omitted tail (the
 * aggregate analogue of `DirectionalPolarityDiffTest`). The kernel's `NumberCombiner.SummeCombiner` gives a
 * Sum-with-omitted-tail `kannGroesserWerden = true` but `kannKleinerWerden = hatVorzeichen` — so for `>= 0`
 * an **unsigned** Sum (`positivesOnly`) can't be broken by filling (it only grows) → **VALUE**, while a
 * **signed** Sum can shrink → **OMISSION**. `Items` declares 50; instantiating 3 rows leaves a fillable tail.
 * `Count` is signed; `StockQty` is `positivesOnly`. Driven from parsed text over the general `Document`.
 */
class AggDirectionalPolarityDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val fields = listOf(
        FieldDef(CN, Kind.STRING), FieldDef(IS, Kind.STRING),
        FieldDef(IC, Kind.NUMBER), FieldDef(SQ, Kind.NUMBER, signed = false),
    )
    private val kinds = fields.associate { it.path to it.kind }

    // Conditions are authored with the rulekit typed DSL ([m]) and rendered to the canonical string fed to
    // BOTH engines — no free-text condition literals (renderer emits absolute paths; both engines accept them).
    private val m = BoundModel.ofJson(order)
    private fun render(c: Condition): String = DslRenderer().render(c)

    /** `Sum(Items* / <field>) >= 0` over 3 filled Items rows (of 50 declared, leaving a fillable tail). */
    private fun agree(starField: String, msg: String) {
        val code = "SUMNN"
        val cond = render(
            Dsl.fieldFilled(m.field(CN))
                .and(Dsl.sum(m.flatten("/Order/Items*/${starField.substringAfterLast('/')}")).ge(0)),
        )
        val model = RuntimeLaws.withRule(order, "/Order", code, "../CustomerName", code, cond)
        val kDoc = RuntimeLaws.doc().group("/Order", 1).field(CN, "Acme", 1, 1)
        val iDoc = Document(kinds, mapOf("/Order/Items" to 50), setOf(SQ)).field(CN, "Acme", 1, 1)
        for (r in 1..3) {
            kDoc.group("/Order/Items", 1, r).field(IS, "S$r", 1, r, 1).field(starField, "5", 1, r, 1)
            iDoc.field(IS, "S$r", 1, r, 1).field(starField, "5", 1, r, 1)
        }
        val kernel = RuntimeLaws.typeOf(model, code, kDoc).map { it.name }.orElse(null)
        val model2 = ModelDef(fields, listOf(RuleDef("/Order/$code", "/Order", cond, code, "../CustomerName")),
            repeatableGroups = setOf("/Order/Items"), declaredReps = mapOf("/Order/Items" to 50))
        val interp = DmInterpreter(model2).validate(iDoc).messages.firstOrNull { it.code == code }?.type
            ?.let { if (it == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR" }
        assertEquals(kernel, interp, "$msg  [kernel=$kernel interp=$interp]")
    }

    @Test fun unsignedSumGreaterEqualZeroWithTailIsValue() {
        // Sum of an unsigned field can only grow → `>= 0` can't be broken by filling the tail → VALUE.
        agree(SQ, "unsigned Sum(StockQty) >= 0 with fillable tail is VALUE")
    }

    @Test fun signedSumGreaterEqualZeroWithTailIsOmission() {
        // Sum of a signed field can shrink (a negative in the tail) → `>= 0` is fillable-to-fix → OMISSION.
        agree(IC, "signed Sum(Count) >= 0 with fillable tail is OMISSION")
    }

    private companion object {
        const val CN = "/Order/CustomerName"
        const val IS = "/Order/Items/Sku"
        const val IC = "/Order/Items/Count"
        const val SQ = "/Order/Items/StockQty"
    }
}
