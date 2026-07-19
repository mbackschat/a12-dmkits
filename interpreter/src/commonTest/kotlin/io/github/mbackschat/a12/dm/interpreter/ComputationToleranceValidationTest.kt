package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** JVM/Node end-to-end IF148 lock: loader-independent synthesis, selected-tier bands, and unsupported safety. */
class ComputationToleranceValidationTest {

    private val model = ModelLoader.load(
        """
        {
          "header": { "id": "tiered-order" },
          "content": { "modelRoot": { "rootGroups": [ {
            "type": "Group", "id": "/Order", "name": "Order", "Group": { "elements": [
              { "type": "Field", "id": "/Order/Tier", "name": "Tier",
                "Field": { "fieldType": {"type":"StringType"} } },
              { "type": "Field", "id": "/Order/Gate", "name": "Gate",
                "Field": { "fieldType": {"type":"NumberType"} } },
              { "type": "Field", "id": "/Order/BaseA", "name": "BaseA",
                "Field": { "fieldType": {"type":"NumberType", "NumberType":{"maxFractionalDigits":1}} } },
              { "type": "Field", "id": "/Order/BaseB", "name": "BaseB",
                "Field": { "fieldType": {"type":"NumberType", "NumberType":{"maxFractionalDigits":1}} } },
              { "type": "Field", "id": "/Order/Fee", "name": "Fee",
                "Field": { "fieldType": {"type":"NumberType", "NumberType":{"maxFractionalDigits":1}} } },
              { "type": "Computation", "id": "/Order/FeeCheck", "name": "FeeCheck", "Computation": {
                "computedFieldRelPath": "../Fee",
                "commonPrecondition": "FieldFilled(Gate)",
                "computationAlternatives": [
                  { "precondition": "[Tier] == \"A\"", "operation": "[BaseA]",
                    "toleranceRangeOp": "DiffersWithToleranceRange1" },
                  { "precondition": "[Tier] == \"B\"", "operation": "[BaseB]",
                    "toleranceRangeOp": "DiffersWithToleranceRange10" }
                ]
              } }
            ] } } ] } }
        }
        """.trimIndent(),
    )

    private fun fires(vararg fields: Pair<String, String>): Boolean {
        val document = model.newDocument().group(ROOT, 1)
        fields.forEach { (path, value) -> document.field(path, value, 1, 1) }
        val outcome = DmInterpreter(model).validate(document)
        assertTrue(outcome.unsupported.isEmpty(), outcome.unsupported.toString())
        return outcome.messages.any { it.code == NAME }
    }

    @Test
    fun selectedAlternativeKeepsItsBand() {
        assertTrue(fires(GATE to "1", TIER to "A", BASE_A to "100.0", BASE_B to "100.0", FEE to "101.5"))
        assertEquals(
            false,
            fires(GATE to "1", TIER to "B", BASE_A to "100.0", BASE_B to "100.0", FEE to "105.0"),
        )
    }

    @Test
    fun falseCommonAndAlternativeGuardsStaySilent() {
        assertEquals(false, fires(TIER to "A", BASE_A to "100.0", BASE_B to "100.0", FEE to "102.0"))
        assertEquals(
            false,
            fires(GATE to "1", TIER to "C", BASE_A to "100.0", BASE_B to "100.0", FEE to "120.0"),
        )
    }

    private companion object {
        const val ROOT = "/Order"
        const val NAME = "FeeCheck"
        const val TIER = "/Order/Tier"
        const val GATE = "/Order/Gate"
        const val BASE_A = "/Order/BaseA"
        const val BASE_B = "/Order/BaseB"
        const val FEE = "/Order/Fee"
    }
}
