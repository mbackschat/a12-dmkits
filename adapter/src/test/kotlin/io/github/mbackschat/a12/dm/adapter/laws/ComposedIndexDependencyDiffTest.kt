package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberLiteral
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ComposedIndexDependencyDiffTest {
    @Test
    fun indexedListsRetainFormalInputsAndComputedSourceDependencies() {
        for (computedSource in listOf(false, true)) verifyInventoryAndOrder(computedSource)
    }

    private fun verifyInventoryAndOrder(computedSource: Boolean) {
        val draft = DocumentModelDraft.named("parcel-index-dependencies").locale("en_US")
            .rootGroup("Order") { o ->
                o.stringField("Wanted") { }.numberField("Total") { }.numberField("Flag") { }
                    .group("Packages") { p ->
                        p.repeatable(2).indexField("Code").stringField("Code") { }
                            .group("Rows") { r -> r.repeatable(2).numberField("Input") { }.numberField("Derived") { } }
                    }
            }.build().toJson()
        val session = EditSession.read(draft)
        val order = session.group("/Order")
        val rows = session.group(order.group("Packages/Rows").path().value())
        val source = order.flatten("Packages/Rows*/Input").forIndex(order.str("Wanted").field())
        val derived = order.flatten("Packages/Rows*/Derived").forIndex("P1")
        var model = session.toJson()
        model = TypedRules.inject(model, order.computation(order.num("Total")) {
            it.name("TotalComp").calc(Dsl.sum(derived)).message(Dsl.en("Check total.")).build()
        })
        model = TypedRules.inject(model, order.computation(order.num("Flag")) {
            it.name("FlagComp").`when`(Dsl.numberOfFilledFields(source).gt(0)).calc(NumberLiteral.of("1"))
                .message(Dsl.en("Check input.")).build()
        })
        if (computedSource) model = TypedRules.inject(model, rows.computation(rows.num("Derived")) {
            it.name("DerivedComp").calc(NumberLiteral.of("10")).message(Dsl.en("Check derived value.")).build()
        })
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(model))
        for (value in listOf("7", "bad")) {
            val json = """{"Order":{"Wanted":"P1","Total":9,"Flag":9,"Packages":[{"Code":"P1","Rows":[{"Input":"$value","Derived":3}]}]}}"""
            val dynamic = DocumentDifferential.kernel(model, json, dynamic = true)
            assertEquals(if (computedSource) "VALUE|10" else "VALUE|3", dynamic.outcomes["Total"],
                "computed source precedes its indexed consumer; an ordinary source retains its stored value")
            assertEquals(if (value == "bad") listOf("zahlHatUngueltigeZeichen") else emptyList<String>(),
                dynamic.operandErrors.map { it.code }, "formal inventory includes indexed list inputs")
            assertEquals(dynamic, DocumentDifferential.kernel(model, json, dynamic = false), value)
            assertEquals(dynamic, DocumentDifferential.interpreter(model, json), value)
        }
        val missingIndex = """{"Order":{"Wanted":"P1","Total":9,"Flag":9,"Packages":[{"Rows":[{"Input":"7","Derived":3}]}]}}"""
        val observed = DocumentDifferential.kernel(model, missingIndex, dynamic = true)
        assertTrue(observed.operandErrors.any { it.pointer.endsWith("/Code") }, "the selected index column is an eager formal input")
        assertEquals(observed, DocumentDifferential.kernel(model, missingIndex, dynamic = false))
        assertEquals(observed, DocumentDifferential.interpreter(model, missingIndex))
    }
}
