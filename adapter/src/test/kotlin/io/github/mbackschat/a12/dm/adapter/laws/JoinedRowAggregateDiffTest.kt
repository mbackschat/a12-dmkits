package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.interpreter.PreparedModel
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Starred and keyed reads from a row of a parallel iteration, against the Kernel (a12-kernel-lean
 * SPEC-2026-10-14-01/-03/-06, KERNEL-FINDINGS KF567). Orders and Stock are joined by `Sku`, so each rule and the
 * computation run once per SKU; Prices is a third keyed group. From the joined row, a starred aggregate over a
 * joined group ranges over every row under the common parent, a filter reads each candidate row, and a keyed
 * read selects among all rows of its group whichever row the join paired — while a field key is read from the
 * joined row. The unmatched side of a join is the `-5` row. The documents scramble the row order between the
 * joined groups, so a positional join or a joined-row-scoped aggregate moves a firing.
 *
 * Own domain (an order-fulfilment sheet: orders against stock, with a price list).
 */
class JoinedRowAggregateDiffTest {

    private val skeleton = DocumentModelDraft.named("order-fulfilment").locale("en_US")
        .rootGroup("Fulfilment") { f -> f
            .stringField("HouseTariff") { }
            .group("Orders") { o -> o.repeatable(4).indexField("Sku")
                .stringField("Sku") { }
                .numberField("Qty") { it.fractionDigits(0) }
                .stringField("Tariff") { }
                .group("Lines") { l -> l.repeatable(3).indexField("Line")
                    .stringField("Line") { }
                    .numberField("Units") { it.fractionDigits(0) } } }
            .group("Stock") { s -> s.repeatable(4).indexField("Sku")
                .stringField("Sku") { }
                .numberField("OnHand") { it.fractionDigits(0) }
                .numberField("Reserved") { it.fractionDigits(0) } }
            .group("Prices") { p -> p.repeatable(4).indexField("Code")
                .stringField("Code") { }
                .numberField("Amount") { it.fractionDigits(0) } }
            .group("Catalog") { c -> c.group("Rates") { r -> r.repeatable(3).indexField("Code")
                .stringField("Code") { }
                .numberField("Amount") { it.fractionDigits(0) } } }
            .group("Archive") { a -> a.repeatable(2).group("Rates") { r -> r.repeatable(3).indexField("Code")
                .stringField("Code") { }
                .numberField("Amount") { it.fractionDigits(0) } } } }
        .build().toJson()

    private val bound = BoundModel.ofJson(skeleton)
    private val fulfilment = bound.group("/Fulfilment")
    private val qty = bound.num("/Fulfilment/Orders/Qty")
    private val tariff = bound.str("/Fulfilment/Orders/Tariff")
    private val onHand = bound.num("/Fulfilment/Stock/OnHand")
    private val amount = bound.num("/Fulfilment/Prices/Amount")
    private val prices = bound.flatten("/Fulfilment/Prices*/Amount")
    private val code = bound.str("/Fulfilment/Prices/Code")
    private val ordered = Dsl.sum(bound.flatten("/Fulfilment/Orders*/Qty"))
    private val paired = onHand.filled().and(qty.filled())

    private fun rule(code: String, condition: Condition, message: String = "Check the stock of this SKU.") =
        bound.rule(fulfilment).name("${code.lowercase()}_rule")
            .errorField(bound.field("/Fulfilment/Stock/OnHand")).`when`(condition)
            .code(code).severity(Severity.ERROR).message(Dsl.en(message)).build()

    // A keyed group read licenses message parameters under its key (SPEC-2026-10-14-03); the row and value rendered
    // must be the selected price at every joined row.
    private val standard = bound.flattenGroup("/Fulfilment/Prices").forIndex("STD")

