package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.CompareOp
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Discriminating below/equal/above matrix for every published ordering comparison. */
class OrderingTruthMatrixDiffTest {

    private val base = RuntimeLaws.model("models/order.dm.json")
    private val m = BoundModel.ofJson(base)
    private val renderer = DslRenderer()
    private val conditions = linkedMapOf(
        CompareOp.LE to m.num(QUANTITY).le(5),
        CompareOp.LT to m.num(QUANTITY).lt(5),
        CompareOp.GT to m.num(QUANTITY).gt(5),
        CompareOp.GE to m.num(QUANTITY).ge(5),
    )
    private val model = conditions.entries.foldIndexed(base) { index, json, entry ->
        RuntimeLaws.withRule(
            json,
            ORDER,
            "Ordering$index",
            "../Quantity",
            entry.key.name,
            renderer.render(entry.value),
        )
    }
    private val definition = ModelLoader.load(model)
    private val generated = RuntimeLaws.kernelJavaEngine(model)

    @Test
    fun everyOrderingOperatorDistinguishesBelowEqualAndAbove() {
        val publishedOrdering = CompareOp.entries
            .filter { it.complement().isPresent && it != CompareOp.EQ && it != CompareOp.NE }
            .toSet()
        assertEquals(publishedOrdering, conditions.keys,
            "every published non-tolerance ordering comparison needs a discriminating truth matrix")
        val codes = conditions.keys.map { it.name }.toSet()
        listOf(
            "4" to setOf("LE", "LT"),
            "5" to setOf("LE", "GE"),
            "6" to setOf("GT", "GE"),
        ).forEach { (value, expected) ->
            val kernelDocument = RuntimeLaws.doc().group(ORDER, 1).field(QUANTITY, value, 1, 1)
            val dynamicCodes = RuntimeLaws.messages(model, kernelDocument)
                .map { it.code() }.filter(codes::contains).toSet()
            val generatedCodes = generated.messages(generated.instance(kernelDocument))
                .map { it.code() }.filter(codes::contains).toSet()
            val interpreterCodes = DmInterpreter(definition).validateFull(
                definition.newDocument().group(ORDER, 1).field(QUANTITY, value, 1, 1),
            ).map { it.code }.filter(codes::contains).toSet()

            assertEquals(expected, dynamicCodes, "$value against 5 — dynamic Groovy")
            assertEquals(dynamicCodes, generatedCodes, "$value against 5 — generated Java")
            assertEquals(dynamicCodes, interpreterCodes, "$value against 5 — interpreter")
        }
    }

    private companion object {
        const val ORDER = "/Order"
        const val QUANTITY = "/Order/Quantity"
    }
}
