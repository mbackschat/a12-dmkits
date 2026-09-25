package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.RoundingMode
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Multiplatform (JVM + JS) lock for a computation's `roundingMode` (IF341): the loader carries A12's three stored
 * spellings and refuses any other loud, and compute writes what `adapter … ComputationRoundingModeDiffTest`
 * measured on both kernel strategies — the value rounded to a whole number in the mode's direction, `Exact` beside a
 * band rounded down to the target's digits, and a validation that still compares the operation unrounded.
 */
class ComputationRoundingModeTest {

    private fun model(roundingModeJson: String?, banded: Boolean = true) = """
        {
          "header": { "id": "order" },
          "content": { "modelRoot": { "rootGroups": [ {
            "type": "Group", "id": "/Order", "name": "Order", "Group": { "elements": [
              { "type": "Field", "id": "/Order/Base", "name": "Base",
                "Field": { "fieldType": {"type":"NumberType", "NumberType":{"maxFractionalDigits":3}} } },
              { "type": "Field", "id": "/Order/Fee", "name": "Fee",
                "Field": { "fieldType": {"type":"NumberType", "NumberType":{"maxFractionalDigits":2}} } },
              { "type": "Computation", "id": "/Order/FeeCheck", "name": "FeeCheck", "Computation": {
                "computedFieldRelPath": "../Fee",${if (roundingModeJson == null) "" else " \"roundingMode\": $roundingModeJson,"}
                "computationAlternatives": [ { "operation": "[Base]"${
                    if (banded) ", \"toleranceRangeOp\": \"DiffersWithToleranceRange1\"" else ""} } ],
                "errorCodesToSuppress": ["MVK_INVALID_COMPARE_DEC_PLACES"],
                "errorMessage": { "en": "The fee follows the base." } } }
            ] } } ] } }
        }
    """.trimIndent()

    private fun modeOf(roundingModeJson: String?) = ModelLoader.load(model(roundingModeJson)).computations.single().roundingMode

    private fun written(mode: String?, base: String, banded: Boolean = true): String {
        val def = ModelLoader.load(model(mode?.let { "\"$it\"" }, banded))
        val result = DmInterpreter(def).compute(def.newDocument().group(ROOT, 1).field(BASE, base, 1, 1)).single()
        return "${result.outcome}|${result.value ?: ""}"
    }

    @Test fun eachStoredSpellingLoads() {
        assertEquals(RoundingMode.EXACT, modeOf("\"Exact\""))
        assertEquals(RoundingMode.ROUND_UP, modeOf("\"RoundUp\""))
        assertEquals(RoundingMode.ROUND_DOWN, modeOf("\"RoundDown\""))
    }

    /** A12's serializer never writes `Exact`, and its accessor reads an explicit null as absent (KF297). */
    @Test fun anAbsentOrNullModeIsExact() {
        assertEquals(RoundingMode.EXACT, modeOf(null))
        assertEquals(RoundingMode.EXACT, modeOf("null"))
    }

    /** A12 matches its internal values exactly (`getByInternalValue` … `orElseThrow`, KF297), so a constant name or
     *  another letter case is no spelling of anything. */
    @Test fun anyOtherPresentModeIsRefusedLoud() {
        listOf("\"ROUND_UP\"", "\"roundup\"", "\"\"", "{}", "1").forEach { malformed ->
            val e = assertFailsWith<IllegalArgumentException>(malformed) { modeOf(malformed) }
            assertContains(e.message ?: "", "roundingMode")
            assertContains(e.message ?: "", "RoundUp")
        }
    }

    @Test fun theModeRoundsTheWrittenValueToAWholeNumberInItsDirection() {
        assertEquals("VALUE|2", written("RoundUp", "1.25"))
        assertEquals("VALUE|-1", written("RoundUp", "-1.25"))
        assertEquals("VALUE|1", written("RoundDown", "1.25"))
        assertEquals("VALUE|-2", written("RoundDown", "-1.25"))
    }

    @Test fun exactBesideABandRoundsDownToTheTargetsDigits() {
        assertEquals("VALUE|1.25", written(null, "1.255"))
        assertEquals("VALUE|-1.26", written(null, "-1.255"))
        assertEquals("ERRORED", written(null, "1.255", banded = false).substringBefore('|'), "the unbanded control")
    }

    @Test fun theValidationComparesTheOperationUnrounded() {
        val def = ModelLoader.load(model("\"RoundDown\""))
        val document = def.newDocument().group(ROOT, 1).field(BASE, "1.259", 1, 1).field(FEE, "0.25", 1, 1)
        val outcome = DmInterpreter(def).validate(document)
        assertTrue(outcome.unsupported.isEmpty(), outcome.unsupported.toString())
        assertTrue(outcome.messages.any { it.code == NAME }, "|0.25 − 1.259| exceeds the band; |0.25 − 1| would not")
    }

    private companion object {
        const val ROOT = "/Order"
        const val NAME = "FeeCheck"
        const val BASE = "/Order/Base"
        const val FEE = "/Order/Fee"
    }
}
