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
 * Perf gate — staging an index field's `sValue` default stays **linear** in the cells it stages
 * (INTERPRETER-PERFORMANCE-RESULTS 2026-10-21; the staging itself is KERNEL-FINDINGS KF574's mechanism). Each of N orders has one
 * line whose enumeration index is absent, so full validation stages N defaults into a copy of the document on
 * every call. The copy is O(placements) by construction; what this lane holds is that each placement is matched
 * against the staged cells by lookup, not by a scan of them, which would make the copy O(placements × cells).
 *
 * The rule reads the staged value, so a default that failed to reach evaluation would fire it on every line.
 */
@Tag("perf")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IndexDefaultStagingPerfTest {

    private val skeleton = DocumentModelDraft.named("dispatch").locale("en_US")
        .rootGroup("Dispatch") { d ->
            d.group("Orders") { o -> o.repeatable(MAX)
                .stringField("Reference") { }
                .group("Lines") { l -> l.repeatable(5).indexField("ServiceLevel")
                    .enumField("ServiceLevel") { e -> e.value("STD").value("EXPRESS").sValue("STD") }
                    .numberField("Qty") { it.fractionDigits(0) } } }
        }.build().toJson()

    private val bound = BoundModel.ofJson(skeleton)
    private val qty = bound.num("$LINES/Qty")

    private val model = bound.addAll(
        listOf(
            bound.rule(bound.group(LINES)).name("express_line").errorField(qty)
                .`when`(qty.ge(0).and(bound.enumOf("$LINES/ServiceLevel").`is`("EXPRESS")))
                .code("EXPRESS_LINE").severity(Severity.ERROR).message(Dsl.en("This line ships express.")).build(),
        ),
        emptyList(),
    ).toJson()

    private val scenario = PerfScenario("index-default-staging", model, ModelLoader.load(model)) { sink, n, _ ->
        sink.group("/Dispatch", 1)
        for (o in 1..n) {
            sink.group(ORDERS, 1, o)
            sink.field("$ORDERS/Reference", "O$o", 1, o, 1)
            sink.group(LINES, 1, o, 1)
            sink.field("$LINES/Qty", "1", 1, o, 1, 1)
        }
    }

    @BeforeAll
    fun assertExactTriEngineEligibility() {
        PerfHarness.assertSemanticAgreement(scenario, listOf(N, N * 4), withJava = true)
    }

    @Test
    fun indexDefaultStagingScalesSubQuadratically() {
        PerfHarness.assertSubQuadraticLane(
            label = "index-default staging",
            small = N,
            identityAt = { n -> PerfHarness.measurementLabel(scenario, n) },
            culprit = "the staged-cell copy matching each placement by a scan of the staged cells",
            unit = "orders",
        ) { n -> PerfHarness.timeInterpreterCpu(scenario, n, warmup = 5, iters = 7) }
    }

    private companion object {
        const val N = 1000
        const val MAX = 100_000
        const val ORDERS = "/Dispatch/Orders"
        const val LINES = "$ORDERS/Lines"
    }
}
