package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.ComputationAlternative
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — **every computation is also a validation rule** (IG14): the kernel auto-generates an implicit
 * `FieldFilled(F) And [F] != <term>` validation per computation, firing under the computation's NAME when a
 * stored computed value disagrees with its term. `validateWith` previously iterated only `model.rules`, so the
 * interpreter never fired it. The kernel model is authored through the typed `Computation` builder (dogfood);
 * both engines must agree on whether `CoverageWindowComp` fires for an agreeing vs disagreeing stored value.
 */
class ComputationValidationDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val m = BoundModel.ofJson(order)
    private val comp = m.computation(m.field(COVERAGE)).name(COMP)
        .calc(Dsl.dateRange(m.date(ORDER_DATE), m.date(DELIVERY_DATE)))
        .message(Dsl.en("Coverage window.")).build()
    private val kModel = m.add(comp).toJson()

    private val def = ModelDef(
        listOf(
            FieldDef(ORDER_DATE, Kind.DATE, format = "yyyy-MM-dd"),
            FieldDef(DELIVERY_DATE, Kind.DATE, format = "yyyy-MM-dd"),
            FieldDef(COVERAGE, Kind.DATE_RANGE, format = "dd.MM.yyyy", rangeSeparator = "-"),
        ),
        emptyList(),
        computations = listOf(ComputationDef(COVERAGE, "/Order",
            listOf(ComputationAlternative(null, "DateRange(OrderDate, DeliveryDate)")), null, COMP)),
    )

    private fun kDoc(coverage: String) = RuntimeLaws.doc().group("/Order", 1)
        .field(ORDER_DATE, "2024-01-01", 1, 1).field(DELIVERY_DATE, "2024-02-01", 1, 1).field(COVERAGE, coverage, 1, 1)
    private fun iDoc(coverage: String) = def.newDocument().group("/Order", 1)
        .field(ORDER_DATE, "2024-01-01", 1, 1).field(DELIVERY_DATE, "2024-02-01", 1, 1).field(COVERAGE, coverage, 1, 1)

    private fun agree(coverage: String, msg: String) {
        val kFires = RuntimeLaws.firedCodes(kModel, kDoc(coverage)).contains(COMP)
        val iFires = DmInterpreter(def).validate(iDoc(coverage)).messages.any { it.code == COMP }
        assertEquals(kFires, iFires, "$msg  (kernel=$kFires interp=$iFires)")
    }

    /** A stored value disagreeing with the computed term (OrderDate→DeliveryDate = 01.01.2024-01.02.2024) fires. */
    @Test fun disagreeingStoredValueFires() = agree("01.01.2020-01.01.2021", "disagreeing stored value → implicit validation fires")

    /** A stored value that equals the computed term does not fire. */
    @Test fun agreeingStoredValueDoesNotFire() = agree("01.01.2024-01.02.2024", "agreeing stored value → no fire")

    private companion object {
        const val COVERAGE = "/Order/CoverageWindow"
        const val ORDER_DATE = "/Order/OrderDate"
        const val DELIVERY_DATE = "/Order/DeliveryDate"
        const val COMP = "CoverageWindowComp"
    }
}
