package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A fill quantifier over three operands and a group list over both joined groups read the joined row as fixed
 * groups: each fires once per joined key, a matched row reading both sides and an unmatched one reading its missing
 * side as empty (`ANY_GROUP` and `ANY_OF_THREE` at `[-5]` for the stocked-only `E`; `MANY_OF_THREE` on the
 * ordered-only `C`). A line beyond either group's capacity joins nothing and draws only the index-overflow codes.
 *
 * Requested by a12-kernel-lean as SPEC-2026-11-05-04's runtime rows; re-expressed on an order-fulfilment sheet whose
 * `Orders` and `Stock` lines are keyed by `Sku`, with capacity 3 and a fourth line on each side. Own domain.
 */
class JoinedRowListQuantifierDiffTest {

    private val skeleton = DocumentModelDraft.named("order-list-quantifiers").locale("en_US")
        .rootGroup("Fulfilment") { f -> f
            .group("Orders") { o -> o.repeatable(3).indexField("Sku")
                .stringField("Sku") { }
                .numberField("Qty") { it.fractionDigits(0) }
                .stringField("Note") { } }
            .group("Stock") { s -> s.repeatable(3).indexField("Sku")
                .stringField("Sku") { }
                .numberField("OnHand") { it.fractionDigits(0) } } }
        .build().toJson()

    private val bound = BoundModel.ofJson(skeleton)
    private val qty = bound.field("/Fulfilment/Orders/Qty")
    private val note = bound.field("/Fulfilment/Orders/Note")
    private val onHand = bound.field("/Fulfilment/Stock/OnHand")
    private val orders = bound.group("/Fulfilment/Orders")
    private val stock = bound.group("/Fulfilment/Stock")

    private fun rule(code: String, condition: Condition) =
        bound.rule(bound.group("/Fulfilment")).name("${code.lowercase()}_rule").errorField(qty).`when`(condition)
            .code(code).severity(Severity.ERROR).message(Dsl.en("Check this SKU.")).build()

    private val model = bound.addAll(listOf(
        rule("ALL_THREE", Dsl.allFieldsFilled(qty, note, onHand)),
        rule("MANY_OF_THREE", Dsl.moreThanOneFieldFilled(qty, note, onHand)),
        rule("ANY_OF_THREE", Dsl.atLeastOneFieldFilled(qty, note, onHand)),
        rule("ANY_GROUP", Dsl.atLeastOneGroupFilled(orders, stock)),
        rule("ALL_GROUPS", Dsl.allGroupsFilled(orders, stock)),
    ), emptyList()).toJson()

    /** `A` matched and filled on both sides, `B` matched with only its stock key, `C` ordered only, `E` stocked
     *  only, and `D` a fourth line on each side, beyond capacity. */
    private val documents = linkedMapOf(
        "matched, one-sided and over-capacity lines" to """{"Fulfilment":{
            "Orders":[{"Sku":"A","Qty":5,"Note":"rush"},{"Sku":"B","Qty":1},{"Sku":"C","Qty":3,"Note":"gift"},{"Sku":"D"}],
            "Stock":[{"Sku":"A","OnHand":5},{"Sku":"B"},{"Sku":"E","OnHand":2},{"Sku":"D"}]}}""",
    )

    @Test
    fun a12AdmitsTheModel() {
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(model))
    }

    @Test
    fun eachQuantifierFiresOncePerJoinedKey() {
        val observed = documents.mapValues { (_, json) ->
            DocumentDifferential.kernelMessages(model, json, true)
                .map { "${it.code}@${it.pointer.removePrefix("/Fulfilment[1]/")}" }.sorted()
        }
        assertEquals(linkedMapOf(
            "matched, one-sided and over-capacity lines" to listOf(
                "ALL_GROUPS@Orders[1]/Qty", "ALL_GROUPS@Orders[2]/Qty", "ALL_THREE@Orders[1]/Qty",
                "ANY_GROUP@Orders[-5]/Qty", "ANY_GROUP@Orders[1]/Qty", "ANY_GROUP@Orders[2]/Qty", "ANY_GROUP@Orders[3]/Qty",
                "ANY_OF_THREE@Orders[-5]/Qty", "ANY_OF_THREE@Orders[1]/Qty", "ANY_OF_THREE@Orders[2]/Qty",
                "ANY_OF_THREE@Orders[3]/Qty", "MANY_OF_THREE@Orders[1]/Qty", "MANY_OF_THREE@Orders[3]/Qty",
                // The fourth lines draw only the index-overflow codes.
                "zuGrosseKontextnummer@Orders[4]/Sku", "zuGrosseKontextnummer@Stock[4]/Sku", "zuGrosseZeile@Orders",
                "zuGrosseZeile@Stock",
            ),
        ), observed)
    }

    @Test
    fun allThreeEnginesAgreeOnEveryDocument() {
        DocumentDifferential.assertAgree(model, documents)
    }
}
