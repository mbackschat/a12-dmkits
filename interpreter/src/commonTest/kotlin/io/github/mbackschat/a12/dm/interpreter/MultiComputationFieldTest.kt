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
 * Multiplatform (JVM + JS) lock for IF87 — a field targeted by MULTIPLE computations (a composed base + subtype
 * override). Code generation flattens them, in document order, into ONE per-field first-match table, and a
 * **SELECTED** alternative ends that scan unconditionally — including when its operation stores no value
 * (SPEC-2026-07-26-03). Only a computation that was never selected (common precondition false, or no guard
 * matched) is a no-op that control falls through. The kernel differential (tri-check, both strategies) is
 * `adapter.laws.MultiComputationFieldDiffTest`; this pins the interpreter's outcomes on both platforms.
 */
class MultiComputationFieldTest {

    // /Quote/Price computed by a base calc (group Base) and a subtype calc (group Sub); /Quote/Warn depends on Price.
    private fun model(vararg comps: ComputationDef) = ModelDef(
        fields = listOf(
            FieldDef("/Quote/Price", Kind.NUMBER, scale = 2),
            FieldDef("/Quote/Warn", Kind.NUMBER, scale = 0),
            FieldDef("/Quote/Flag", Kind.STRING),
            FieldDef("/Quote/Base/BaseAmt", Kind.NUMBER, scale = 2),
            FieldDef("/Quote/Base/BadBase", Kind.NUMBER, scale = 2, minFractionalDigits = 2),
            FieldDef("/Quote/Sub/SubAmt", Kind.NUMBER, scale = 2),
            FieldDef("/Quote/Sub/BadSub", Kind.NUMBER, scale = 2, minFractionalDigits = 2),
        ),
        rules = emptyList(),
        computations = comps.toList(),
    )

    private fun price(group: String, op: String, guard: String? = null) =
        ComputationDef("/Quote/Price", group, listOf(ComputationAlternative(null, op)), commonPrecondition = guard)

    private fun outcomes(m: ModelDef, build: (Document) -> Unit): Map<String, String> {
        val doc = m.newDocument(); build(doc)
        return DmInterpreter(m).compute(doc).associate {
            it.path to when (it.outcome) {
                ComputeOutcome.CLEARED -> "CLEARED"; ComputeOutcome.ERRORED -> "ERRORED"
                ComputeOutcome.VALUE -> "VALUE|${it.value}"
            }
        }
    }

    private fun seed(d: Document) {
        d.field("/Quote/Base/BaseAmt", "10.00", 1, 1, 1)
        d.field("/Quote/Sub/SubAmt", "20.00", 1, 1, 1)
    }

    // Base value(10) is document-first → wins over Sub value(20).
    @Test fun valueFirstWins() = assertEquals(mapOf("/Quote/Price" to "VALUE|10"),
        outcomes(model(price("/Quote/Base", "[BaseAmt]"), price("/Quote/Sub", "[SubAmt]"))) { seed(it) })

    // An EMPTY base calc (precondition FALSE — Flag empty) is a no-op → control falls through to Sub value(20).
    @Test fun emptyFirstFallsThroughToLaterValue() = assertEquals(mapOf("/Quote/Price" to "VALUE|20"),
        outcomes(model(price("/Quote/Base", "[BaseAmt]", guard = "FieldFilled(../Flag)"), price("/Quote/Sub", "[SubAmt]"))) { seed(it) })

    // An empty SECOND calc is a no-op → the first value survives.
    @Test fun emptySecondIsNoOp() = assertEquals(mapOf("/Quote/Price" to "VALUE|10"),
        outcomes(model(price("/Quote/Base", "[BaseAmt]"), price("/Quote/Sub", "[SubAmt]", guard = "FieldFilled(../Flag)"))) { seed(it) })

    // A poison-first (invalid operand) wins over a later value.
    @Test fun poisonFirstWinsOverLaterValue() = assertEquals(mapOf("/Quote/Price" to "CLEARED"),
        outcomes(model(price("/Quote/Base", "[BadBase]"), price("/Quote/Sub", "[SubAmt]"))) {
            it.field("/Quote/Base/BadBase", "5", 1, 1, 1); it.field("/Quote/Sub/SubAmt", "20.00", 1, 1, 1)
        })

    // The corpus shape: empty base + poisoning subtype → Price poisoned → a FieldNotFilled(Price) dependent cascades.
    @Test fun emptyThenPoisonCascadesToDependent() = assertEquals(
        mapOf("/Quote/Price" to "CLEARED", "/Quote/Warn" to "CLEARED"),
        outcomes(model(
            price("/Quote/Base", "[BaseAmt]", guard = "FieldFilled(../Flag)"),
            price("/Quote/Sub", "[BadSub]"),
            ComputationDef("/Quote/Warn", "/Quote", listOf(ComputationAlternative("FieldNotFilled(Price)", "1"))),
        )) {
            it.field("/Quote/Base/BaseAmt", "10.00", 1, 1, 1); it.field("/Quote/Sub/BadSub", "5", 1, 1, 1)
        })

    // ── selection vs storage (SPEC-2026-07-26-03) ────────────────────────────────────────────────────────────
    // Every case above makes its empty-first computation empty by a FALSE GUARD — i.e. UNSELECTED — where both
    // the old first-non-empty account and the flattened first-SELECTED account agree. These separate them: the
    // first computation is unguarded (selected) but stores nothing, which only a STRING target can show — a
    // number renders empty-as-0 and stores a real 0. Kernel-measured on both strategies in the differential.

    private fun note(group: String, op: String, guard: String? = null) =
        ComputationDef("/Quote/Note", group, listOf(ComputationAlternative(null, op)), commonPrecondition = guard)

    private fun noteModel(vararg comps: ComputationDef) = ModelDef(
        fields = listOf(
            FieldDef("/Quote/Note", Kind.STRING),
            FieldDef("/Quote/Flag", Kind.STRING),
            FieldDef("/Quote/Base/BaseText", Kind.STRING),
        ),
        rules = emptyList(),
        computations = comps.toList(),
    )

    @Test fun aSelectedButEmptyFirstComputationEndsTheScan() = assertEquals(
        mapOf("/Quote/Note" to "CLEARED"),
        outcomes(noteModel(note("/Quote/Base", "[BaseText]"), note("/Quote/Sub", "\"OVERRIDE\""))) { },
        "the unguarded copy of an empty string was SELECTED, so the later literal never stores")

    @Test fun aSelectedAllEmptyConcatenationAlsoEndsTheScan() = assertEquals(
        mapOf("/Quote/Note" to "CLEARED"),
        outcomes(noteModel(note("/Quote/Base", "[BaseText] + [BaseText]"), note("/Quote/Sub", "\"OVERRIDE\""))) { },
        "the same holds for an all-empty concatenation — the shape IF123 is about")

    @Test fun anUnselectedFirstComputationStillFallsThrough() = assertEquals(
        mapOf("/Quote/Note" to "VALUE|OVERRIDE"),
        outcomes(noteModel(note("/Quote/Base", "[BaseText]", guard = "FieldFilled(../Flag)"),
            note("/Quote/Sub", "\"OVERRIDE\""))) { },
        "a FALSE precondition is never selected, so the fall-through survives — the opposite control")

    @Test fun aSelectedFirstComputationWithAValueStillWins() = assertEquals(
        mapOf("/Quote/Note" to "VALUE|FIRST"),
        outcomes(noteModel(note("/Quote/Base", "[BaseText]"), note("/Quote/Sub", "\"OVERRIDE\""))) {
            it.field("/Quote/Base/BaseText", "FIRST", 1, 1, 1)
        })
}