    private val model = bound.addAll(
        listOf(
            rule("ORDERED_ABOVE_STOCK", paired.and(ordered.gt(onHand))),
            rule("TARIFF_PRICED", paired.and(amount.forIndex(tariff.field()).gt(0))),
            rule("TARIFF_UNPRICED", paired.and(amount.semanticIndex(tariff.field()).notFilled())),
            rule("STANDARD_PRICE_LISTED", paired.and(standard.filled()), "Standard price row [" +
                Dsl.repetitionIndex(standard) + "] costs [" + Dsl.fieldValue(amount.semanticIndex("STD")) + "]."),
            rule("SKU_A_BULK", paired.and(qty.forIndex("A").gt(1))),
            rule("BULK_ORDERED_ABOVE_STOCK", paired.and(
                Dsl.sum(bound.flatten("/Fulfilment/Orders*/Qty").having(qty.gt(1))).gt(onHand))),
            rule("NO_PRICE_LIST", paired.and(Dsl.noGroupFilled(bound.flattenGroup("/Fulfilment/Prices*")))),
            rule("TARIFF_REPEATS", paired.and(Dsl.fieldValuesNotUnique(bound.flatten("/Fulfilment/Orders*/Tariff")))),
            rule("CATALOG_STANDARD_PRICED", paired.and(bound.num("/Fulfilment/Catalog/Rates/Amount").forIndex("STD").gt(0))),
            rule("CATALOG_ABOVE_STOCK", paired.and(
                Dsl.sum(bound.flatten("/Fulfilment/Catalog/Rates*/Amount")).gt(onHand))),
            rule("HOUSE_TARIFF_PRICED", paired.and(Dsl.atLeastOneFieldFilled(
                prices.having(code.eq(bound.str("/Fulfilment/HouseTariff").outer()))))),
        ),
        listOf(
            bound.computation(bound.num("/Fulfilment/Stock/Reserved")).name("ReservedComputation")
                .commonPrecondition(amount.semanticIndex("STD").filled())
                .calc(qty.asNumber().plus(ordered)).message(Dsl.en("Reservation computed.")).build(),
        ),
    ).toJson()

