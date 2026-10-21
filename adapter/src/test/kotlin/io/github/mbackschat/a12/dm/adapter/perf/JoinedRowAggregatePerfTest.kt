package io.github.mbackschat.a12.dm.adapter.perf

import io.github.mbackschat.a12.dm.interpreter.gen.PlacementSink
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Perf gate — starred and keyed reads from a row of a parallel iteration stay **linear** (the
 * joined-row path of INTERPRETER-FINDINGS IF402). Orders and Stock are joined by `Sku`, N matched
 * keys, and each joined row reads the sum of every order's `Qty`, which ranges over the common parent's rows
 * (KERNEL-FINDINGS KF567), and a keyed `Prices` read. The interpreter builds one common-parent context per joined
 * row, so an aggregate re-evaluated there instead of read from the document-level cache costs O(rows) per row:
 * 4× the rows would then take ≈16× the time.
 *
 * `starOnlyJoinScalesSubQuadratically` holds the other join route: a rule that reads both sides only through stars
 * below them, so nothing is read plainly and the stars alone join Orders and Stock (KERNEL-FINDINGS KF573). Each
 * joined row then reads its own order's lines and its own stock's bins.
 *
 * No rule fires, so the lane times evaluation rather than message construction. The model repeats far
 * above 4×N, for the reason `GroupScopedFillPerfTest` gives: an over-limit row is a context error to the Kernel.
 */
@Tag("perf")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JoinedRowAggregatePerfTest {

    private val skeleton = DocumentModelDraft.named("order-fulfilment").locale("en_US")
        .rootGroup("Fulfilment") { f -> f
            .group("Orders") { o -> o.repeatable(MAX).indexField("Sku")
                .stringField("Sku") { }
                .numberField("Qty") { it.fractionDigits(0) }
                .stringField("Tariff") { }
                .group("Lines") { l -> l.repeatable(3).numberField("Units") { it.fractionDigits(0) } } }
            .group("Stock") { s -> s.repeatable(MAX).indexField("Sku")
                .stringField("Sku") { }
                .numberField("OnHand") { it.fractionDigits(0) }
                .group("Bins") { b -> b.repeatable(2).numberField("Level") { it.fractionDigits(0) } } }
            .group("Prices") { p -> p.repeatable(MAX).indexField("Code")
                .stringField("Code") { }
                .numberField("Amount") { it.fractionDigits(0) } } }
        .build().toJson()

    private val bound = BoundModel.ofJson(skeleton)
    private val qty = bound.num("$ORDERS/Qty")
    private val onHand = bound.num("$STOCK/OnHand")
    private val paired = onHand.filled().and(qty.filled())

    private fun rule(code: String, condition: Condition) =
        bound.rule(bound.group(ROOT)).name("${code.lowercase()}_rule")
            .errorField(bound.field("$STOCK/OnHand")).`when`(condition)
            .code(code).severity(Severity.ERROR).message(Dsl.en("Check the stock of this SKU.")).build()

    private val model = bound.addAll(
        listOf(
            rule("ORDERED_ABOVE_STOCK", paired.and(Dsl.sum(bound.flatten("$ORDERS*/Qty")).gt(onHand))),
            rule("TARIFF_PRICED", paired.and(
                bound.num("$PRICES/Amount").forIndex(bound.str("$ORDERS/Tariff").field()).gt(0))),
        ),
        emptyList(),
    ).toJson()

    private val def: ModelDef = ModelLoader.load(model)

    // Every line counts a unit, so a joined row's count is 1 and the rule never holds.
    private val starOnlyModel = bound.addAll(
        listOf(
            bound.rule(bound.group(ROOT)).name("binned_without_lines_rule")
                .errorField(bound.field("$STOCK/Bins/Level"))
                .`when`(Dsl.atLeastOneFieldFilled(bound.flatten("$STOCK/Bins*/Level"))
                    .and(Dsl.numberOfFilledFields(bound.flatten("$ORDERS/Lines*/Units")).eq(0)))
                .code("BINNED_WITHOUT_LINES").severity(Severity.ERROR).message(Dsl.en("Check the stock of this SKU.")).build(),
        ),
        emptyList(),
    ).toJson()

    // Every SKU on both sides; Stock and Prices in the reverse order, so a positional join would pair wrong rows.
    // OnHand exceeds any order total and every price is 0, so neither rule fires.
    private val scenario = PerfScenario("joined-row-aggregate", model, def) { sink, n, _ -> fulfilment(sink, n) }

    private val starOnlyScenario =
        PerfScenario("joined-star-only", starOnlyModel, ModelLoader.load(starOnlyModel)) { sink, n, _ -> fulfilment(sink, n) }

    private fun fulfilment(sink: PlacementSink, n: Int) {
        sink.group(ROOT, 1)
        for (r in 1..n) {
            val back = n + 1 - r
            sink.group(ORDERS, 1, r)
            sink.field("$ORDERS/Sku", "S$r", 1, r, 1)
            sink.field("$ORDERS/Qty", "1", 1, r, 1)
            sink.field("$ORDERS/Tariff", "T$r", 1, r, 1)
            sink.group("$ORDERS/Lines", 1, r, 1)
            sink.field("$ORDERS/Lines/Units", "1", 1, r, 1, 1)
            sink.group(STOCK, 1, r)
            sink.field("$STOCK/Sku", "S$back", 1, r, 1)
            sink.field("$STOCK/OnHand", (MAX * 2).toString(), 1, r, 1)
            sink.group("$STOCK/Bins", 1, r, 1)
            sink.field("$STOCK/Bins/Level", "1", 1, r, 1, 1)
            sink.group(PRICES, 1, r)
            sink.field("$PRICES/Code", "T$back", 1, r, 1)
            sink.field("$PRICES/Amount", "0", 1, r, 1)
        }
    }

    @BeforeAll
    fun assertExactTriEngineEligibility() {
        PerfHarness.assertSemanticAgreement(scenario, listOf(N, N * 4), withJava = true)
        PerfHarness.assertSemanticAgreement(starOnlyScenario, listOf(N, N * 4), withJava = true)
    }

    @Test
    fun starOnlyJoinScalesSubQuadratically() {
        PerfHarness.assertSubQuadraticLane(
            label = "star-only join",
            small = N,
            identityAt = { n -> PerfHarness.measurementLabel(starOnlyScenario, n) },
            culprit = "a star-only join collecting its partners, or reading a side's children, by a pass over every row",
            clock = "wall",
            unit = "joined rows",
        ) { n -> PerfHarness.timeInterpreter(starOnlyScenario, n, warmup = 5, iters = 5) }
    }

    @Test
    fun joinedRowAggregateScalesSubQuadratically() {
        PerfHarness.assertSubQuadraticLane(
            label = "joined-row aggregate",
            small = N,
            identityAt = { n -> PerfHarness.measurementLabel(scenario, n) },
            culprit = "an aggregate or keyed read re-evaluated per joined row instead of read from the document cache",
            clock = "wall",
            unit = "joined rows",
        ) { n -> PerfHarness.timeInterpreter(scenario, n, warmup = 5, iters = 5) }
    }

    private companion object {
        const val N = 1000
        const val MAX = 100_000
        const val ROOT = "/Fulfilment"
        const val ORDERS = "$ROOT/Orders"
        const val STOCK = "$ROOT/Stock"
        const val PRICES = "$ROOT/Prices"
    }
}
