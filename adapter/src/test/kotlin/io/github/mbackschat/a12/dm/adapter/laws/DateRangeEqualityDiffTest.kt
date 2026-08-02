package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Tri-engine runtime lock for the owner-admitted DateRange construction equality domain. */
class DateRangeEqualityDiffTest {

    private val base = RuntimeLaws.model("models/order.dm.json")
    private val model = BoundModel.ofJson(base)
    private val constructed = Dsl.dateRange(model.date(ORDER_DATE), model.date(DELIVERY_DATE))
    private val modelJson = listOf(
        rule(EQUAL, constructed.eq(model.dateRange(COVERAGE))),
        rule(NOT_EQUAL, model.dateRange(COVERAGE).ne(constructed)),
    ).fold(base, TypedRules::inject)
    private val java by lazy { RuntimeLaws.kernelJavaEngine(modelJson) }
    private val definition by lazy { ModelLoader.load(modelJson) }

    @Test
    fun constructionAndFieldEqualityAreExactComplementsOnEveryEngine() {
        agree("01.06.2024-30.06.2024", setOf(EQUAL))
        agree("01.06.2024-29.06.2024", setOf(NOT_EQUAL))
    }

    private fun agree(coverage: String, expected: Set<String>) {
        val kernelDocument = RuntimeLaws.doc().group(ROOT, 1)
            .field(ORDER_DATE, "2024-06-01", 1, 1)
            .field(DELIVERY_DATE, "2024-06-30", 1, 1)
            .field(COVERAGE, coverage, 1, 1)
        val dynamic = RuntimeLaws.messages(modelJson, kernelDocument).map { it.code() }.toSet()
        val generated = java.messages(java.instance(kernelDocument)).map { it.code() }.toSet()

        val document = definition.newDocument().group(ROOT, 1)
            .field(ORDER_DATE, "2024-06-01", 1, 1)
            .field(DELIVERY_DATE, "2024-06-30", 1, 1)
            .field(COVERAGE, coverage, 1, 1)
        val interpreted = DmInterpreter(definition).validateFull(document).map { it.code }.toSet()

        assertEquals(expected, dynamic, "normative dynamic Groovy ($coverage)")
        assertEquals(dynamic, generated, "generated Java ($coverage)")
        assertEquals(dynamic, interpreted, "interpreter ($coverage)")
    }

    private fun rule(code: String, condition: Condition): Rule = model.rule(model.group(ROOT))
        .name(code)
        .errorField(model.dateRange(COVERAGE).field())
        .`when`(condition)
        .code(code)
        .severity(Severity.ERROR)
        .message(Dsl.en("Date-range equality probe."))
        .build()

    private companion object {
        const val ROOT = "/Order"
        const val ORDER_DATE = "/Order/OrderDate"
        const val DELIVERY_DATE = "/Order/DeliveryDate"
        const val COVERAGE = "/Order/CoverageWindow"
        const val EQUAL = "DateRangeEqual"
        const val NOT_EQUAL = "DateRangeNotEqual"
    }
}