    private fun order(sku: String, qty: Int?, tariff: String?) =
        """{"Sku":"$sku"""" + (qty?.let { ""","Qty":$it""" } ?: "") + (tariff?.let { ""","Tariff":"$it"""" } ?: "") + "}"

    private fun stock(sku: String, onHand: Int) = """{"Sku":"$sku","OnHand":$onHand,"Reserved":0}"""

    private fun price(code: String, amount: Int) = """{"Code":"$code","Amount":$amount}"""

    private fun sheet(orders: List<String>, stock: List<String>, prices: List<String>, rates: List<String>) =
        """{"Fulfilment":{"HouseTariff":"STD","Orders":[${orders.joinToString(",")}],"Stock":[${stock.joinToString(",")}],""" +
            """"Prices":[${prices.joinToString(",")}],"Catalog":{"Rates":[${rates.joinToString(",")}]}}}"""

    private val documents = linkedMapOf(
        "every SKU on both sides, stock in the opposite order" to sheet(
            listOf(order("A", 2, "STD"), order("B", 1, "PROMO")),
            listOf(stock("B", 1), stock("A", 5)),
            listOf(price("PROMO", 0), price("STD", 3)),
            listOf(price("X", 1), price("STD", 4))),
        "an order without stock and stock without an order" to sheet(
            listOf(order("A", 4, "NONE"), order("C", 1, "STD")),
            listOf(stock("A", 3), stock("D", 2)),
            listOf(price("STD", 3)),
            emptyList()),
        "no price list and a repeated tariff" to sheet(
            listOf(order("B", 3, "PROMO"), order("A", 2, "PROMO")),
            listOf(stock("A", 9), stock("B", 2)),
            emptyList(),
            listOf(price("STD", 1))),
    )

    @Test
    fun allThreeEnginesAgreeOnEveryDocument() {
        DocumentDifferential.assertAgree(model, documents)
    }

    /** The keyed price renders the row its key selects, `Prices[2]`, at both joined rows, not the joined row's own. */
    @Test
    fun aKeyedGroupMessageRendersTheSelectedRowAtEveryJoinedRow() {
        val texts = DocumentDifferential.kernel(model, documents.values.first(), dynamic = true).authoredTexts
            .filter { it.startsWith("STANDARD_PRICE_LISTED ") }
        assertEquals(listOf("Standard price row [2] costs [3].", "Standard price row [2] costs [3]."),
            texts.map { it.split(" ", limit = 3)[2] })
    }

    @Test
    fun theCheckerIsSilentOnTheModelA12Admits() {
        assertEquals(emptyList<String>(), check(ModelSource(model)).findings.filter { it.justifiesRejection }.map { it.code })
    }

    /**
     * Which SKU each rule fires for on the dynamic Kernel. A joined-row-scoped aggregate would sum one order and
     * move `ORDERED_ABOVE_STOCK`; a positional join would pair A's order with B's stock; a keyed read scoped to the
     * joined row would see only that SKU's prices or orders.
     */
    @Test
    fun theKernelReadsEveryDomainFromTheCommonParent() {
        val fired = documents.mapValues { (_, json) ->
            DocumentDifferential.kernel(model, json, dynamic = true).messages
                .map { it.code + "@" + it.pointer.substringAfter("Stock[").substringBefore("]") }.sorted()
        }
        val reserved = listOf("ReservedComputation@1", "ReservedComputation@2")
        assertEquals(linkedMapOf(
            "every SKU on both sides, stock in the opposite order" to (reserved + listOf(
                "BULK_ORDERED_ABOVE_STOCK@1", "HOUSE_TARIFF_PRICED@1", "HOUSE_TARIFF_PRICED@2", "ORDERED_ABOVE_STOCK@1",
                "SKU_A_BULK@1", "SKU_A_BULK@2", "STANDARD_PRICE_LISTED@1", "STANDARD_PRICE_LISTED@2",
                "TARIFF_PRICED@2", "CATALOG_ABOVE_STOCK@1", "CATALOG_STANDARD_PRICED@1",
                "CATALOG_STANDARD_PRICED@2")).sorted(),
            "an order without stock and stock without an order" to (reserved + listOf(
                "BULK_ORDERED_ABOVE_STOCK@1", "HOUSE_TARIFF_PRICED@1", "ORDERED_ABOVE_STOCK@1", "SKU_A_BULK@1",
                "STANDARD_PRICE_LISTED@1", "TARIFF_UNPRICED@1")).sorted(),
            "no price list and a repeated tariff" to listOf(
                "BULK_ORDERED_ABOVE_STOCK@2", "CATALOG_STANDARD_PRICED@1", "CATALOG_STANDARD_PRICED@2",
                "NO_PRICE_LIST@1", "NO_PRICE_LIST@2", "ORDERED_ABOVE_STOCK@2", "SKU_A_BULK@1", "SKU_A_BULK@2",
                "TARIFF_REPEATS@1", "TARIFF_REPEATS@2", "TARIFF_UNPRICED@1", "TARIFF_UNPRICED@2"),
        ), fired)
    }

    /**
     * A star below a joined group binds the joined order, which no measurement covers, so the interpreter refuses it
     * before reading a document rather than answer it from the common parent.
     */
    @Test
    fun anAggregateBelowAJoinedGroupIsRefused() {
        val lines = Dsl.sum(bound.flatten("/Fulfilment/Orders/Lines*/Units"))
        val declined = bound.addAll(listOf(rule("LINES_ABOVE_STOCK", paired.and(lines.gt(onHand)))), emptyList()).toJson()
        val prepared = PreparedModel.loadExpandedJson(declined)
        val report = prepared.createInterpreter()
            .validateFull(prepared.documents.readDocument(documents.values.first()))
        assertEquals(mapOf(
            "lines_above_stock_rule" to "an aggregate over /Fulfilment/Orders/Lines/Units bound below the parallel " +
                "iteration's common parent /Fulfilment is not modeled",
        ),
            report.unsupported.associate { it.path.substringAfterLast('/') to it.reason })
    }

    /**
     * What A12 admits from a joined row beyond [theKernelReadsEveryDomainFromTheCommonParent]. It admits a star below
     * a joined group, which the interpreter declines ([anAggregateBelowAJoinedGroupIsRefused]). It refuses the rest at
     * the model, so none needs an interpreter account: a filter
     * correlated with a joined group, which leaves a common-parent field as the only possible outer row
     * (`HOUSE_TARIFF_PRICED`); a keyed read below a joined group, whose own index makes two on the path; and a keyed
     * read under an unstarred repeatable ancestor, which A12 asks to star.
     */
    @Test
    fun whatA12AdmitsFromAJoinedRow() {
        val validator = RuleValidator()
        // (A12, ours): the Kernel's verdict and the portable checker's.
        fun codes(condition: String): Pair<Set<String>, Set<String>> {
            val draft = DraftRule("/Fulfilment", "ProbeRule", "../Stock/OnHand",
                "FieldFilled(Stock/OnHand) And FieldFilled(Orders/Qty) And $condition", "PROBE", "ERROR")
            val a12 = validator.check(skeleton, draft).diagnostics().map { it.code().orElse("?") }.toSet()
            val ours = check(ModelSource(validator.inject(skeleton, draft))).findings
                .filter { it.justifiesRejection }.map { it.code }.toSet()
            return a12 to ours
        }
        fun both(vararg codes: String) = codes.toSet().let { it to it }
        assertEquals(
            mapOf(
                "a star below a joined group" to both(),
                "correlated with a joined group" to both("MVK_PARALLEL_ITERATION_NOT_ALLOWED"),
                "keyed below a joined group" to both("MVK_MULTIPLE_INDEX_FIELDS"),
                "keyed under an unstarred repeatable ancestor" to both("MVK_NO_WILDCARD"),
            ),
            mapOf(
                "a star below a joined group" to codes("Sum(Orders/Lines*/Units) > [Stock/OnHand]"),
                "correlated with a joined group" to
                    codes("AtLeastOneFieldFilled(Prices*/Amount Having [Prices/Code] == [\$Orders/Tariff])"),
                "keyed below a joined group" to codes("[Orders/Lines/Units For \"L1\"] > 0"),
                "keyed under an unstarred repeatable ancestor" to codes("[Archive/Rates/Amount For \"STD\"] > 0"),
            ))
    }
}
