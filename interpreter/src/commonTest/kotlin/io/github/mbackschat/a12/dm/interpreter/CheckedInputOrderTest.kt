package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.ComputationAlternative
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multiplatform (JVM + JS) lock for the ordered input gates of a stored value (KERNEL-FINDINGS KF547): a raw
 * `noValueValidation` String is never checked; every other value meets the technical length guard over its
 * authored text, then the charset/blank baseline. A computed String target meets its type's default maximum of
 * 99,999 instead. The kernel differential for every row is `adapter.laws.CheckedInputOrderDiffTest`; this pins
 * the same boundaries on both platforms, where the UTF-16 count must not drift.
 */
class CheckedInputOrderTest {

    private companion object {
        const val LIMIT = 99_999
        const val NOTE = "/Order/Note"
        const val MEMO = "/Order/Memo"
        const val COPY = "/Order/Copy"
    }

    private val model = ModelDef(
        fields = listOf(
            FieldDef(NOTE, Kind.STRING, lineBreaksPermitted = true),
            FieldDef(MEMO, Kind.STRING, lineBreaksPermitted = true, noValueValidation = true),
            FieldDef(COPY, Kind.STRING, lineBreaksPermitted = true),
        ),
        rules = emptyList(),
        computations = listOf(ComputationDef(COPY, "/Order", listOf(ComputationAlternative(null, "[Note] + [Note]")))),
    )

    private fun codes(field: String, value: String): List<String> =
        DmInterpreter(model).validateFull(model.newDocument().group("/Order", 1).field(field, value, 1, 1))
            .map { it.code }

    @Test fun theLimitItselfIsAdmitted() = assertEquals(emptyList(), codes(NOTE, "a".repeat(LIMIT)))

    @Test fun oneUnitMoreIsTooLong() = assertEquals(listOf("wertZuLang"), codes(NOTE, "a".repeat(LIMIT + 1)))

    // A supplementary character is two units, and the guard answers before the charset can.
    @Test fun theGuardPrecedesTheCharset() =
        assertEquals(listOf("wertZuLang"), codes(NOTE, "a".repeat(LIMIT - 1) + "😀"))

    // 100,000 authored units fold to 99,999: the authored count decides.
    @Test fun anAuthoredCrlfCountsTwoUnits() =
        assertEquals(listOf("wertZuLang"), codes(NOTE, "a".repeat(LIMIT - 1) + "\r\n"))

    @Test fun aRawStringIsNeverChecked() {
        assertEquals(emptyList(), codes(MEMO, " x "))
        assertEquals(emptyList(), codes(MEMO, "a".repeat(LIMIT + 1)))
    }

    @Test fun aComputedStringMeetsTheDefaultMaximum() {
        fun outcome(half: Int) = DmInterpreter(model)
            .compute(model.newDocument().group("/Order", 1).field(NOTE, "a".repeat(half), 1, 1))
            .single { it.path == COPY }.outcome
        assertEquals(ComputeOutcome.VALUE, outcome(LIMIT / 2))
        assertEquals(ComputeOutcome.ERRORED, outcome((LIMIT + 1) / 2))
    }
}
