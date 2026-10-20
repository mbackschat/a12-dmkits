package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Starred reads below a joined group, against the Kernel (a12-kernel-lean SPEC-2026-10-14-12, re-measured here,
 * KERNEL-FINDINGS KF573). Orders and Stock are joined by `Sku`. A star below one side reads that side's joined row,
 * at one starred level or two, through a count, a sum, a first-filled value or a fill quantifier, and a star alone
 * joins its side; a side without a row for the SKU reads its children empty at its `-5` row. [KF567]'s reading from
 * the common parent, the rival account, would pool every SKU's lines and bins, so each document gives one SKU
 * children and the other none.
 *
 * Own domain (an order-fulfilment sheet: orders with lines and picks, stock with bins).
 */
class JoinedSideChildReadDiffTest {

    private val skeleton = DocumentModelDraft.named("order-fulfilment").locale("en_US")
        .rootGroup("Fulfilment") { f -> f
            .group("Orders") { o -> o.repeatable(4).indexField("Sku")
                .stringField("Sku") { }
                .numberField("Qty") { it.fractionDigits(0) }
                .group("Lines") { l -> l.repeatable(3)
                    .numberField("Units") { it.fractionDigits(0) }
                    .group("Picks") { p -> p.repeatable(2).numberField("Count") { it.fractionDigits(0) } } } }
            .group("Stock") { s -> s.repeatable(4).indexField("Sku")
                .stringField("Sku") { }
                .numberField("OnHand") { it.fractionDigits(0) }
                .stringField("Note") { }
                .numberField("Reserved") { it.fractionDigits(0) }
                .group("Bins") { b -> b.repeatable(2).numberField("Level") { it.fractionDigits(0) } } } }
        .build().toJson()

    private val bound = BoundModel.ofJson(skeleton)
    private val fulfilment = bound.group("/Fulfilment")
    private val qty = bound.num("/Fulfilment/Orders/Qty")
    private val onHand = bound.num("/Fulfilment/Stock/OnHand")
    private val note = bound.str("/Fulfilment/Stock/Note")
    private val units = bound.flatten("/Fulfilment/Orders/Lines*/Units")
    private val picks = bound.flatten("/Fulfilment/Orders/Lines*/Picks*/Count")
    private val levels = bound.flatten("/Fulfilment/Stock/Bins*/Level")
    private val ordered = qty.filled().and(note.notFilled())
    private val stocked = onHand.filled().and(note.notFilled())

    private fun rule(code: String, condition: Condition) =
        bound.rule(fulfilment).name("${code.lowercase()}_rule")
            .errorField(bound.field("/Fulfilment/Stock/Note")).`when`(condition)
            .code(code).severity(Severity.ERROR).message(Dsl.en("Check the stock of this SKU.")).build()

    private val rules = listOf(
        rule("LINES_UNCOUNTED", ordered.and(Dsl.numberOfFilledFields(units).eq(0))),
        rule("BINS_EMPTY", ordered.and(Dsl.sum(levels).eq(0))),
        rule("BINS_UNFILLED", ordered.and(Dsl.noFieldFilled(levels))),
        rule("BINS_STOCKED", ordered.and(Dsl.atLeastOneFieldFilled(levels))),
        rule("FIRST_BIN_STOCKED", ordered.and(Dsl.firstFilledNumber(levels).gt(0))),
        rule("ONLY_LINES_UNCOUNTED", stocked.and(Dsl.numberOfFilledFields(units).eq(0))),
        rule("ONLY_PICKS_UNCOUNTED", stocked.and(Dsl.numberOfFilledFields(picks).eq(0))),
    )

    private val model = bound.addAll(rules, emptyList()).toJson()

    private val computed = bound.addAll(
        emptyList(),
        listOf(bound.computation(bound.num("/Fulfilment/Stock/Reserved")).name("ReservedComputation")
            .calc(qty.asNumber().plus(Dsl.sum(units))).message(Dsl.en("Reservation computed.")).build()),
    ).toJson()

    private fun order(sku: String, qty: Int, lines: String = "") =
        """{"Sku":"$sku","Qty":$qty""" + (if (lines.isEmpty()) "" else ""","Lines":[$lines]""") + "}"

    private fun stock(sku: String, onHand: Int, bins: String = "") =
        """{"Sku":"$sku","OnHand":$onHand""" + (if (bins.isEmpty()) "" else ""","Bins":[$bins]""") + "}"

    private fun sheet(orders: List<String>, stock: List<String>) =
        """{"Fulfilment":{"Orders":[${orders.joinToString(",")}],"Stock":[${stock.joinToString(",")}]}}"""

