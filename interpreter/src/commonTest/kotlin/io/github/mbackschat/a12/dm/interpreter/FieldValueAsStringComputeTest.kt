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
 * Multiplatform (JVM + JS) lock for the PLAIN-field `FieldValueAsString` compute reads (IF108's class sweep)
 * and the two rules they surfaced:
 *
 *  - IF109 — a NOT-GIVEN read (empty source) is `Val.Unknown`, not `Txt("")`: a standalone copy computes
 *    nothing (CLEARED — the kernel's `handleBerechnetenWert(VkString)` returns early on an empty value), a
 *    concat maps it to `""`; a formally-INVALID source poisons in compute mode (IF85's rule through the
 *    coercion), standalone and in-concat alike.
 *  - IF110 — a COMPUTED value is checked with the kernel's `checkFieldValueFormatBasic` (type format only):
 *    a computed string that starts/ends with a blank is a plain VALUE, not ERRORED (the baseline
 *    charset/blank checks belong to stored INPUT values only).
 *
 * Kernel-verified by the tri-check differential `adapter.laws.FieldValueAsStringComputeDiffTest`.
 */
class FieldValueAsStringComputeTest {

    private val FEE = "/Invoice/Fee"
    private val QTY = "/Invoice/Qty"
    private val FEE_TEXT = "/Invoice/FeeText"
    private val QTY_TEXT = "/Invoice/QtyText"

    private val def = ModelDef(
        fields = listOf(
            FieldDef(FEE, Kind.NUMBER, scale = 2, minFractionalDigits = 2),
            FieldDef(QTY, Kind.NUMBER, scale = 0),
            FieldDef(FEE_TEXT, Kind.STRING), FieldDef(QTY_TEXT, Kind.STRING),
        ),
        rules = emptyList(),
        computations = listOf(
            ComputationDef(FEE_TEXT, "/Invoice", "FieldValueAsString(Fee) + \" G\""),
            ComputationDef(QTY_TEXT, "/Invoice", "FieldValueAsString(Qty)"),
        ),
        repeatableGroups = emptySet(),
    )

    /** → the (FeeText, QtyText) outcomes. */
    private fun outcomes(fee: String?, qty: String?): Pair<String, String> {
        val doc = def.newDocument().group("/Invoice", 1)
            .field(FEE_TEXT, "SEED", 1, 1).field(QTY_TEXT, "SEED", 1, 1)
        fee?.let { doc.field(FEE, it, 1, 1) }
        qty?.let { doc.field(QTY, it, 1, 1) }
        val computed = DmInterpreter(def).compute(doc)
        fun at(path: String) = computed.first { it.path == path }.let { c ->
            when (c.outcome) {
                ComputeOutcome.VALUE -> "VALUE|${c.value}"
                ComputeOutcome.CLEARED -> "CLEARED"
                ComputeOutcome.ERRORED -> "ERRORED"
            }
        }
        return at(FEE_TEXT) to at(QTY_TEXT)
    }

    @Test fun filledSourcesCompute() =
        assertEquals("VALUE|3.50 G" to "VALUE|7", outcomes("3.50", "7"))

    @Test fun anInvalidSourceInTheConcatClears() =
        assertEquals("CLEARED" to "VALUE|7", outcomes("1.234", "7"))

    @Test fun anInvalidSourceStandaloneClears() =
        assertEquals("VALUE|3.50 G" to "CLEARED", outcomes("3.50", "7.5"))

    // The computed " G" LEADS with a blank and is still a VALUE — the computed-value check is the kernel's
    // BASIC one (type format only), which has no leading/trailing-blank rule (IF110).
    @Test fun anEmptySourceInTheConcatContributesBlank() =
        assertEquals("VALUE| G" to "VALUE|7", outcomes(null, "7"))

    @Test fun anEmptySourceStandaloneClears() =
        assertEquals("VALUE|3.50 G" to "CLEARED", outcomes("3.50", null))
}
