package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ToleranceBand
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Multiplatform (JVM + JS) lock for the computation `toleranceRangeOp` load (IF148): the loader carries the
 * closed [ToleranceBand] onto the alternative, and an unknown non-empty value is rejected LOUD (never silently a
 * strict `!=`). The end-to-end validation semantics are pinned in `adapter … ComputationToleranceValidationDiffTest`.
 */
class ComputationToleranceBandLoadTest {

    private fun model(toleranceRangeOpJson: String?) = """
        {
          "header": { "id": "order" },
          "content": { "modelRoot": { "rootGroups": [ {
            "type": "Group", "id": "/Order", "name": "Order", "Group": { "elements": [
              { "type": "Field", "id": "/Order/Base", "name": "Base", "Field": { "fieldType": {"type":"NumberType"} } },
              { "type": "Field", "id": "/Order/Fee", "name": "Fee", "Field": { "fieldType": {"type":"NumberType"} } },
              { "type": "Computation", "id": "/Order/FeeCheck", "name": "FeeCheck", "Computation": {
                "computedFieldRelPath": "../Fee", "computationAlternatives": [
                  { "operation": "[Base]"${if (toleranceRangeOpJson == null) "" else ", \"toleranceRangeOp\": $toleranceRangeOpJson"} } ] } }
            ] } } ] } }
        }
    """.trimIndent()

    private fun bandOf(toleranceRangeOp: String?) =
        ModelLoader.load(model(toleranceRangeOp?.let { "\"$it\"" })).computations.first().alternatives.first().tolerance

    @Test fun eachBandLoads() {
        assertEquals(ToleranceBand.TR1, bandOf("DiffersWithToleranceRange1"))
        assertEquals(ToleranceBand.TR2, bandOf("DiffersWithToleranceRange2"))
        assertEquals(ToleranceBand.TR5, bandOf("DiffersWithToleranceRange5"))
        assertEquals(ToleranceBand.TR10, bandOf("DiffersWithToleranceRange10"))
    }

    @Test fun absentToleranceIsNull() = assertNull(bandOf(null), "no toleranceRangeOp → strict != (null band)")

    @Test fun explicitNullToleranceIsNull() {
        val band = ModelLoader.load(model("null")).computations.first().alternatives.first().tolerance
        assertNull(band, "explicit null retains strict !=")
    }

    @Test fun anUnknownToleranceValueIsRejectedLoud() {
        val e = assertFailsWith<IllegalArgumentException> { bandOf("DiffersWithToleranceRange3") }
        assertContains(e.message ?: "", "toleranceRangeOp")
        assertContains(e.message ?: "", "DiffersWithToleranceRange3")
    }

    @Test fun everyMalformedPresentToleranceIsRejectedLoud() {
        listOf("\"\"", "\"   \"", "{}", "[]", "7", "true").forEach { malformed ->
            val e = assertFailsWith<IllegalArgumentException>(malformed) { ModelLoader.load(model(malformed)) }
            assertContains(e.message ?: "", "toleranceRangeOp")
            assertContains(e.message ?: "", "DiffersWithToleranceRange1")
        }
    }
}
