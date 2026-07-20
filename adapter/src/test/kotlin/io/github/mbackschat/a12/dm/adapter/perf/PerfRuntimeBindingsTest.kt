package io.github.mbackschat.a12.dm.adapter.perf

import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldTypeCheckError
import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldTypeFactory
import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldTypeValidationParam
import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldValidator
import io.github.mbackschat.a12.dm.adapter.laws.TypedRules
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.CustomCondition
import io.github.mbackschat.a12.dm.interpreter.model.CustomFieldType
import io.github.mbackschat.a12.dm.interpreter.model.CustomTypeRejection
import io.github.mbackschat.a12.dm.modelkit.CustomFieldBuilder
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import java.time.Instant
import java.util.Optional
import java.util.function.Consumer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Runtime bindings are part of a fair scenario: every timed engine must see the same clock and callbacks. */
class PerfRuntimeBindingsTest {

    @Test
    fun fixedClockAndCustomConditionReachAllThreeEngines() {
        val base = DocumentModelDraft.named("perf-runtime-bindings").locale("en_US")
            .rootGroup("Case") { root ->
                root.dateField("Day") {}
            }
            .build()
            .toJson()
        val model = BoundModel.ofJson(base)
        val day = model.date(DAY)
        val rule = model.rule(model.group(ROOT)).name("PinnedRuntime")
            .code("PINNED")
            .errorField(day)
            .`when`(day.filled().and(day.eq(Dsl.today())).and(Dsl.customCondition(ALWAYS_TRUE)))
            .severity(Severity.ERROR)
            .message(Dsl.en("pinned runtime"))
            .build()
        val modelJson = TypedRules.inject(base, rule)
        val definition = ModelLoader.load(modelJson)
        val bindings = PerfRuntimeBindings.builder()
            .clock(DAY_VALUE, "${DAY_VALUE}T12:00:00", Instant.parse("${DAY_VALUE}T12:00:00Z"))
            .customCondition(
                ALWAYS_TRUE,
                "constant-true-v1",
                CustomCondition { _, _, _, _ -> true },
                constantKernelCondition(true),
            )
            .build()
        val scenario = PerfScenario(
            name = "pinned-runtime-bindings",
            kernelModel = modelJson,
            interpModel = definition,
        ) { sink, _, _ ->
            sink.group(ROOT, 1)
            sink.field(DAY, DAY_VALUE, 1, 1)
        }.withRuntimeBindings(bindings)

        val row = PerfHarness.runScenario(scenario, n = 1, warmup = 0, iters = 1)

        assertEquals(1, row.firings)
    }

    @Test
    fun fixedClockReachesComputationOnAllThreeEngines() {
        val base = DocumentModelDraft.named("perf-compute-bindings").locale("en_US")
            .rootGroup("Case") { root -> root.dateField("CalculatedDay") {} }
            .build()
            .toJson()
        val model = BoundModel.ofJson(base)
        val computation = model.computation(model.date(CALCULATED_DAY).field())
            .name("PinnedToday")
            .calc(Dsl.today().expression())
            .message(Dsl.en("pinned today"))
            .build()
        val modelJson = TypedRules.inject(base, computation)
        val bindings = PerfRuntimeBindings.builder()
            .clock(DAY_VALUE, "${DAY_VALUE}T12:00:00", Instant.parse("${DAY_VALUE}T12:00:00Z"))
            .build()
        val scenario = PerfScenario(
            name = "pinned-compute-bindings",
            kernelModel = modelJson,
            interpModel = ModelLoader.load(modelJson),
            op = PerfOp.COMPUTE,
        ) { sink, _, _ -> sink.group(ROOT, 1) }
            .withRuntimeBindings(bindings)

        val row = PerfHarness.runScenario(scenario, n = 1, warmup = 0, iters = 1)

        assertEquals(1, row.firings)
    }

    @Test
    fun customFieldTypeRejectionReachesAllThreeEngines() {
        val modelJson = DocumentModelDraft.named("perf-custom-type-bindings").locale("en_US")
            .rootGroup("Case", Consumer { root ->
                root.customField("Code", Consumer<CustomFieldBuilder> { it.type(CUSTOM_TYPE) })
            })
            .build()
            .toJson()
        val interpreterType = CustomFieldType { _, _ -> CustomTypeRejection(REJECTION_CODE) }
        val kernelFactory = object : ICustomFieldTypeFactory {
            override fun createCustomFieldValidator(name: String): Optional<ICustomFieldValidator> =
                if (name != CUSTOM_TYPE) Optional.empty() else Optional.of(object : ICustomFieldValidator {
                    override fun validate(
                        value: String,
                        parameter: ICustomFieldTypeValidationParam,
                        displayValue: Boolean,
                    ): Optional<ICustomFieldTypeCheckError> = Optional.of(object : ICustomFieldTypeCheckError {
                        override fun getErrorMessage(): String = "rejected for perf binding"
                        override fun getErrorKey(): String = REJECTION_CODE
                    })
                })
        }
        val bindings = PerfRuntimeBindings.builder()
            .customFieldTypes("always-reject-v1", mapOf(CUSTOM_TYPE to interpreterType), kernelFactory)
            .build()
        val scenario = PerfScenario(
            name = "paired-custom-field-type",
            kernelModel = modelJson,
            interpModel = ModelLoader.load(modelJson),
        ) { sink, _, _ ->
            sink.group(ROOT, 1)
            sink.field(CODE, "invalid", 1, 1)
        }.withRuntimeBindings(bindings)

        val row = PerfHarness.runScenario(scenario, n = 1, warmup = 0, iters = 1)

        assertEquals(1, row.firings)
    }

    @Test
    fun comparativeTimingRejectsAnInterpreterOnlyBinding() {
        val scenario = PerfCatalog.b1().withInterpreterOnlyFactory("unpaired-test") { DmInterpreter(it) }

        assertThrows<IllegalStateException> {
            PerfHarness.runScenario(scenario, n = 1, warmup = 0, iters = 1)
        }
    }

    private companion object {
        const val ROOT = "/Case"
        const val DAY = "$ROOT/Day"
        const val CALCULATED_DAY = "$ROOT/CalculatedDay"
        const val CODE = "$ROOT/Code"
        const val DAY_VALUE = "2020-02-03"
        const val ALWAYS_TRUE = "AlwaysTrue"
        const val CUSTOM_TYPE = "ProjectCode"
        const val REJECTION_CODE = "PROJECT_CODE_INVALID"
    }
}
