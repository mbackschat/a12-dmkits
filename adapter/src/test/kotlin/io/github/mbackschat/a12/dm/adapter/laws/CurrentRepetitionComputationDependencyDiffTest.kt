package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** `CurrentRepetition(group)` reads a row coordinate, not every computed field contained by that group. */
class CurrentRepetitionComputationDependencyDiffTest {

    @Test
    fun structuralGuardAndRealDependentComputeInDependencyOrder() {
        val baseJson = DocumentModelDraft.named("shipment-costs").locale("en_US")
            .rootGroup("Shipment") { shipment ->
                shipment.group("Lines") { lines ->
                    lines.repeatable(10)
                    lines.numberField("Base") { it.fractionDigits(0) }
                    lines.numberField("First") { it.fractionDigits(0) }
                    lines.numberField("Second") { it.fractionDigits(0) }
                }
            }
            .build()
            .toJson()
        val model = BoundModel.ofJson(baseJson)
        val lines = model.group(LINES)
        val first = model.computation(model.num(FIRST))
            .name("FirstCost")
            .commonPrecondition(Dsl.currentRepetition(lines).gt(0))
            .calc(model.num(BASE).asNumber().expression())
            .message(Dsl.en("first cost"))
            .build()
        val second = model.computation(model.num(SECOND))
            .name("SecondCost")
            .calc(model.num(FIRST).asNumber().expression())
            .message(Dsl.en("second cost"))
            .build()
        val modelJson = TypedRules.inject(TypedRules.inject(baseJson, first), second)
        val definition = ModelLoader.load(modelJson)
        val kernelDocument = RuntimeLaws.doc().group(ROOT, 1)
            .group(LINES, 1, 1)
            .field(BASE, "7", 1, 1, 1)
        val interpreterDocument = definition.newDocument().group(ROOT, 1)
            .group(LINES, 1, 1)
            .field(BASE, "7", 1, 1, 1)

        val dynamic = RuntimeLaws.compute(modelJson, kernelDocument).computedValues()
        val java = RuntimeLaws.kernelJavaEngine(modelJson)
        val generated = java.compute(java.instance(kernelDocument)).computedValues()
        val interpreted = DmInterpreter(definition).compute(interpreterDocument)
            .filter { it.path == FIRST || it.path == SECOND }
            .associate { it.path to it.value }

        assertEquals(mapOf(FIRST to "7", SECOND to "7"), dynamic, "dynamic Groovy")
        assertEquals(dynamic, generated, "generated Java")
        assertEquals(dynamic, interpreted, "interpreter")
    }

    private fun List<RuntimeLaws.Computed>.computedValues(): Map<String, String?> =
        filter { it.path() == FIRST || it.path() == SECOND }
            .associate { it.path() to it.value().orElse(null) }

    private companion object {
        const val ROOT = "/Shipment"
        const val LINES = "/Shipment/Lines"
        const val BASE = "/Shipment/Lines/Base"
        const val FIRST = "/Shipment/Lines/First"
        const val SECOND = "/Shipment/Lines/Second"
    }
}
