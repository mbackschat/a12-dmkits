package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A per-row fill quantifier over a star answers from its own parent row's lines (INTERPRETER-FINDINGS IF411). One
 * validation shares a starred operand's tally across the rows of one scope, so what this locks is that two sibling
 * orders are two scopes: a tally kept without its scope would answer the second order from the first order's lines.
 * Each document is also read in the reverse order, so neither answer can come from whichever order is asked first.
 */
class FillScopePerParentRowDiffTest {

    private val skeleton = DocumentModelDraft.named("shop").locale("en_US")
        .rootGroup("Shop") { s ->
            s.group("Orders") { o -> o.repeatable(3)
                .stringField("Ref") { }
                .group("Lines") { l -> l.repeatable(3).stringField("Note") { } } }
        }.build().toJson()

    private val bound = BoundModel.ofJson(skeleton)
    private val notes = bound.flatten("$ORDERS/Lines*/Note")

    private fun rule(code: String, condition: Condition) =
        bound.rule(bound.group(ORDERS)).name("${code.lowercase()}_rule").errorField(bound.field("$ORDERS/Ref"))
            .`when`(bound.str("$ORDERS/Ref").filled().and(condition))
            .code(code).severity(Severity.ERROR).message(Dsl.en("Check the order's notes.")).build()

    private val model = bound.addAll(
        listOf(rule("NOTED", Dsl.atLeastOneFieldFilled(notes)), rule("UNNOTED", Dsl.noFieldFilled(notes))),
        emptyList(),
    ).toJson()

    private val documents = mapOf(
        "the first order noted" to """{"Shop":{"Orders":[
            {"Ref":"A","Lines":[{"Note":"fragile"},{}]},{"Ref":"B","Lines":[{},{}]}]}}""",
        "the second order noted" to """{"Shop":{"Orders":[
            {"Ref":"A","Lines":[{},{}]},{"Ref":"B","Lines":[{},{"Note":"fragile"}]}]}}""",
    )

    @Test
    fun eachOrderIsTalliedOverItsOwnLines() {
        assertEquals(mapOf(
            "the first order noted" to listOf("NOTED@1", "UNNOTED@2"),
            "the second order noted" to listOf("NOTED@2", "UNNOTED@1"),
        ), documents.mapValues { (_, json) ->
            DocumentDifferential.kernel(model, json, dynamic = true).messages
                .map { it.code + "@" + it.pointer.substringAfter("Orders[").substringBefore("]") }.sorted()
        })
        DocumentDifferential.assertAgree(model, documents)
    }

    private companion object {
        const val ORDERS = "/Shop/Orders"
    }
}
