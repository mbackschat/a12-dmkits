package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.Document
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
 * Multiplatform (JVM + JS) lock for the final-empty-String store boundary (IG69/feedback F10): a computation
 * whose FINAL String result is empty text stores NO value — the kernel's second root-store decision
 * (`CalculationController.handleBerechnetenWert(VkString…)`: `!hasValue()` → "do like no calculation was
 * done"). Expression evaluation is untouched (clean empty operands still contribute `""` inside concatenation,
 * IF81); only the ROOT store converts a final empty text to the same no-value decision as a bare empty copy.
 * The kernel differential (tri-check, both strategies) is `adapter.laws.EmptyConcatStoreDiffTest`; this pins
 * the interpreter's report-all outcomes — and the store decision's CLASS-level consequences (multi-computation
 * fall-through, cascade reads, gate order) — on both platforms.
 */
class EmptyStringComputationStoreTest {

    private companion object {
        const val HOLDER = "/Policy/HolderName"
        const val RIDER = "/Policy/RiderCode"
        const val LABEL = "/Policy/Label"
        const val SUMMARY = "/Policy/Summary"
    }

    private fun model(vararg comps: ComputationDef, labelMinLength: Int? = null) = ModelDef(
        fields = listOf(
            FieldDef(HOLDER, Kind.STRING),
            FieldDef(RIDER, Kind.STRING),
            FieldDef(LABEL, Kind.STRING, minLength = labelMinLength),
            FieldDef(SUMMARY, Kind.STRING),
        ),
        rules = emptyList(),
        computations = comps.toList(),
    )

    private fun label(op: String) = ComputationDef(LABEL, "/Policy", listOf(ComputationAlternative(null, op)))

    private fun outcomes(m: ModelDef, build: (Document) -> Unit): Map<String, String> {
        val doc = m.newDocument().group("/Policy", 1); build(doc)
        return DmInterpreter(m).compute(doc).associate {
            it.path to when (it.outcome) {
                ComputeOutcome.CLEARED -> "CLEARED"; ComputeOutcome.ERRORED -> "ERRORED|${it.value}"
                ComputeOutcome.VALUE -> "VALUE|${it.value}"
            }
        }
    }

    // The receipt's divergent shape: all operands empty → the concat's final "" is NO value. A stale
    // target is cleared (report-all: CLEARED), never stored as `VALUE ""`.
    @Test fun allEmptyConcatClearsStaleTarget() = assertEquals(
        mapOf(LABEL to "CLEARED"),
        outcomes(model(label("[HolderName] + [RiderCode]"))) { it.field(LABEL, "STALE", 1, 1) })

    // Same decision on an ABSENT target — report-all still says CLEARED (the kernel-granularity projection
    // drops it; the kernel reports nothing).
    @Test fun allEmptyConcatOnAbsentTargetIsCleared() = assertEquals(
        mapOf(LABEL to "CLEARED"),
        outcomes(model(label("[HolderName] + [RiderCode]"))) { })

    // Controls — the store decision is the FINAL-text boundary, not operand substitution (IF81 unchanged).
    @Test fun filledPlusEmptyStoresTheFilledText() = assertEquals(
        mapOf(LABEL to "VALUE|ALPHA"),
        outcomes(model(label("[HolderName] + [RiderCode]"))) { it.field(HOLDER, "ALPHA", 1, 1) })

    @Test fun emptyPlusNonemptyLiteralStoresTheLiteral() = assertEquals(
        mapOf(LABEL to "VALUE|-GOLD"),
        outcomes(model(label("[HolderName] + \"-GOLD\""))) { })

    // "Like no calculation was done" (IF87 fall-through): an all-empty concat is a NO-OP for the cell, so a
    // LATER computation targeting the same cell still wins. Storing `VALUE ""` would wrongly decide the cell.
    @Test fun emptyConcatFallsThroughToLaterComputation() = assertEquals(
        mapOf(LABEL to "VALUE|FALLBACK"),
        outcomes(model(label("[HolderName] + [RiderCode]"), label("\"FALLBACK\""))) { })

    // Cascade: a dependent of the empty-concat-cleared target reads it EMPTY (never the stale stored text).
    @Test fun dependentReadsEmptyConcatTargetAsEmpty() = assertEquals(
        mapOf(LABEL to "CLEARED", SUMMARY to "VALUE|#"),
        outcomes(model(
            label("[HolderName] + [RiderCode]"),
            ComputationDef(SUMMARY, "/Policy", listOf(ComputationAlternative(null, "[Label] + \"#\""))),
        )) { it.field(LABEL, "STALE", 1, 1) })

    // Gate ORDER: the no-value decision precedes the target's format check (the kernel checks only a stored
    // value) — an empty final text into a minLength target is CLEARED, never ERRORED(stringZuKurz).
    @Test fun emptyConcatIntoMinLengthTargetIsClearedNotErrored() = assertEquals(
        mapOf(LABEL to "CLEARED"),
        outcomes(model(label("[HolderName] + [RiderCode]"),
            labelMinLength = 2)) { it.field(LABEL, "ST", 1, 1) })

    // Poison stays its own outcome: a formally-invalid computed result is ERRORED with its attempted value —
    // the empty-store decision never swallows it.
    @Test fun erroredResultIsPreservedWithAttemptedValue() = assertEquals(
        mapOf(LABEL to "ERRORED|A"),
        outcomes(model(label("[HolderName] + [RiderCode]"),
            labelMinLength = 2)) { it.field(HOLDER, "A", 1, 1) })
}
