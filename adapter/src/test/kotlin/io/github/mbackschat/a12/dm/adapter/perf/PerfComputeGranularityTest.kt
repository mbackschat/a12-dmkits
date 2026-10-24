package io.github.mbackschat.a12.dm.adapter.perf

import io.github.mbackschat.a12.dm.adapter.laws.TypedRules
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
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
        assertEquals(emptyList<String>(), InterpreterPerfEngine.buildWithSupport(scenario).instance(1).run())
    }

    @Test
    fun clearOfAStoredEmptyStringIsSilentInEveryEngine() {
        val base = DocumentModelDraft.named("perf-compute-present-empty").locale("en_US")
            .rootGroup("Case") { group ->
                group.stringField("Left") {}
                group.stringField("Right") {}
                group.stringField("Status") {}
            }
            .build()
            .toJson()
        val bound = BoundModel.ofJson(base)
        val computation = bound.computation(bound.str(STATUS))
            .name("ClearStatus")
            .calc(bound.str(LEFT).plus(bound.str(RIGHT).asString()).expression())
            .message(Dsl.en("status"))
            .build()
        val modelJson = TypedRules.inject(base, computation)
        val scenario = PerfScenario(
            name = "present-empty-string-compute",
            kernelModel = modelJson,
            interpModel = ModelLoader.load(modelJson),
            op = PerfOp.COMPUTE,
        ) { sink, _, _ ->
            sink.group(ROOT, 1)
            sink.field(STATUS, "", 1, 1)
        }

        val interpreter = InterpreterPerfEngine.build(scenario).instance(1).outcome().signatures
        val groovy = KernelPerfEngine.build(scenario).instance(1).outcome().signatures
        val java = KernelJavaPerfEngine.build(scenario).instance(1).outcome().signatures

        assertEquals(emptyList<String>(), groovy, "dynamic Groovy reports no delta for an unfilled target")
        assertEquals(groovy, java, "generated Java agrees with dynamic Groovy")
        assertEquals(groovy, interpreter, "the prepared interpreter projection agrees with both kernel strategies")
        assertEquals(groovy, InterpreterPerfEngine.buildWithSupport(scenario).instance(1).run())
    }

    @Test
    fun supportComputationCannotDiscardAnUnregisteredCustomType() {
        val base = DocumentModelDraft.named("perf-support-compute").locale("en_US")
            .rootGroup("Case") { it.customField("Status") { field -> field.type("StatusType") } }
            .build().toJson()
        val bound = BoundModel.ofJson(base)
        val status = bound.custom(STATUS)
        val computation = bound.computation(status).name("SetStatus")
            .calc(Dsl.text("ready")).message(Dsl.en("Review status.")).build()
        val json = TypedRules.inject(base, computation)
        val scenario = PerfScenario("unsupported-compute", json, ModelLoader.load(json), PerfOp.COMPUTE) { sink, _, _ ->
            sink.group(ROOT, 1)
        }
        val instance = InterpreterPerfEngine.buildWithSupport(scenario).instance(1)
        assertEquals(listOf(io.github.mbackschat.a12.dm.interpreter.model.UnsupportedRule(
            "/Case[1]/Status", "custom field type 'StatusType' has no registered validator",
            io.github.mbackschat.a12.dm.interpreter.model.UnsupportedSubject.CELL)), instance.outcome().unsupported)
        assertThrows(IllegalStateException::class.java) { instance.run() }
    }

    private companion object {
        const val ROOT = "/Case"
        const val LEFT = "$ROOT/Left"
        const val RIGHT = "$ROOT/Right"
        const val STATUS = "$ROOT/Status"
    }
}
