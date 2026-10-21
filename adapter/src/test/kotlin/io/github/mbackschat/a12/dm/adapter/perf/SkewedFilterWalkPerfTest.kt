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
 * Perf gate — a filtered star bound to a row stays **linear** in the stored rows when the nesting is skewed
 * (INTERPRETER-FINDINGS IF413). One line carries N parts and each other line one, so the
 * document stores about 2N parts, and each line sums its own filtered parts. A12 sizes every line's walk by the
 * widest line (KERNEL-FINDINGS KF528), so each line's grid spans N coordinates although it stores one. Every
 * shipped nested scenario fans out uniformly, where that widening costs nothing. Here a walk that builds or steps
 * through each missing coordinate costs O(N) per line, and the memory arms see a walk that keeps them.
 */
@Tag("perf")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SkewedFilterWalkPerfTest {

    private val skeleton = DocumentModelDraft.named("assembly").locale("en_US")
        .rootGroup("Assembly") { a ->
            a.group("Lines") { l -> l.repeatable(MAX)
                .numberField("Budget") { it.fractionDigits(0) }
                .group("Parts") { p -> p.repeatable(MAX).numberField("Cost") { it.fractionDigits(0) } } }
        }.build().toJson()

    private val bound = BoundModel.ofJson(skeleton)
    private val budget = bound.num(BUDGET)
    private val cost = bound.num(COST)

    // Each part costs 1 and each budget exceeds any line's total, so nothing fires.
    private val model = bound.addAll(
        listOf(
            bound.rule(bound.group(LINES)).name("costed_parts_over_budget").errorField(bound.field(BUDGET))
                .`when`(budget.filled().and(Dsl.sum(bound.flatten("$LINES/Parts*/Cost").having(cost.gt(0))).gt(budget)))
                .code("COSTED_PARTS_OVER_BUDGET").severity(Severity.ERROR).message(Dsl.en("Parts exceed the budget."))
                .build(),
        ),
        emptyList(),
    ).toJson()

    private val scenario = PerfScenario("skewed-filter-walk", model, ModelLoader.load(model)) { sink, n, _ ->
        sink.group("/Assembly", 1)
        for (line in 1..n) {
            sink.group(LINES, 1, line)
            sink.field(BUDGET, (MAX * 2).toString(), 1, line, 1)
            for (part in 1..(if (line == 1) n else 1)) {
                sink.group(PARTS, 1, line, part)
                sink.field(COST, "1", 1, line, part, 1)
            }
        }
    }

    @BeforeAll
    fun assertExactTriEngineEligibility() {
        PerfHarness.assertSemanticAgreement(scenario, listOf(N, N * 4), withJava = true)
    }

    @Test
    fun skewedFilterWalkScalesSubQuadratically() {
        PerfHarness.assertSubQuadraticLane(
            label = "skewed filter walk",
            small = N,
            identityAt = { n -> PerfHarness.measurementLabel(scenario, n) },
            culprit = "a filter walk building, keeping or stepping through every missing coordinate of its widened grid",
            unit = "lines",
        ) { n -> PerfHarness.timeInterpreterCpu(scenario, n, warmup = 5, iters = 7) }
    }

    private companion object {
        const val N = 1000
        const val MAX = 100_000
        const val LINES = "/Assembly/Lines"
        const val BUDGET = "$LINES/Budget"
        const val PARTS = "$LINES/Parts"
        const val COST = "$PARTS/Cost"
    }
}
