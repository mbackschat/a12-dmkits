package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A computation and its implicit validation intentionally use different alternative semantics.
 * Computation selects the first holding alternative; validation retains every holding alternative's
 * mismatch disjunct. Overlapping guards therefore may compute the first value and still report that a
 * later holding alternative disagrees with the stored target.
 */
class ComputationOverlapValidationDiffTest {

    private val base = RuntimeLaws.model("models/order.dm.json")

    @Test
    fun laterHoldingMismatchFiresAlthoughTheFirstAlternativeComputes() {
        agree(
            alternatives = listOf("[Quantity] >= 0" to "1", "[Quantity] <= 10" to "2"),
            quantity = "5",
            stored = "1",
            expectedComputed = "VALUE:1",
            expectedFired = true,
        )
    }

    @Test
    fun overlappingAlternativesWithTheSameResultDoNotFire() {
        agree(
            alternatives = listOf("[Quantity] >= 0" to "1", "[Quantity] <= 10" to "1"),
            quantity = "5",
            stored = "1",
            expectedComputed = "VALUE:1",
            expectedFired = false,
        )
    }

    @Test
    fun aNonholdingLaterAlternativeContributesNoMismatch() {
        agree(
            alternatives = listOf("[Quantity] >= 0" to "1", "[Quantity] < 0" to "2"),
            quantity = "5",
            stored = "1",
            expectedComputed = "VALUE:1",
            expectedFired = false,
        )
    }

    private fun agree(
        alternatives: List<Pair<String, String>>,
        quantity: String,
        stored: String,
        expectedComputed: String,
        expectedFired: Boolean,
    ) {
        val model = RuntimeLaws.withTieredComputation(
            base,
            "/Order",
            NAME,
            "../BackorderQuantity",
            null,
            alternatives.map { RuntimeLaws.CompAlt(it.first, it.second) },
        )
        val kDoc = RuntimeLaws.doc().group("/Order", 1)
            .field(QUANTITY, quantity, 1, 1)
            .field(TARGET, stored, 1, 1)
        val definition = ModelLoader.load(model)
        val iDoc = definition.newDocument().group("/Order", 1)
            .field(QUANTITY, quantity, 1, 1)
            .field(TARGET, stored, 1, 1)
        val interpreter = DmInterpreter(definition)
        val java = RuntimeLaws.kernelJavaEngine(model)

        val groovyComputed = RuntimeLaws.compute(model, kDoc).targetOutcome()
        val javaComputed = java.compute(java.instance(kDoc)).targetOutcome()
        val interpreterComputed = interpreter.compute(iDoc)
            .single { it.path == TARGET && it.outcome != ComputeOutcome.CLEARED }
            .let { if (it.outcome == ComputeOutcome.ERRORED) "ERRORED" else "VALUE:${it.value}" }
        assertEquals(expectedComputed, groovyComputed, "dynamic-Groovy compute")
        assertEquals(groovyComputed, javaComputed, "static-Java compute")
        assertEquals(groovyComputed, interpreterComputed, "interpreter compute")

        val groovyFired = RuntimeLaws.messages(model, kDoc).any { it.code() == NAME }
        val javaFired = java.messages(java.instance(kDoc)).any { it.code() == NAME }
        val interpreterFired = interpreter.validateFull(iDoc).any { it.code == NAME }
        assertEquals(expectedFired, groovyFired, "dynamic-Groovy implicit validation")
        assertEquals(groovyFired, javaFired, "static-Java implicit validation")
        assertEquals(groovyFired, interpreterFired, "interpreter implicit validation")
    }

    private fun List<RuntimeLaws.Computed>.targetOutcome(): String =
        single { it.path() == TARGET && !it.cleared() }.let {
            if (it.errored()) "ERRORED" else "VALUE:${it.value().orElse("")}"
        }
}

private const val NAME = "OverlapComp"
private const val QUANTITY = "/Order/Quantity"
private const val TARGET = "/Order/BackorderQuantity"
