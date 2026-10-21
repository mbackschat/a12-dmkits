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
 * Perf gate — a keyed `$` correlation over a REQUIRED column stays **linear** (INTERPRETER-PERFORMANCE-RESULTS 2026-10-21, the
 * correlation-plan gate). A required column can hold an unavailable empty cell, which forces the nested tally, so
 * the interpreter asks once per scope whether one exists. `OuterCorrelationPerfTest` cannot see that question: its
 * column is not required, and the gate is skipped. Here every cell is filled and every value distinct, so the
 * answer is "none", no witness ends a scan early, and the keyed join must carry every row. Asked once per row
 * instead of once per scope, the question alone is O(rows) per row.
 *
 * `oneUnavailableCellKeepsTheKernelsClass` holds the other answer. One claim leaves its required reference empty,
 * so its cell is unavailable, and the scope takes the nested tally that the indexed ones cannot replace
 * (KERNEL-FINDINGS KF552): O(rows) per row in the interpreter and in both kernel strategies alike, measured
 * 2026-10-21 at 250 to 2000 rows (INTERPRETER-PERFORMANCE-RESULTS). A sub-quadratic floor would bar A12's own
 * cost, so this shape is held to the kernel's complexity class instead: the interpreter may not fall behind it.
 */
@Tag("perf")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RequiredColumnCorrelationPerfTest {

    private val skeleton = DocumentModelDraft.named("claims-register").locale("en_US")
        .rootGroup("Register") { r ->
            r.group("Claims") { c -> c.repeatable(MAX).numberField("Reference") { it.fractionDigits(0).required() } }
        }.build().toJson()

    private val bound = BoundModel.ofJson(skeleton)
    private val claims = bound.group(CLAIMS)
    private val reference = bound.num(REFERENCE)

    // A claim whose reference another claim repeats: FieldFilled(Reference) And
    // AtLeastOneFieldFilled(Claims* / Reference Having another row And [Reference] == [$Reference]).
    private val model = bound.addAll(
        listOf(
            bound.rule(claims).name("duplicate_reference").errorField(bound.field(REFERENCE))
                .`when`(reference.filled().and(Dsl.atLeastOneFieldFilled(bound.flatten("$CLAIMS*/Reference").having(
                    Dsl.currentRepetition(claims).ne(Dsl.currentRepetition(claims, true))
                        .and(reference.eq(reference.outer()))))))
                .code("DUPLICATE_REFERENCE").severity(Severity.ERROR).message(Dsl.en("Another claim has this reference."))
                .build(),
        ),
        emptyList(),
    ).toJson()

    private val scenario = PerfScenario("required-column-correlation", model, ModelLoader.load(model)) { sink, n, _ ->
        sink.group("/Register", 1)
        for (r in 1..n) {
            sink.group(CLAIMS, 1, r)
            sink.field(REFERENCE, r.toString(), 1, r, 1)
        }
    }

    private val oneUnavailableScenario = PerfScenario("required-column-correlation-one-empty", model, ModelLoader.load(model)) { sink, n, _ ->
        sink.group("/Register", 1)
        for (r in 1..n) {
            sink.group(CLAIMS, 1, r)
            if (r == 1) sink.emptyField(REFERENCE, 1, r, 1) else sink.field(REFERENCE, r.toString(), 1, r, 1)
        }
    }

    @BeforeAll
    fun assertExactTriEngineEligibility() {
        PerfHarness.assertSemanticAgreement(scenario, listOf(N, N * 4), withJava = true)
        PerfHarness.assertSemanticAgreement(oneUnavailableScenario, listOf(N_NESTED, N_NESTED * 4), withJava = true)
    }

    @Test
    fun oneUnavailableCellKeepsTheKernelsClass() {
        val interpreter = InterpreterPerfEngine.build(oneUnavailableScenario)
        val kernel = KernelJavaPerfEngine.build(oneUnavailableScenario)
        fun medianMs(engine: ReusableEngine, n: Int): Double {
            val instance = engine.instance(n)
            return PerfHarness.measure(warmup = 3, iters = 3) { instance.run() }.medianMs
        }
        PerfHarness.assertCrossEngineClassHolds(
            label = "required-column correlation, one unavailable cell",
            ceiling = PerfBounds.CROSS_ENGINE_CEILING,
        ) {
            PerfHarness.RatioGrowth(
                iSmall = medianMs(interpreter, N_NESTED),
                kSmall = medianMs(kernel, N_NESTED),
                iLarge = medianMs(interpreter, N_NESTED * 4),
                kLarge = medianMs(kernel, N_NESTED * 4),
            )
        }
    }

    @Test
    fun requiredColumnCorrelationScalesSubQuadratically() {
        PerfHarness.assertSubQuadraticLane(
            label = "required-column correlation",
            small = N,
            identityAt = { n -> PerfHarness.measurementLabel(scenario, n) },
            culprit = "the unavailable-empty question asked per row instead of once per scope",
            unit = "rows",
        ) { n -> PerfHarness.timeInterpreterCpu(scenario, n, warmup = 5, iters = 7) }
    }

    private companion object {
        const val N = 2000
        /** The nested tally is quadratic in every engine, so its scales stay small. */
        const val N_NESTED = 250
        const val MAX = 100_000
        const val CLAIMS = "/Register/Claims"
        const val REFERENCE = "$CLAIMS/Reference"
    }
}
