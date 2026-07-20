package io.github.mbackschat.a12.dm.adapter.perf

import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PerfAgreementTest {

    @Test
    fun fullValidationSignaturesRejectARowAttributionSwap() {
        val expected = listOf(
            "LIMIT|VALUE_ERROR|/Order[1]/Items[1]/Qty[1]",
            "OTHER|VALUE_ERROR|/Order[1]/Items[2]/Qty[1]",
        )
        val swapped = listOf(
            "LIMIT|VALUE_ERROR|/Order[1]/Items[2]/Qty[1]",
            "OTHER|VALUE_ERROR|/Order[1]/Items[1]/Qty[1]",
        )

        assertThrows<IllegalStateException> {
            PerfHarness.assertSignaturesAgree("row-swap", 2, "expected", expected, "swapped", swapped)
        }
    }

    @Test
    fun fullComputationSignaturesRejectValuesSwappedBetweenRows() {
        val expected = listOf(
            "/Order[1]/Items[1]/Total[1]|VALUE|10",
            "/Order[1]/Items[2]/Total[1]|VALUE|20",
        )
        val swapped = listOf(
            "/Order[1]/Items[1]/Total[1]|VALUE|20",
            "/Order[1]/Items[2]/Total[1]|VALUE|10",
        )

        assertThrows<IllegalStateException> {
            PerfHarness.assertSignaturesAgree("value-swap", 2, "expected", expected, "swapped", swapped)
        }
        assertDoesNotThrow {
            PerfHarness.assertSignaturesAgree("same", 2, "a", expected, "b", expected)
        }
    }

    @Test
    fun erroredComputationSignaturesRejectAttemptedValueSwaps() {
        val source = "/C/Source"
        val target = "/C/Target"
        val model = ModelDef(
            fields = listOf(
                FieldDef(source, Kind.NUMBER),
                FieldDef(target, Kind.NUMBER, maxValue = "10"),
            ),
            rules = emptyList(),
            computations = listOf(ComputationDef(target, "/C", "[$source]")),
        )
        val scenario = PerfScenario(
            "errored-value-swap",
            kernelModel = "{}",
            interpModel = model,
            op = PerfOp.COMPUTE,
        ) { sink, _, variant ->
            sink.group("/C", 1)
            sink.field(source, if (variant == 0) "11" else "12", 1, 1)
        }
        val engine = InterpreterPerfEngine.build(scenario)
        val eleven = engine.instance(1, 0).outcome().signatures
        val twelve = engine.instance(1, 1).outcome().signatures

        assertTrue(eleven.single().endsWith("|ERRORED|11"), "$eleven")
        assertTrue(twelve.single().endsWith("|ERRORED|12"), "$twelve")
        assertThrows<IllegalStateException> {
            PerfHarness.assertSignaturesAgree("errored-value-swap", 1, "eleven", eleven, "twelve", twelve)
        }
    }
}