    private val documents = linkedMapOf(
        "every SKU on both sides, stock in the opposite order" to sheet(
            listOf(order("A", 2, """{"Units":1,"Picks":[{"Count":1}]},{}"""), order("B", 1)),
            listOf(stock("B", 3), stock("A", 5, """{},{"Level":2}"""))),
        "an order without stock and stock without an order" to sheet(
            listOf(order("A", 1, """{"Units":4}"""), order("C", 1, """{"Units":2}""")),
            listOf(stock("A", 1, """{"Level":3}"""), stock("D", 2))),
        "every child filled" to sheet(
            listOf(order("A", 1, """{"Units":4,"Picks":[{"Count":2}]}"""), order("B", 2, """{"Units":1,"Picks":[{"Count":1}]}""")),
            listOf(stock("A", 1, """{"Level":3}"""), stock("B", 2, """{"Level":1}"""))),
    )

    @Test
    fun a12AndTheCheckerAdmitTheModel() {
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(model), "A12 admits every rule")
        assertEquals(emptyList<String>(), check(ModelSource(model)).findings.filter { it.justifiesRejection }.map { it.code })
    }

    @Test
    fun allThreeEnginesAgreeOnEveryDocument() {
        DocumentDifferential.assertAgree(model, documents)
    }

    @Test
    fun allThreeEnginesAgreeOnAComputationReadingBelowAJoinedGroup() {
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(computed), "A12 admits the computation")
        DocumentDifferential.assertAgree(computed, documents)
    }

    /**
     * SKU C has no stock, so its children read at the `-5` stock row with the starred level at `[0]`, and SKU D has no
     * order. A read below a side names that side's index field in the fill-to-fix set, the starred read as a plain
     * read does: `Stock[-5]/Sku` for C's bins, and `Orders[-5]/Sku` for D's lines.
     */
    @Test
    fun anUnmatchedSidesChildrenAreNamedAtItsUnknownRow() {
        val messages = DocumentDifferential.kernel(model, documents.getValue("an order without stock and stock without an order"),
            dynamic = true).messages
        fun probe(code: String, row: String) = messages.single { it.code == code && it.pointer.contains("Stock[$row]") }
            .let { "ref=${it.referenced}|fill=${it.fillToFix}" }
        assertEquals(
            mapOf(
                "BINS_UNFILLED" to "ref=[/Fulfilment[1]/Orders[2]/Qty, /Fulfilment[1]/Orders[2]/Sku, " +
                    "/Fulfilment[1]/Stock[-5]/Bins[0]/Level, /Fulfilment[1]/Stock[-5]/Note, /Fulfilment[1]/Stock[-5]/Sku]" +
                    "|fill=[/Fulfilment[1]/Stock[-5]/Bins[0]/Level, /Fulfilment[1]/Stock[-5]/Note, /Fulfilment[1]/Stock[-5]/Sku]",
                // No plain read touches Orders here, so its index enters only through the starred lines.
                "ONLY_LINES_UNCOUNTED" to "ref=[/Fulfilment[1]/Orders[-5]/Lines[0]/Units, /Fulfilment[1]/Orders[-5]/Sku, " +
                    "/Fulfilment[1]/Stock[2]/Note, /Fulfilment[1]/Stock[2]/OnHand, /Fulfilment[1]/Stock[2]/Sku]" +
                    "|fill=[/Fulfilment[1]/Orders[-5]/Lines[0]/Units, /Fulfilment[1]/Orders[-5]/Sku, " +
                    "/Fulfilment[1]/Stock[2]/Note, /Fulfilment[1]/Stock[2]/Sku]",
            ),
            mapOf("BINS_UNFILLED" to probe("BINS_UNFILLED", "-5"), "ONLY_LINES_UNCOUNTED" to probe("ONLY_LINES_UNCOUNTED", "2")),
        )
    }

    /**
     * Which SKU each rule fires for on the dynamic Kernel, by the error row: `Stock[n]`, or `-5` where the SKU has
     * no stock. A common-parent domain would count A's lines and bins for B too and move every firing for B.
     */
    @Test
    fun theKernelReadsEachSidesChildrenAtItsJoinedRow() {
        val fired = documents.mapValues { (_, json) ->
            DocumentDifferential.kernel(model, json, dynamic = true).messages
                .map { it.code + "@" + it.pointer.substringAfter("Stock[").substringBefore("]") }.sorted()
        }
        assertEquals(linkedMapOf(
            "every SKU on both sides, stock in the opposite order" to listOf(
                "BINS_EMPTY@1", "BINS_STOCKED@2", "BINS_UNFILLED@1", "FIRST_BIN_STOCKED@2",
                "LINES_UNCOUNTED@1", "ONLY_LINES_UNCOUNTED@1", "ONLY_PICKS_UNCOUNTED@1"),
            "an order without stock and stock without an order" to listOf(
                "BINS_EMPTY@-5", "BINS_STOCKED@1", "BINS_UNFILLED@-5", "FIRST_BIN_STOCKED@1",
                "ONLY_LINES_UNCOUNTED@2", "ONLY_PICKS_UNCOUNTED@1", "ONLY_PICKS_UNCOUNTED@2"),
            "every child filled" to listOf("BINS_STOCKED@1", "BINS_STOCKED@2", "FIRST_BIN_STOCKED@1", "FIRST_BIN_STOCKED@2"),
        ), fired)
    }
}
