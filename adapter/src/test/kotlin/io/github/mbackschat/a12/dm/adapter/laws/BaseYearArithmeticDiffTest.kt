package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Tri-engine lock for numeric `BaseYear` in direct and arithmetic Number computations. */
class BaseYearArithmeticDiffTest {

    @Test
    fun directAndArithmeticNumberComputationsAgree() {
        val base = DocumentModelDraft.named("forecast").locale("en_US").baseYear(2020)
            .rootGroup("Forecast") { group ->
                group.numberField("Input") { it.fractionDigits(0) }
                    .numberField("Direct") { it.fractionDigits(0) }
                    .numberField("PlusOne") { it.fractionDigits(0) }
                    .numberField("PlusField") { it.fractionDigits(0) }
            }
            .build().toJson()
        val model = BoundModel.ofJson(base)
        val computations = listOf(
            model.computation(model.num(DIRECT)).name("DirectBaseYear")
                .calc(Dsl.baseYearNumber().expression()).message(Dsl.en("Direct base year.")).build(),
            model.computation(model.num(PLUS_ONE)).name("IncrementBaseYear")
                .calc(Dsl.baseYearNumber().plus(1).expression()).message(Dsl.en("Incremented base year.")).build(),
            model.computation(model.num(PLUS_FIELD)).name("AddBaseYear")
                .calc(model.num(INPUT).asNumber().plus(Dsl.baseYearNumber()).expression())
                .message(Dsl.en("Input plus base year.")).build(),
        )
        val modelJson = computations.fold(base, TypedRules::inject)
        val kernelDocument = RuntimeLaws.doc().group(ROOT, 1).field(INPUT, "5", 1, 1)

        val dynamic = RuntimeLaws.compute(modelJson, kernelDocument).values()
        val java = RuntimeLaws.kernelJavaEngine(modelJson)
        val generated = java.compute(java.instance(kernelDocument)).values()

        val definition = ModelLoader.load(modelJson)
        val interpreterDocument = definition.newDocument().group(ROOT, 1).field(INPUT, "5", 1, 1)
        val interpreted = DmInterpreter(definition).compute(interpreterDocument)
            .filter { it.path in TARGETS }.associate { it.path to it.value }

        val expected = mapOf(DIRECT to "2020", PLUS_ONE to "2021", PLUS_FIELD to "2025")
        assertEquals(expected, dynamic, "normative dynamic-Groovy")
        assertEquals(dynamic, generated, "generated Java")
        assertEquals(dynamic, interpreted, "interpreter")
    }

    private fun List<RuntimeLaws.Computed>.values(): Map<String, String?> =
        filter { it.path() in TARGETS }.associate { it.path() to it.value().orElse(null) }

    private companion object {
        const val ROOT = "/Forecast"
        const val INPUT = "/Forecast/Input"
        const val DIRECT = "/Forecast/Direct"
        const val PLUS_ONE = "/Forecast/PlusOne"
        const val PLUS_FIELD = "/Forecast/PlusField"
        val TARGETS = setOf(DIRECT, PLUS_ONE, PLUS_FIELD)
    }
}
