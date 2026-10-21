package io.github.mbackschat.a12.dm.adapter.perf

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
 * Perf gate — the two unavailable-empty walks no gate skips stay **linear** over a mostly empty column
 * (INTERPRETER-PERFORMANCE-RESULTS 2026-10-21). A fill quantifier over a group scope asks of every empty cell whether it is unavailable
 * (`Interpreter.availableCells`), and a starred value-list quantifier collects the unavailable rows of its column
 * first. `GroupScopedFillPerfTest` fills every cell, so neither walk meets an empty one there. Here only one item
 * in ten has a code; both rules read the column once per call, so each walk is O(items), and a walk repeated per
 * empty cell is O(items²).
 */
@Tag("perf")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UnavailableEmptyWalkPerfTest {

    private val skeleton = DocumentModelDraft.named("parcel").locale("en_US")
        .rootGroup("Parcel") { p -> p
            .stringField("Carrier") { }
            .group("Items") { i -> i.repeatable(MAX).stringField("Code") { } } }
        .build().toJson()

    private val bound = BoundModel.ofJson(skeleton)
    private val carrier = bound.str(CARRIER)
    private val codes = bound.flatten("$ITEMS*/Code")

    private fun rule(code: String, condition: io.github.mbackschat.a12.dm.rulekit.dsl.Condition) =
        bound.rule(bound.group("/Parcel")).name("${code.lowercase()}_rule").errorField(bound.field(CARRIER))
            .`when`(carrier.filled().and(condition))
            .code(code).severity(Severity.ERROR).message(Dsl.en("Check the parcel items.")).build()

    // Codes are C<r> on every tenth item, so both conditions hold false and nothing fires.
    private val model = bound.addAll(
        listOf(
            rule("NO_ITEM_CODED", Dsl.noFieldFilled(bound.flattenGroup("$ITEMS*"))),
            rule("RESTRICTED_CODE", Dsl.atLeastOneFieldValueIncludedInValueList(listOf("X1", "X2"), codes)),
        ),
        emptyList(),
    ).toJson()

    private val scenario = PerfScenario("unavailable-empty-walk", model, ModelLoader.load(model)) { sink, n, _ ->
        sink.group("/Parcel", 1)
        sink.field(CARRIER, "Road", 1, 1)
        for (r in 1..n) {
            sink.group(ITEMS, 1, r)
            if (r % 10 == 0) sink.field(CODE, "C$r", 1, r, 1) else sink.emptyField(CODE, 1, r, 1)
        }
    }

    @BeforeAll
    fun assertExactTriEngineEligibility() {
        PerfHarness.assertSemanticAgreement(scenario, listOf(N, N * 4), withJava = true)
    }

    @Test
    fun unavailableEmptyWalksScaleSubQuadratically() {
        PerfHarness.assertSubQuadraticLane(
            label = "unavailable-empty walks",
            small = N,
            identityAt = { n -> PerfHarness.measurementLabel(scenario, n) },
            culprit = "an unavailable-empty walk repeated for every empty cell instead of once per call",
            unit = "items",
            batch = BATCH,
        ) { n -> PerfHarness.timeInterpreterCpu(scenario, n, warmup = 5, iters = 7, batch = BATCH) }
    }

    private companion object {
        const val N = 4000
        const val MAX = 100_000
        const val BATCH = 3
        const val CARRIER = "/Parcel/Carrier"
        const val ITEMS = "/Parcel/Items"
        const val CODE = "$ITEMS/Code"
    }
}
