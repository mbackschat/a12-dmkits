package io.github.mbackschat.a12.dm.adapter.perf

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.UnsupportedConstructException
import io.github.mbackschat.a12.dm.interpreter.model.ComputationAlternative
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.CustomFieldType
import io.github.mbackschat.a12.dm.interpreter.model.CustomFieldTypeDeclaration
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * IG77 → IF156 — the perf harness must never **time a fiction**. Before any sample is eligible, the semantic
 * preflight proves on the interpreter's **outcome-bearing** [io.github.mbackschat.a12.dm.interpreter.DmInterpreter.validate]
 * API that no rule is silently unsupported (INTERPRETER-PERFORMANCE.md "Measure AGAINST the kernel"). Relying on
 * `validateFull` to *throw* mid-timed-run is not that proof — it is an incidental crash, not an explicit
 * before-timing assertion on the outcome the caller can read. A fully-supported scenario passes untouched; an
 * unsupported one is REFUSED, naming the offending rule, so it can never be benchmarked as if it evaluated
 * everything (a silently-unsupported rule would make the interpreter look fast by doing less work than the kernel).
 */
@Tag("perf")
class PerfPreflightTest {

    private val cn = "/Order/CustomerName"

    /**
     * A scenario whose interpreter rule references an UNREGISTERED `CustomCondition` — a legal kernel construct the
     * interpreter reports unsupported (no SPI handler). The kernel model is irrelevant to the preflight, which
     * inspects only the interpreter's outcome; a placeholder suffices.
     */
    private fun unsupportedScenario(): PerfScenario {
        val model = ModelDef(
            fields = listOf(FieldDef(cn, Kind.STRING)),
            rules = listOf(
                RuleDef(
                    "/Order/R1", "/Order",
                    "FieldFilled(CustomerName) And CustomCondition RiskCheck", "R1", "../CustomerName",
                ),
            ),
        )
        return PerfScenario("unsupported-preflight", kernelModel = "{}", interpModel = model) { sink, _, _ ->
            sink.group("/Order", 1); sink.field(cn, "Acme", 1, 1)
        }
    }

    @Test
    fun preflightRefusesToTimeAScenarioWithAnUnsupportedConstruct() {
        val ex = assertThrows<IllegalStateException> { PerfHarness.preflight(unsupportedScenario(), n = 1) }
        val msg = ex.message ?: ""
        assertTrue("/Order/R1" in msg, "the refusal names the unsupported rule: $msg")
        assertTrue("unsupported" in msg.lowercase(), "the refusal explains it will not time a fiction: $msg")
    }

    @Test
    fun preflightPassesAFullySupportedScenario() {
        // B1 (per-row Qty>50) — every construct supported, so the preflight returns cleanly (no throw).
        PerfHarness.preflight(PerfCatalog.b1(), n = 50)
    }

    @Test
    fun preparedAuditFindsAnUnregisteredCustomTypeEvenWhenOnlyARowAfterTheSampleCapHasAValue() {
        val code = "/C/Rows/Code"
        val model = ModelDef(
            fields = listOf(FieldDef(code, Kind.STRING, customType = CustomFieldTypeDeclaration("LateType"))),
            rules = emptyList(),
            repeatableGroups = setOf("/C/Rows"),
            declaredReps = mapOf("/C/Rows" to 1_000),
        )
        val scenario = PerfScenario("late-custom-type", "{}", model) { sink, n, _ ->
            sink.group("/C", 1)
            for (row in 1..n) {
                sink.group("/C/Rows", 1, row)
                if (row == 201) sink.field(code, "late", 1, row, 1)
            }
        }

        val ex = assertThrows<IllegalStateException> { PerfHarness.preflight(scenario, n = 201) }
        assertTrue("LateType" in (ex.message ?: ""), "the value-independent audit names the missing type: ${ex.message}")
    }

    @Test
    fun everyRepeatedVariantGetsAnOutcomeBearingUnsupportedCheck() {
        val code = "/C/Code"
        val model = ModelDef(
            fields = listOf(FieldDef(code, Kind.STRING, customType = CustomFieldTypeDeclaration("VariantType"))),
            rules = emptyList(),
        )
        val checker = CustomFieldType { value, _ ->
            if (value == "middle") throw UnsupportedConstructException("middle variant unavailable") else null
        }
        val scenario = PerfScenario(
            "middle-variant",
            "{}",
            model,
            interpreterFactory = { DmInterpreter(it, customFieldTypes = mapOf("VariantType" to checker)) },
        ) { sink, _, variant ->
            sink.group("/C", 1)
            sink.field(code, listOf("first", "middle", "last")[variant], 1, 1)
        }

        val ex = assertThrows<IllegalStateException> {
            PerfHarness.preflightVariants(scenario, n = 1, variants = 3)
        }
        assertTrue("variant=1" in (ex.message ?: ""), "the refusal identifies the unsupported middle variant: ${ex.message}")
    }

