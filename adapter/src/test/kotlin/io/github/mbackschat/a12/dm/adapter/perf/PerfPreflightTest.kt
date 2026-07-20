package io.github.mbackschat.a12.dm.adapter.perf

import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import org.junit.jupiter.api.Assertions.assertTrue
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
}
