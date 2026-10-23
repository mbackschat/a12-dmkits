package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.KernelConsistencyOracle
import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.core.ReferenceResolvers
import io.github.mbackschat.a12.dm.core.Severity as CoreSeverity
import io.github.mbackschat.a12.dm.interpreter.PreparedModel
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter
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
                .group("Bins") { b -> b.repeatable(2).numberField("Level") { it.fractionDigits(0) } } }
            .group("Returns") { r -> r.repeatable(2)
                .group("Items") { i -> i.repeatable(2).numberField("Units") { it.fractionDigits(0) } } } }
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

    /**
     * SKU A's stock fills every bin and its order counts no line, SKU B the reverse, so only a joined-row read fires
     * these probes for A alone: pooled over the common parent, A's count would see B's lines and B's empty bins.
     */
    private val probes = documents + ("full bins beside an uncounted order" to sheet(
        listOf(order("A", 1), order("B", 1, """{"Units":2}""")),
        listOf(stock("A", 4, """{"Level":1},{"Level":2}"""), stock("B", 1))))

    /**
     * The quantifiers that read a star's declared range, at matched and unmatched sides: they reach the same joined
     * row, and an unmatched side's `-5` row reads as its declared capacity with nothing filled (cold review 2026-10-21).
     * The group quantifiers take no star (`AllGroupsFilled(Stock/Bins*)` draws `MVK_NO_WILDCARDS_G_ALLOWED`).
     */
    @Test
    fun aDeclaredRangeQuantifierReadsTheJoinedRowToo() {
        val quantified = bound.addAll(listOf(
            rule("BINS_ALL_LEVELLED", ordered.and(Dsl.allFieldsFilled(levels))),
            rule("BINS_NOT_ALL_LEVELLED", ordered.and(Dsl.notAllFieldsFilled(levels))),
        ), emptyList()).toJson()
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(quantified), "A12 admits every rule")
        assertEquals(linkedMapOf(
            "every SKU on both sides, stock in the opposite order" to listOf("BINS_NOT_ALL_LEVELLED@1", "BINS_NOT_ALL_LEVELLED@2"),
            // The SKU without stock reads its bins at the `-5` row: declared capacity with nothing filled.
            "an order without stock and stock without an order" to listOf("BINS_NOT_ALL_LEVELLED@-5", "BINS_NOT_ALL_LEVELLED@1"),
            "every child filled" to listOf("BINS_NOT_ALL_LEVELLED@1", "BINS_NOT_ALL_LEVELLED@2"),
            "full bins beside an uncounted order" to listOf("BINS_ALL_LEVELLED@1", "BINS_NOT_ALL_LEVELLED@2"),
        ), firedByStockRow(quantified, probes))
        DocumentDifferential.assertAgree(quantified, probes)
    }

    /**
     * A rule whose only reads of both sides are stars: the error field is a bin's level, so nothing is read plainly
     * (cold review 2026-10-21). A12 still joins the two groups: it fires for SKU A alone, whose own order counts no
     * line, where a pooled read of `Orders` would see B's line.
     */
    @Test
    fun aRuleReadingBothSidesOnlyThroughStars() {
        val starOnly = bound.addAll(listOf(
            bound.rule(fulfilment).name("star_only_rule")
                .errorField(bound.field("/Fulfilment/Stock/Bins/Level"))
                .`when`(Dsl.atLeastOneFieldFilled(levels).and(Dsl.numberOfFilledFields(units).eq(0)))
                .code("STAR_ONLY").severity(Severity.ERROR).message(Dsl.en("Check the stock of this SKU.")).build(),
        ), emptyList()).toJson()
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(starOnly), "A12 admits the rule")
        assertEquals(probes.mapValues { (name, _) ->
            if (name == "full bins beside an uncounted order") listOf("STAR_ONLY@1") else emptyList()
        }, firedByStockRow(starOnly, probes))
        DocumentDifferential.assertAgree(starOnly, probes)
    }

    /**
     * A star that also binds an unstarred repeatable level, below a joined group or below the common parent: what A12
     * says of each placement, and what the interpreter does with the ones A12 admits (cold review 2026-10-21).
     *
     * A12 refuses every placement whose declaration does not sit on that level. Where it does (R2, C2), the unindexed
     * level directly below the joined `Orders` is iterated inside each joined row (SPEC-2026-10-23-03, measured in
     * [GroupTargetRuntimeDiffTest]), and the star bound at that nested level is what the interpreter declines: the
     * audit at preparation, the computation at its first joined row. No runtime of that binding is measured. C4, the
     * starred control, is evaluated: [aFullyStarredThirdGroupIsReadFromTheCommonParent].
     */
    @Test
    fun whereAStarBindsAnUnstarredLevelBesideTheJoin() {
        val picksBelowLines = Dsl.sum(bound.flatten("/Fulfilment/Orders/Lines/Picks*/Count"))
        fun probe(errorField: String, condition: Condition) = bound.rule(fulfilment).name("probe_rule")
            .errorField(bound.field(errorField)).`when`(condition)
            .code("PROBE").severity(Severity.ERROR).message(Dsl.en("Check the picks.")).build()
        fun computation(target: String, value: NumberExpr) =
            bound.computation(bound.num(target)).name("ProbeComputation").calc(value)
                .message(Dsl.en("Probe computed.")).build()
        val computing = { target: String, value: NumberExpr -> emptyList<Rule>() to listOf(computation(target, value)) }
        val shapes = linkedMapOf(
            "R1 rule, error field on the other side" to (listOf(probe("/Fulfilment/Stock/Note",
                stocked.and(picksBelowLines.gt(0)))) to emptyList()),
            "R2 rule, error field on the unstarred level" to (listOf(probe("/Fulfilment/Orders/Lines/Units",
                stocked.and(bound.num("/Fulfilment/Orders/Lines/Units").filled()).and(picksBelowLines.gt(0)))) to emptyList()),
            "C1 computation into the other side" to computing("/Fulfilment/Stock/Reserved", qty.asNumber().plus(picksBelowLines)),
            "C2 computation into the unstarred level" to
                computing("/Fulfilment/Orders/Lines/Units", onHand.asNumber().plus(picksBelowLines)),
            "C3 computation, third group unstarred" to computing("/Fulfilment/Stock/Reserved",
                qty.asNumber().plus(Dsl.sum(bound.flatten("/Fulfilment/Returns/Items*/Units")))),
            "C4 computation, third group starred" to computing("/Fulfilment/Stock/Reserved",
                qty.asNumber().plus(Dsl.sum(bound.flatten("/Fulfilment/Returns*/Items*/Units")))),
        )
        val observed = shapes.mapValues { (_, programs) ->
            val json = ModelWriter.addAll(skeleton, programs.first, programs.second)
            val a12 = KernelConsistencyOracle().checkConsistency(json, ReferenceResolvers.selfContained())
                .filter { it.severity() == CoreSeverity.ERROR }.mapNotNull { CODE.find(it.message())?.groupValues?.get(1) }
                .toSortedSet()
            val checker = check(ModelSource(json)).findings.filter { it.justifiesRejection }.map { it.code }.toSortedSet()
            if (a12.isNotEmpty()) return@mapValues "refused $a12; checker $checker"
            val prepared = PreparedModel.loadExpandedJson(json)
            val interpreter = prepared.createInterpreter()
            val audit = interpreter.supportReport().unsupported.map { it.reason }
            val runtime = interpreter.compute(prepared.documents.readDocument(documents.values.first())).unsupported.map { it.reason }
            "admitted; checker $checker; audit=$audit; compute=$runtime"
        }
        val prepared = "[an aggregate over /Fulfilment/Orders/Lines/Picks/Count bound below the parallel iteration's " +
            "common parent /Fulfilment is not modeled]"
        val computed = "[an aggregate of /Fulfilment/Orders/Lines/Picks bound below the joined group /Fulfilment/Orders " +
            "is not modeled]"
        assertEquals(linkedMapOf(
            "R1 rule, error field on the other side" to "refused [MVK_NO_WILDCARD]; checker [MVK_NO_WILDCARD]",
            "R2 rule, error field on the unstarred level" to "admitted; checker []; audit=$prepared; compute=[]",
            "C1 computation into the other side" to "refused [MVK_NO_WILDCARD]; checker [MVK_NO_WILDCARD]",
            "C2 computation into the unstarred level" to "admitted; checker []; audit=$prepared; compute=$computed",
            "C3 computation, third group unstarred" to "refused [MVK_NO_WILDCARD]; checker [MVK_NO_WILDCARD]",
            "C4 computation, third group starred" to "admitted; checker []; audit=[]; compute=[]",
        ), observed)
    }

    /** A star over every level of a third group, beside the join, sums that group's rows under the common parent. */
    @Test
    fun aFullyStarredThirdGroupIsReadFromTheCommonParent() {
        val returned = bound.addAll(emptyList(), listOf(
            bound.computation(bound.num("/Fulfilment/Stock/Reserved")).name("ReservedComputation")
                .calc(qty.asNumber().plus(Dsl.sum(bound.flatten("/Fulfilment/Returns*/Items*/Units"))))
                .message(Dsl.en("Reservation computed.")).build())).toJson()
        val withReturns = documents.mapValues { (_, json) ->
            json.removeSuffix("}}") + ""","Returns":[{"Items":[{"Units":2},{"Units":3}]},{"Items":[{},{"Units":4}]}]}}"""
        }
        DocumentDifferential.assertAgree(returned, withReturns)
    }

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
        assertEquals(linkedMapOf(
            "every SKU on both sides, stock in the opposite order" to listOf(
                "BINS_EMPTY@1", "BINS_STOCKED@2", "BINS_UNFILLED@1", "FIRST_BIN_STOCKED@2",
                "LINES_UNCOUNTED@1", "ONLY_LINES_UNCOUNTED@1", "ONLY_PICKS_UNCOUNTED@1"),
            "an order without stock and stock without an order" to listOf(
                "BINS_EMPTY@-5", "BINS_STOCKED@1", "BINS_UNFILLED@-5", "FIRST_BIN_STOCKED@1",
                "ONLY_LINES_UNCOUNTED@2", "ONLY_PICKS_UNCOUNTED@1", "ONLY_PICKS_UNCOUNTED@2"),
            "every child filled" to listOf("BINS_STOCKED@1", "BINS_STOCKED@2", "FIRST_BIN_STOCKED@1", "FIRST_BIN_STOCKED@2"),
        ), firedByStockRow(model, documents))
    }

    /** Each document's dynamic-Kernel firings as `code@row`, the `Stock` row read from the message pointer. */
    private fun firedByStockRow(model: String, sheets: Map<String, String>) = sheets.mapValues { (_, json) ->
        DocumentDifferential.kernel(model, json, dynamic = true).messages
            .map { it.code + "@" + it.pointer.substringAfter("Stock[").substringBefore("]") }.sorted()
    }

    private companion object {
        val CODE = Regex("""\[(MVK_\w+)]""")
    }
}