    @Test
    fun exactScaleTimingRefusesARegisteredCallbackFailureAfterTheSampleCap() {
        val code = "/C/Rows/Code"
        val model = ModelDef(
            fields = listOf(FieldDef(code, Kind.STRING, customType = CustomFieldTypeDeclaration("LateType"))),
            rules = emptyList(),
            repeatableGroups = setOf("/C/Rows"),
            declaredReps = mapOf("/C/Rows" to 1_000),
        )
        val checker = CustomFieldType { value, _ ->
            if (value == "late") throw UnsupportedConstructException("late row callback unavailable") else null
        }
        val scenario = PerfScenario(
            "late-registered-callback",
            "{}",
            model,
            interpreterFactory = { DmInterpreter(it, customFieldTypes = mapOf("LateType" to checker)) },
        ) { sink, n, _ ->
            sink.group("/C", 1)
            for (row in 1..n) {
                sink.group("/C/Rows", 1, row)
                sink.field(code, if (row == 201) "late" else "ok", 1, row, 1)
            }
        }

        val cold = assertThrows<IllegalStateException> {
            PerfHarness.freshRunMedianMillis(scenario, n = 201, repeats = 1)
        }
        assertTrue("N=201" in (cold.message ?: ""), "the cold refusal names the exact timed scale: ${cold.message}")
        assertTrue("late row callback" in (cold.message ?: ""), "the cold refusal names the callback failure: ${cold.message}")

        val warm = assertThrows<IllegalStateException> {
            PerfHarness.timeInterpreter(scenario, n = 201, warmup = 0, iters = 1)
        }
        assertTrue("N=201" in (warm.message ?: ""), "the warm refusal names the exact timed scale: ${warm.message}")
        assertTrue("late row callback" in (warm.message ?: ""), "the warm refusal names the callback failure: ${warm.message}")
    }

    @Test
    fun preparedAuditChecksEveryComputationAlternativeRegardlessOfDataSelection() {
        val selector = "/C/Selector"
        val result = "/C/Result"
        val model = ModelDef(
            fields = listOf(FieldDef(selector, Kind.NUMBER), FieldDef(result, Kind.NUMBER)),
            rules = emptyList(),
            computations = listOf(
                ComputationDef(
                    targetPath = result,
                    group = "/C",
                    alternatives = listOf(
                        ComputationAlternative("[$selector] == 1", "1"),
                        ComputationAlternative("[$selector] == 2", "UnsupportedFunction([$selector])"),
                    ),
                    name = "SelectResult",
                ),
            ),
        )
        val scenario = PerfScenario("selected-alternative", "{}", model) { sink, _, _ ->
            sink.group("/C", 1)
            sink.field(selector, "1", 1, 1)
        }

        val ex = assertThrows<IllegalStateException> { PerfHarness.preflight(scenario, n = 1) }
        assertTrue("UnsupportedFunction" in (ex.message ?: ""), "the unselected alternative is still audited: ${ex.message}")
    }

    @Test
    fun scenarioLocaleIsTheLocaleTheInterpreterEngineExecutes() {
        val code = "/C/Code"
        val model = ModelDef(
            fields = listOf(FieldDef(code, Kind.STRING, customType = CustomFieldTypeDeclaration("LocaleType"))),
            rules = emptyList(),
        )
        var observedLocale = ""
        val checker = CustomFieldType { _, context ->
            observedLocale = context.locale
            null
        }
        val scenario = PerfScenario(
            "locale-bound",
            "{}",
            model,
            interpreterFactory = { DmInterpreter(it, customFieldTypes = mapOf("LocaleType" to checker)) },
            locale = "de_DE",
        ) { sink, _, _ ->
            sink.group("/C", 1)
            sink.field(code, "value", 1, 1)
        }

        InterpreterPerfEngine.build(scenario).instance(1).outcome()

        assertEquals("de_DE", observedLocale)
    }
}
