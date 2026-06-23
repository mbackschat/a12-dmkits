package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.Document
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.FieldDef
import io.github.mbackschat.a12.dm.interpreter.Kind
import io.github.mbackschat.a12.dm.interpreter.ModelDef
import io.github.mbackschat.a12.dm.interpreter.RuleDef
import io.github.mbackschat.a12.dm.interpreter.Verdict
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
    private val condition = "AllFieldsFilled(OrderDate, DeliveryDate) And DifferenceInDays(OrderDate, DeliveryDate) < 0"
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
