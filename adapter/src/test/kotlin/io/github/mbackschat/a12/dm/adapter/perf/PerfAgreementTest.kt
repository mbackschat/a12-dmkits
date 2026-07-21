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

    @Test
    fun largeSignatureMismatchReportsBoundedMultisetDeltas() {
        val longSignature = "LONG|" + "x".repeat(10_000)
        val expected = buildList {
            repeat(4) { add("DUPLICATE") }
            add(longSignature)
            addAll((0 until 100).map { "EXPECTED-$it" })
        }
        val actual = buildList {
            add("DUPLICATE")
            addAll((0 until 120).map { "ACTUAL-$it" })
        }

        val error = assertThrows<IllegalStateException> {
            PerfHarness.assertSignaturesAgree("large-mismatch", 100, "expected", expected, "actual", actual)
        }
        val message = checkNotNull(error.message)

        assertTrue(message.length < 6_000, "mismatch diagnostics must remain bounded; length=${message.length}")
        assertTrue(message.contains("only-expected(104)"), message)
        assertTrue(message.contains("only-actual(120)"), message)
        assertTrue(message.contains("DUPLICATE ×3"), message)
        assertTrue(message.contains("LONG|"), message)
        assertTrue(!message.contains(longSignature), "the 10,000-character signature must be truncated")
        assertTrue(message.contains("EXPECTED-7"), message)
        assertTrue(!message.contains("EXPECTED-8"), "each side must stop after ten distinct samples")
        assertTrue(message.contains("ACTUAL-9"), message)
        assertTrue(!message.contains("ACTUAL-10"), "each side must stop after ten distinct samples")
    }
}
