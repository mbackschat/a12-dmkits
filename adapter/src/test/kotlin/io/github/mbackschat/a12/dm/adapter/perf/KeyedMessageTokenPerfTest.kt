package io.github.mbackschat.a12.dm.adapter.perf

import io.github.mbackschat.a12.dm.adapter.laws.DocumentDifferential
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Perf gate — rendering keyed message tokens stays **linear** in the fired messages (INTERPRETER-PERFORMANCE-RESULTS 2026-10-21).
 * Every other lane renders the template `x` or nothing, so no lane renders a token at all. Here every line fires,
 * and its message reads a keyed price twice, as a value and as a row number. Each `For` token is re-read from its
 * text per message, so its cost is a constant per message; what this lane holds is that resolving it stays a
 * lookup rather than a pass over the document, which would cost O(document) per fired message.
 */
@Tag("perf")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KeyedMessageTokenPerfTest {

    private val skeleton = DocumentModelDraft.named("price-sheet").locale("en_US")
        .rootGroup("Sheet") { s -> s
            .group("Prices") { p -> p.repeatable(5).indexField("Code")
                .stringField("Code") { }
                .numberField("Amount") { it.fractionDigits(0) } }
            .group("Lines") { l -> l.repeatable(MAX).numberField("Qty") { it.fractionDigits(0) } } }
        .build().toJson()

    private val bound = BoundModel.ofJson(skeleton)
    private val qty = bound.num(QTY)
    private val amount = bound.num("$PRICES/Amount")
    private val standard = bound.flattenGroup(PRICES).forIndex("STD")

    private val model = bound.addAll(
        listOf(
            bound.rule(bound.group(LINES)).name("priced_line").errorField(bound.field(QTY))
                // A row-number token needs its selected row filled whenever the rule fires, or A12 refuses the text.
                .`when`(qty.gt(0).and(standard.filled()))
                .code("PRICED_LINE").severity(Severity.ERROR)
                .message(Dsl.en("Priced at [" + Dsl.fieldValue(amount.semanticIndex("STD")) + "] from price row [" +
                    Dsl.repetitionIndex(standard) + "]."))
                .build(),
        ),
        emptyList(),
    ).toJson()

    private val scenario = PerfScenario("keyed-message-token", model, ModelLoader.load(model)) { sink, n, _ ->
        sink.group("/Sheet", 1)
        for ((row, code) in listOf("PROMO", "STD").withIndex()) {
            sink.group(PRICES, 1, row + 1)
            sink.field("$PRICES/Code", code, 1, row + 1, 1)
            sink.field("$PRICES/Amount", "3", 1, row + 1, 1)
        }
        for (r in 1..n) {
            sink.group(LINES, 1, r)
            sink.field(QTY, "1", 1, r, 1)
        }
    }

    /** The timed signatures compare code, kind and pointer, not text, so the rendered tokens are compared here. */
    @BeforeAll
    fun assertExactTriEngineEligibility() {
        PerfHarness.assertSemanticAgreement(scenario, listOf(N, N * 4), withJava = true)
        DocumentDifferential.assertAgree(model, mapOf("two priced lines" to
            """{"Sheet":{"Prices":[{"Code":"PROMO","Amount":3},{"Code":"STD","Amount":3}],"Lines":[{"Qty":1},{"Qty":2}]}}"""))
    }

    @Test
    fun keyedMessageTokensScaleSubQuadratically() {
        PerfHarness.assertSubQuadraticLane(
            label = "keyed message tokens",
            small = N,
            identityAt = { n -> PerfHarness.measurementLabel(scenario, n) },
            culprit = "a keyed message token resolved by a pass over the document instead of the index lookup",
            unit = "fired messages",
        ) { n -> PerfHarness.timeInterpreterCpu(scenario, n, warmup = 5, iters = 7) }
    }

    private companion object {
        const val N = 1000
        const val MAX = 100_000
        const val PRICES = "/Sheet/Prices"
        const val LINES = "/Sheet/Lines"
        const val QTY = "$LINES/Qty"
    }
}
