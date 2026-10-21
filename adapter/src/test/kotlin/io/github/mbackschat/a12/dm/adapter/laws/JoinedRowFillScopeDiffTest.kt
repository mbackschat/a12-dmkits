package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Unstarred fill scopes read from a joined row (IG174): a field list naming both sides, a side's non-repeatable
 * subgroup as a fill-quantifier operand, and that subgroup's `FirstFilledValue`. Each binds the joined row itself,
 * so a matched side reads its own row and an unmatched side reads its `-5` row; the documents give every SKU a
 * different fill so a read of the wrong row or of the common parent shows.
 *
 * Own domain (an order-fulfilment sheet: orders with a delivery window, stock with bin levels).
 */
class JoinedRowFillScopeDiffTest {

    private val skeleton = DocumentModelDraft.named("order-fill-scopes").locale("en_US")
        .rootGroup("Fulfilment") { f -> f
            .group("Orders") { o -> o.repeatable(4).indexField("Sku")
                .stringField("Sku") { }
                .numberField("Qty") { it.fractionDigits(0) }
                .group("Window") { w -> w.numberField("From") { it.fractionDigits(0) }.numberField("To") { it.fractionDigits(0) } } }
            .group("Stock") { s -> s.repeatable(4).indexField("Sku")
                .stringField("Sku") { }
                .numberField("OnHand") { it.fractionDigits(0) }
                .stringField("Note") { }
                .group("Levels") { l -> l.numberField("Low") { it.fractionDigits(0) }.numberField("High") { it.fractionDigits(0) } } } }
        .build().toJson()

    private val bound = BoundModel.ofJson(skeleton)
    private val fulfilment = bound.group("/Fulfilment")
    private val qty = bound.num("/Fulfilment/Orders/Qty")
    private val onHand = bound.num("/Fulfilment/Stock/OnHand")
    private val note = bound.str("/Fulfilment/Stock/Note")
    private val qtyField = bound.field("/Fulfilment/Orders/Qty")
    private val onHandField = bound.field("/Fulfilment/Stock/OnHand")
    private val window = bound.flattenGroup("/Fulfilment/Orders/Window")
    private val levels = bound.flattenGroup("/Fulfilment/Stock/Levels")

    private fun rule(code: String, condition: Condition) =
        bound.rule(fulfilment).name("${code.lowercase()}_rule")
            .errorField(bound.field("/Fulfilment/Stock/Note")).`when`(note.notFilled().and(condition))
            .code(code).severity(Severity.ERROR).message(Dsl.en("Check the fulfilment of this SKU.")).build()

    private val model = bound.addAll(listOf(
        rule("NOT_ALL", qty.filled().and(Dsl.notAllFieldsFilled(qtyField, onHandField))),
        rule("ONE_FILLED", Dsl.numberOfFilledFields(qtyField, onHandField).eq(1)),
        rule("LEVELS_EMPTY", qty.filled().and(Dsl.noFieldFilled(levels))),
        rule("WINDOW_SET", onHand.filled().and(Dsl.atLeastOneFieldFilled(window))),
        rule("FIRST_LEVEL_HIGH", qty.filled().and(Dsl.firstFilledNumber(bound.firstFilledGroup("/Fulfilment/Stock/Levels")).gt(2))),
    ), emptyList()).toJson()

    private fun order(sku: String, qty: Int?, window: String = "") = "{" + listOfNotNull("\"Sku\":\"$sku\"",
        qty?.let { "\"Qty\":$it" }, window.takeIf { it.isNotEmpty() }?.let { "\"Window\":$it" }).joinToString(",") + "}"

    private fun stock(sku: String, onHand: Int?, levels: String = "") = "{" + listOfNotNull("\"Sku\":\"$sku\"",
        onHand?.let { "\"OnHand\":$it" }, levels.takeIf { it.isNotEmpty() }?.let { "\"Levels\":$it" }).joinToString(",") + "}"

    private fun sheet(orders: List<String>, stock: List<String>) =
        """{"Fulfilment":{"Orders":[${orders.joinToString(",")}],"Stock":[${stock.joinToString(",")}]}}"""

    private val documents = linkedMapOf(
        "every SKU on both sides, stock in the opposite order" to sheet(
            listOf(order("A", 2, """{"From":1}"""), order("B", 1)),
            listOf(stock("B", 3, """{"High":5}"""), stock("A", null))),
        "an order without stock and stock without an order" to sheet(
            listOf(order("A", 1, """{"To":3}"""), order("C", 1)),
            listOf(stock("A", 1, """{"High":4}"""), stock("D", 2, """{"Low":5}"""))),
        "matched rows with nothing filled" to sheet(listOf(order("A", null)), listOf(stock("A", null))),
    )

    @Test
    fun a12AdmitsTheModel() {
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(model))
    }

    @Test
    fun theKernelReadsEachJoinedRow() {
        val observed = documents.mapValues { (_, json) ->
            DocumentDifferential.kernelMessages(model, json, true).map { "${it.code}@${it.pointer.removePrefix("/Fulfilment[1]/")}" }
        }
        assertEquals(linkedMapOf(
            "every SKU on both sides, stock in the opposite order" to listOf("FIRST_LEVEL_HIGH@Stock[1]/Note",
                "LEVELS_EMPTY@Stock[2]/Note", "NOT_ALL@Stock[2]/Note", "ONE_FILLED@Stock[2]/Note"),
            // SKU C has no stock and D no order: each reads the missing side at its `-5` row, empty.
            "an order without stock and stock without an order" to listOf("FIRST_LEVEL_HIGH@Stock[1]/Note",
                "LEVELS_EMPTY@Stock[-5]/Note", "NOT_ALL@Stock[-5]/Note", "ONE_FILLED@Stock[-5]/Note",
                "ONE_FILLED@Stock[2]/Note", "WINDOW_SET@Stock[1]/Note"),
            "matched rows with nothing filled" to emptyList(),
        ), observed)
    }

    @Test
    fun allThreeEnginesAgreeOnEveryDocument() {
        DocumentDifferential.assertAgree(model, documents)
    }
}
