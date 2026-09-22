package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.interpreter.model.Verdict
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential for `DifferenceInDays` — cli-runtime.md's actual `DeliveryNotBeforeOrder` rule:
 * `AllFieldsFilled(OrderDate, DeliveryDate) And DifferenceInDays(OrderDate, DeliveryDate) < 0`. The
 * difference is whole days, sign = second − first, so delivery BEFORE order is negative → fires. Verified
 * against the REAL kernel for the demo's exact violation/compliant instances.
 */
class DifferenceInDaysDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")

    // Conditions are authored with the rulekit typed DSL ([m]) and rendered to the canonical string fed to BOTH
    // engines — no free-text condition literals. The renderer emits absolute paths; both engines accept them.
    private val m = BoundModel.ofJson(order)
    private fun render(c: Condition): String = RuntimeLaws.render(c)
    private val condition = render(
        Dsl.allFieldsFilled(m.date(OD).field(), m.date(DD).field())
            .and(Dsl.differenceInDays(m.date(OD), m.date(DD)).lt(0))
    )
    private val rule = RuleDef("/Order/DeliveryNotBeforeOrder", "/Order", condition, "DELIVERY_BEFORE_ORDER", "../DeliveryDate")
    private val model = RuntimeLaws.withRule(order, "/Order", "DeliveryNotBeforeOrder", "../DeliveryDate", "DELIVERY_BEFORE_ORDER", condition)
    private val def = ModelDef(listOf(FieldDef(OD, Kind.DATE), FieldDef(DD, Kind.DATE)), listOf(rule))

    private fun verdictFor(orderDate: String, deliveryDate: String): Boolean {
        val kernel = RuntimeLaws.fires(model, "DELIVERY_BEFORE_ORDER",
            RuntimeLaws.doc().group("/Order", 1).field(OD, orderDate, 1, 1).field(DD, deliveryDate, 1, 1))
        val interp = DmInterpreter(def).testRule("/Order/DeliveryNotBeforeOrder",
            Document(def.kinds).field(OD, orderDate, 1, 1).field(DD, deliveryDate, 1, 1)).verdict == Verdict.FIRED
        assertEquals(kernel, interp, "DifferenceInDays rule on order=$orderDate delivery=$deliveryDate [k=$kernel i=$interp]")
        return interp
    }

    @Test
    fun deliveryBeforeOrderFiresLikeKernel() {
        assertEquals(true, verdictFor("2024-06-01", "2024-05-15"), "delivery before order → fires")
    }

    @Test
    fun deliveryAfterOrderDoesNotFireLikeKernel() {
        assertEquals(false, verdictFor("2024-06-01", "2024-06-15"), "delivery after order → no fire")
    }

    @Test
    fun sameDayDoesNotFireLikeKernel() {
        assertEquals(false, verdictFor("2024-06-01", "2024-06-01"), "same day → difference 0, not < 0")
    }

    private companion object {
        const val OD = "/Order/OrderDate"
        const val DD = "/Order/DeliveryDate"
    }
}
