package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Calculation
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential for the computation form of the `DateRange(start, end)` constructor. It assigns a DATE_RANGE
 * field, while `DateRangeEqualityDiffTest` locks its condition form. The kernel renders the value in the TARGET field's
 * format: `OrderDate`/`DeliveryDate` (`yyyy-MM-dd`) → `CoverageWindow` (`dd.MM.yyyy`-`-`) = `01.06.2024-30.06.2024`.
 * Compares the computed value to the kernel's `compute()`.
 */
class DateRangeComputeDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    // Operation authored with the rulekit typed DSL ([m]) — DateRange(start, end) — rendered (relative to
    // the computation group /Order) to the canonical string fed to BOTH engines; no free-text literal.
    private val m = BoundModel.ofJson(order)
    private fun renderOp(c: Calculation): String = DslRenderer(ConditionLanguage.EN, "/Order").render(c)
    private val fields = listOf(
        FieldDef(OD, Kind.DATE), FieldDef(DD, Kind.DATE),
        FieldDef(CW, Kind.DATE_RANGE, format = "dd.MM.yyyy", rangeSeparator = "-"),
    )
    private val operation = renderOp(Dsl.dateRange(m.date(OD), m.date(DD)))
    private val comp = ComputationDef(CW, "/Order", operation)
    private val def = ModelDef(fields, emptyList(), computations = listOf(comp))
    private val model = RuntimeLaws.withComputation(order, "/Order", "CWComp", "../CoverageWindow", operation)

    private fun agree(orderDate: String, deliveryDate: String, msg: String) {
        val kernel = RuntimeLaws.compute(model, RuntimeLaws.doc()
            .group("/Order", 1).field(OD, orderDate, 1, 1).field(DD, deliveryDate, 1, 1))
        val kernelValue = RuntimeLaws.computedValue(kernel, CW).orElse(null)

        val iDoc = def.newDocument().field(OD, orderDate, 1, 1).field(DD, deliveryDate, 1, 1)
        val got = DmInterpreter(def).compute(iDoc).single { it.path == CW }.value
        assertEquals(kernelValue, got, "$msg (kernel=$kernelValue interp=$got)")
    }

    @Test fun rendersRangeInTargetFormat() =
        agree("2024-06-01", "2024-06-30", "DateRange rendered to the CoverageWindow dd.MM.yyyy-separator format")

    @Test fun anotherSpan() =
        agree("2023-01-15", "2023-12-24", "a second span renders the same way")

    @Test
    fun dateRangeConstructorHasGeneratedJavaTriEvidence() {
        val kDoc = RuntimeLaws.doc().group("/Order", 1)
            .field(OD, "2024-06-01", 1, 1).field(DD, "2024-06-30", 1, 1)
        val groovyDynamic = RuntimeLaws.compute(model, kDoc)
            .single { it.path() == CW }.value().orElse(null)
        val engine = RuntimeLaws.kernelJavaEngine(model)
        val javaStatic = engine.compute(engine.instance(kDoc))
            .single { it.path() == CW }.value().orElse(null)
        val iDoc = def.newDocument().field(OD, "2024-06-01", 1, 1).field(DD, "2024-06-30", 1, 1)
        val interpreter = DmInterpreter(def).compute(iDoc).single { it.path == CW }.value

        assertEquals("01.06.2024-30.06.2024", groovyDynamic, "DateRange must render in the target field format")
        assertEquals(groovyDynamic, javaStatic, "DateRange: groovy-dynamic vs generated Java")
        assertEquals(groovyDynamic, interpreter, "DateRange: kernel vs interpreter")
    }

    private companion object {
        const val OD = "/Order/OrderDate"
        const val DD = "/Order/DeliveryDate"
        const val CW = "/Order/CoverageWindow"
    }
}
