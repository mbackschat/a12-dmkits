package io.github.mbackschat.a12.dm.adapter.perf

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
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
}
