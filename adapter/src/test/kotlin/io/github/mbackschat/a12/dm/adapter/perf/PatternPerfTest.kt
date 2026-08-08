package io.github.mbackschat.a12.dm.adapter.perf

import io.github.mbackschat.a12.dm.adapter.laws.RuntimeLaws
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/** IF164 compile-once guard: one dialect-sensitive match per row stays linear and retains its first-row firing. */
@Tag("perf")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PatternPerfTest {
    private val base = DocumentModelDraft.named("pattern-perf").locale("en_US")
        .rootGroup("Order") { order ->
            order.group("Items") { items -> items.repeatable(MAX).stringField("Code") { } }
        }.build().toJson()
    private val bound = BoundModel.ofJson(base)
    private val condition = DslRenderer().render(bound.str(CODE).matches("(?i:abc)"))
    private val kernelModel = RuntimeLaws.withRule(base, "/Order", "Pattern", "../Items/Code", "PAT", condition)
    private val model = ModelLoader.load(kernelModel)
    private val scenario = PerfScenario("pattern-per-row", kernelModel, model) { sink, n, _ ->
        sink.group("/Order", 1)
        for (row in 1..n) {
            sink.group("/Order/Items", 1, row)
            sink.field(CODE, if (row == 1) "ABC" else "xyz", 1, row, 1)
        }
    }

    @BeforeAll
    fun exactDocumentsAgreeBeforeTiming() {
        PerfHarness.assertSemanticAgreement(scenario, listOf(N, N * 4), withJava = true)
    }

    @Test
    fun patternHeavyValidationStaysSubQuadratic() {
        PerfHarness.assertGrowthBelowCatastropheFloor(
            "pattern-heavy validation", "the distinct-pattern cache or the per-row match path",
        ) {
            PerfHarness.pairedGrowth(N, N * 4) { rows ->
                PerfHarness.timeInterpreter(scenario, rows, warmup = 2, iters = 3).medianMs
            }
        }
    }

    private companion object {
        const val N = 2_000
        const val MAX = 20_000
        const val CODE = "/Order/Items/Code"
    }
}
