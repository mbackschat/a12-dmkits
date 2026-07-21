package io.github.mbackschat.a12.dm.adapter.perf

import io.github.mbackschat.a12.dm.adapter.laws.TypedRules
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The perf differential compares the interpreter's report-all API at the kernel's delta-only boundary. */
class PerfComputeGranularityTest {

    @Test
    fun unchangedStringComputationIsProjectedOutBeforeComparison() {
        val base = DocumentModelDraft.named("perf-compute-granularity").locale("en_US")
            .rootGroup("Case") { group -> group.stringField("Status") {} }
            .build()
            .toJson()
        val bound = BoundModel.ofJson(base)
        val computation = bound.computation(bound.str(STATUS))
            .name("KeepStatus")
            .calc(Dsl.text("V1").expression())
            .message(Dsl.en("status"))
            .build()
        val modelJson = TypedRules.inject(base, computation)
        val scenario = PerfScenario(
            name = "unchanged-string-compute",
            kernelModel = modelJson,
            interpModel = ModelLoader.load(modelJson),
            op = PerfOp.COMPUTE,
        ) { sink, _, _ ->
            sink.group(ROOT, 1)
            sink.field(STATUS, "V1", 1, 1)
        }

        assertEquals(emptyList<String>(), InterpreterPerfEngine.build(scenario).instance(1).outcome().signatures)
    }

    private companion object {
        const val ROOT = "/Case"
        const val STATUS = "$ROOT/Status"
    }
}
