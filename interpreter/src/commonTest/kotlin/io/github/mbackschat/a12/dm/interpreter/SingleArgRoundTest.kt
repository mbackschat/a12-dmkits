package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multiplatform (JVM + JS) lock for IF102 — the Round family's decimal-places argument is OPTIONAL on BOTH
 * forms (`RoundX(operation[, digits])` / `RoundXValue(field[, digits])`, the kernel grammar's
 * `(',' ZiffernFolge)?`): an absent digits argument is the integer round (places 0), and a PRESENT digits
 * argument is honored by the field form too. The 1-arg operation form previously crashed
 * (`IndexOutOfBoundsException` — aborting whole corpus instances); the field form hardcoded 0 places.
 * Kernel-verified by the tri-check differential `adapter.laws.SingleArgRoundDiffTest`.
 */
class SingleArgRoundTest {

    private val AMOUNT = "/Ledger/Amount"
    private val RESULT = "/Ledger/Result"

    private fun outcome(operation: String, amount: String): String {
        val def = ModelDef(
            fields = listOf(FieldDef(AMOUNT, Kind.NUMBER, scale = 3), FieldDef(RESULT, Kind.NUMBER, scale = 2)),
            rules = emptyList(),
            computations = listOf(ComputationDef(RESULT, "/Ledger", operation)),
        )
        val doc = def.newDocument().group("/Ledger", 1).field(AMOUNT, amount, 1, 1)
        val c = DmInterpreter(def).compute(doc).first { it.path == RESULT }
        return when (c.outcome) {
            ComputeOutcome.VALUE -> "VALUE|${c.value}"
            ComputeOutcome.CLEARED -> "CLEARED"
            ComputeOutcome.ERRORED -> "ERRORED"
        }
    }

    @Test fun singleArgOperationFormsRoundToInteger() {
        assertEquals("VALUE|5", outcome("RoundDown([Amount])", "5.75"))
        assertEquals("VALUE|6", outcome("RoundUp([Amount])", "5.25"))
        assertEquals("VALUE|6", outcome("RoundAccounting([Amount])", "5.5"))
    }

    @Test fun fieldFormHonorsAPresentDigitsArgument() =
        assertEquals("VALUE|5.13", outcome("RoundAccountingValue(Amount, 2)", "5.125"))

    @Test fun fieldFormWithoutDigitsRoundsToInteger() =
        assertEquals("VALUE|6", outcome("RoundAccountingValue(Amount)", "5.5"))
}
