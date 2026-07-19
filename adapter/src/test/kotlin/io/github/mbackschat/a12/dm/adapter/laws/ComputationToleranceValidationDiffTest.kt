package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.ToleranceBand
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Typed tri-engine IF148 lock for a computation alternative's `toleranceRangeOp`: every band, both numeric
 * directions, tier selection, and false guards use one rulekit computation as the kernel/interpreter input.
 */
class ComputationToleranceValidationDiffTest {

    private fun singleBandModel(band: ToleranceBand): String {
        val base = DocumentModelDraft.named("order").locale("en_US")
            .rootGroup("Order") { order ->
                order.numberField("Base") { it.fractionDigits(1) }
                order.numberField("Fee") { it.fractionDigits(1) }
            }
            .build()
            .toJson()
        val model = BoundModel.ofJson(base)
        val computation = model.computation(model.num(FEE))
            .name(NAME)
            .calc(model.num(BASE).value(), band)
            .message(Dsl.en("Fee follows Base within the selected tolerance."))
            .build()
        return TypedRules.inject(base, computation)
    }

    private fun agree(
        modelJson: String,
        fields: Map<String, String>,
        expectFire: Boolean,
        scenario: String,
    ) {
        val def = ModelLoader.load(modelJson)
        val kernelDocument = RuntimeLaws.doc().group(ROOT, 1)
        val interpreterDocument = def.newDocument().group(ROOT, 1)
        fields.forEach { (path, value) ->
            kernelDocument.field(path, value, 1, 1)
            interpreterDocument.field(path, value, 1, 1)
        }

        val dynamic = RuntimeLaws.firedCodes(modelJson, kernelDocument).contains(NAME)
        val staticEngine = RuntimeLaws.kernelJavaEngine(modelJson)
        val generatedJava = staticEngine.firedCodesSorted(staticEngine.instance(kernelDocument)).contains(NAME)
        val interpreter = DmInterpreter(def).validate(interpreterDocument).messages.any { it.code == NAME }

        assertEquals(expectFire, dynamic, "$scenario — normative dynamic-Groovy")
        assertEquals(dynamic, generatedJava, "$scenario — generated Java")
        assertEquals(dynamic, interpreter, "$scenario — interpreter")
    }

    @Test
    fun everyBandCoversEqualInteriorBoundaryAndBothOutsideDirections() {
        val cases = mapOf(
            ToleranceBand.TR1 to listOf("100.0" to false, "100.5" to false, "101.0" to false, "101.5" to true, "98.5" to true),
            ToleranceBand.TR2 to listOf("100.0" to false, "101.0" to false, "102.0" to false, "102.5" to true, "97.5" to true),
            ToleranceBand.TR5 to listOf("100.0" to false, "102.5" to false, "105.0" to false, "105.5" to true, "94.5" to true),
            ToleranceBand.TR10 to listOf("100.0" to false, "105.0" to false, "110.0" to false, "110.5" to true, "89.5" to true),
        )
        cases.forEach { (band, fees) ->
            val model = singleBandModel(band)
            fees.forEach { (fee, fires) ->
                agree(model, mapOf(BASE to "100.0", FEE to fee), fires, "${band.wire()} fee=$fee")
            }
        }
    }

    @Test
    fun selectedTierKeepsItsOwnBandAndFalseGuardsStaySilent() {
        val base = DocumentModelDraft.named("tiered-order").locale("en_US")
            .rootGroup("Order") { order ->
                order.stringField("Tier") { }
                order.numberField("Gate") { it.fractionDigits(0) }
                order.numberField("BaseA") { it.fractionDigits(1) }
                order.numberField("BaseB") { it.fractionDigits(1) }
                order.numberField("Fee") { it.fractionDigits(1) }
            }
            .build()
            .toJson()
        val model = BoundModel.ofJson(base)
        val computation = model.computation(model.num(FEE))
            .name(NAME)
            .commonPrecondition(model.num(GATE).filled())
            .`when`(model.str(TIER).eq("A")).calc(model.num(BASE_A).value(), ToleranceBand.TR1)
            .`when`(model.str(TIER).eq("B")).calc(model.num(BASE_B).value(), ToleranceBand.TR10)
            .message(Dsl.en("Fee follows the selected tier."))
            .build()
        val json = TypedRules.inject(base, computation)

        agree(
            json,
            mapOf(GATE to "1", TIER to "A", BASE_A to "100.0", BASE_B to "100.0", FEE to "101.5"),
            true,
            "tier A selects TR1",
        )
        agree(
            json,
            mapOf(GATE to "1", TIER to "B", BASE_A to "100.0", BASE_B to "100.0", FEE to "105.0"),
            false,
            "tier B selects TR10",
        )
        agree(
            json,
            mapOf(TIER to "A", BASE_A to "100.0", BASE_B to "100.0", FEE to "102.0"),
            false,
            "false common guard",
        )
        agree(
            json,
            mapOf(GATE to "1", TIER to "C", BASE_A to "100.0", BASE_B to "100.0", FEE to "120.0"),
            false,
            "no alternative selected",
        )
    }

    private companion object {
        const val ROOT = "/Order"
        const val NAME = "FeeCheck"
        const val BASE = "/Order/Base"
        const val FEE = "/Order/Fee"
        const val GATE = "/Order/Gate"
        const val TIER = "/Order/Tier"
        const val BASE_A = "/Order/BaseA"
        const val BASE_B = "/Order/BaseB"
    }
}
