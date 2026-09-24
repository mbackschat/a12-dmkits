package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.StoredCellState
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Differential + tri-check (groovy-dynamic == java-static == interpreter) for the **post-application cell
 * states** (IG72 → IF126; KERNEL-FINDINGS §11 "post-application cell state"): the interpreter's
 * `Document.cellStateAt` over `DmInterpreter.applyComputations` must report, per cell, exactly what the
 * kernel's `compute() → IDocumentComputationResult.applyTo(DocumentV2)` route leaves — absent /
 * present-empty / present-value(raw) kept distinct. The kernel legs read the applied `DocumentV2` directly
 * ([RuntimeLaws.appliedStates]); both strategies share the apply implementation
 * (`ComputedFieldInstanceImpl.applyTo`), so the tri-check also guards the strategy seam.
 *
 * The final arm locks the applied copy's CONFIGURATION: `Document.withComputed` once dropped the
 * `fieldConstraints` map, so `validate(applyComputations(doc))` fired an aggregate rule over a
 * constraint-invalid cell where the kernel's form-engine flow suppresses it (the multiplatform twin is
 * `interpreter … AppliedCopyConstraintsTest`).
 */
class AppliedCellStateDiffTest {

    private companion object {
        const val HOLDER = "/Policy/HolderName"
        const val RIDER = "/Policy/RiderCode"
        const val LABEL = "/Policy/Label"
        const val NOTE = "/Policy/Note"
        const val AMOUNT = "/Policy/Items/Amount"
        val PROBE_PATHS = listOf(HOLDER, RIDER, LABEL, NOTE)
        val PROBES = PROBE_PATHS.map { RuntimeLaws.CellProbe(it, listOf(1, 1)) }
    }

    private fun policy(labelMinLength: Int? = null): String = DocumentModelDraft.named("policy").locale("en_US")
        .rootGroup("Policy") { g ->
            g.stringField("HolderName") {}.stringField("RiderCode") {}
                .stringField("Label") { l -> labelMinLength?.let { l.minLength(it) } }
                .stringField("Note") {}
        }
        .build().toJson()

    private fun kernelModel(minLength: Int? = null): String =
        RuntimeLaws.withComputation(policy(minLength), "/Policy", "LabelComp", "../Label",
            "[HolderName] + [RiderCode]")

    private fun wire(s: StoredCellState): String = when (s) {
        StoredCellState.Absent -> "absent"
        StoredCellState.PresentEmpty -> "presentEmpty"
        is StoredCellState.PresentValue -> "presentValue|${s.raw}"
    }

    /** Tri-check all four probed cells after apply; [expect] pins the full signature list. */
    private fun agreeStates(minLength: Int? = null, expect: Map<String, String>, msg: String, build: (Both) -> Unit) {
        val kModel = kernelModel(minLength)
        val def = ModelLoader.load(kModel)
        val b = Both(RuntimeLaws.doc(), def.newDocument())
        build(b)
        val groovy = RuntimeLaws.appliedStates(kModel, b.kDoc, PROBES)
        val engine = RuntimeLaws.kernelJavaEngine(kModel)
        val kDoc2 = RuntimeLaws.doc()
        build(Both(kDoc2, def.newDocument()))
        val javaStatic = engine.appliedStates(kDoc2, PROBES)
        val applied = DmInterpreter(def).applyComputations(b.iDoc)
        val interp = PROBE_PATHS.map { "$it|" + wire(applied.cellStateAt(it, listOf(1, 1))) }
        val expected = PROBE_PATHS.map { "$it|${expect.getValue(it)}" }
        assertEquals(expected, groovy, "$msg — the kernel groovy-dynamic applied states")
        assertEquals(groovy, javaStatic, "$msg — groovy vs java-static (tri-check)")
        assertEquals(groovy, interp, "$msg — kernel vs interpreter (cellStateAt)")
    }

    // A computed VALUE lands in an ABSENT cell — created, present-value; untouched neighbours keep their states.
    @Test fun aComputedValueCreatesAnAbsentCell() = agreeStates(
        expect = mapOf(HOLDER to "presentValue|ALPHA", RIDER to "presentValue|-GOLD",
            LABEL to "presentValue|ALPHA-GOLD", NOTE to "absent"),
        msg = "value into absent target") { b ->
        b.group("/Policy", 1).field(HOLDER, "ALPHA", 1, 1).field(RIDER, "-GOLD", 1, 1)
    }

    // …and REPLACES a stale value.
    @Test fun aComputedValueReplacesAStaleCell() = agreeStates(
        expect = mapOf(HOLDER to "presentValue|ALPHA", RIDER to "presentValue|-GOLD",
            LABEL to "presentValue|ALPHA-GOLD", NOTE to "absent"),
        msg = "value over stale target") { b ->
        b.group("/Policy", 1).field(HOLDER, "ALPHA", 1, 1).field(RIDER, "-GOLD", 1, 1).field(LABEL, "STALE", 1, 1)
    }

    // A VALUE typed-EQUAL to the stored one is NOT applied by the kernel (delta granularity — only
    // `withChanges` applies); the final state is indistinguishable, which this pins.
    @Test fun aComputedValueEqualToTheStoredOneLeavesTheSameState() = agreeStates(
        expect = mapOf(HOLDER to "presentValue|ALPHA", RIDER to "presentValue|-GOLD",
            LABEL to "presentValue|ALPHA-GOLD", NOTE to "absent"),
        msg = "value equal to stored") { b ->
        b.group("/Policy", 1).field(HOLDER, "ALPHA", 1, 1).field(RIDER, "-GOLD", 1, 1)
            .field(LABEL, "ALPHA-GOLD", 1, 1)
    }

    // The all-empty concat computes nothing (IF123): a stale-FILLED target is EMPTIED but stays PRESENT —
    // the cleared instance is `withFieldValue(pointer, null)`, never a removal.
    @Test fun aClearedStaleCellReadsPresentEmptyNeverAbsent() = agreeStates(
        expect = mapOf(HOLDER to "absent", RIDER to "absent", LABEL to "presentEmpty", NOTE to "absent"),
        msg = "cleared stale target") { b ->
        b.group("/Policy", 1).field(LABEL, "STALE", 1, 1)
    }

    // Computed-nothing over an EMPTY-placed target: the kernel mints NO cleared instance (not input-filled)
    // — the placement survives untouched as present-empty.
    @Test fun computedNothingOverAnEmptyPlacedTargetStaysPresentEmpty() = agreeStates(
        expect = mapOf(HOLDER to "absent", RIDER to "absent", LABEL to "presentEmpty", NOTE to "presentValue|keep"),
        msg = "computed-nothing over empty-placed target") { b ->
        b.group("/Policy", 1).emptyField(LABEL, 1, 1).field(NOTE, "keep", 1, 1)
    }

    // …and over an ABSENT target nothing is created — the Set Y receipt's silence, as a STATE.
    @Test fun computedNothingOverAnAbsentTargetStaysAbsent() = agreeStates(
        expect = mapOf(HOLDER to "absent", RIDER to "absent", LABEL to "absent", NOTE to "presentValue|keep"),
        msg = "computed-nothing over absent target") { b ->
        b.group("/Policy", 1).field(NOTE, "keep", 1, 1)
    }

    // An ERRORED result (computed "A" into a minLength-2 target) EMPTIES a present cell — stale or
    // empty-placed alike…
    @Test fun anErroredResultEmptiesAStaleCell() = agreeStates(minLength = 2,
        expect = mapOf(HOLDER to "presentValue|A", RIDER to "absent", LABEL to "presentEmpty", NOTE to "absent"),
        msg = "errored over stale target") { b ->
        b.group("/Policy", 1).field(HOLDER, "A", 1, 1).field(LABEL, "STALE", 1, 1)
    }

    @Test fun anErroredResultKeepsAnEmptyPlacedCellPresentEmpty() = agreeStates(minLength = 2,
        expect = mapOf(HOLDER to "presentValue|A", RIDER to "absent", LABEL to "presentEmpty", NOTE to "absent"),
        msg = "errored over empty-placed target") { b ->
        b.group("/Policy", 1).field(HOLDER, "A", 1, 1).emptyField(LABEL, 1, 1)
    }

    // …but never CREATES one: the kernel's errored apply is guarded on the instance existing.
    @Test fun anErroredResultOverAnAbsentTargetStaysAbsent() = agreeStates(minLength = 2,
        expect = mapOf(HOLDER to "presentValue|A", RIDER to "absent", LABEL to "absent", NOTE to "absent"),
        msg = "errored over absent target") { b ->
        b.group("/Policy", 1).field(HOLDER, "A", 1, 1)
    }

    // The raw STORED text is reported unnormalized: the kernel's document store keeps CRLF (only its eval
    // caches normalize, IF124) — an untouched multi-line cell must read back byte-identical.
    @Test fun anUntouchedCrlfValueReadsBackUnnormalized() = agreeStates(
        expect = mapOf(HOLDER to "presentValue|ALPHA", RIDER to "presentValue|-GOLD",
            LABEL to "presentValue|ALPHA-GOLD", NOTE to "presentValue|X\r\nY"),
        msg = "untouched CRLF cell") { b ->
        b.group("/Policy", 1).field(HOLDER, "ALPHA", 1, 1).field(RIDER, "-GOLD", 1, 1)
            .field(NOTE, "X\r\nY", 1, 1)
    }

    // ---- the applied copy keeps the declared constraints (the emptyCopy fix) ---------------------------

    /** A Sum over the starred `Items` rows' `Amount` compared to `5.00` must stay SUPPRESSED after apply
     *  when the stored "5" violates the declared minFractionalDigits — the applied copy is the same document
     *  plus the computed value, never a constraint-stripped one. The control (a valid "5.00") proves the
     *  rule is live. */
    @Test fun theAppliedCopyKeepsDeclaredConstraints() {
        val base = DocumentModelDraft.named("policy-sum").locale("en_US")
            .rootGroup("Policy") { g ->
                g.stringField("Note") {}.stringField("Label") {}
                g.group("Items") { i -> i.repeatable(5).numberField("Amount") { it.fractionDigits(2, 2) } }
            }
            .build().toJson()
        // The error field must be referenced in the condition (MVK_ERROR_FIELD_NOT_REFERENCED) — the
        // standard outside-error-field idiom: gate on FieldFilled(Note), Note placed filled in every case.
        val withRule = RuntimeLaws.withRule(base, "/Policy", "SumR", "../Note", "SumR",
            "FieldFilled(Note) And Sum(Items*/Amount) == 5.00")
        val kModel = RuntimeLaws.withComputation(withRule, "/Policy", "LabelComp", "../Label", "\"X\"")
        val def = ModelLoader.load(kModel)

        fun outcomes(amount: String): Triple<Boolean, Boolean, Boolean> {
            val kDoc = RuntimeLaws.doc().group("/Policy", 1).group("/Policy/Items", 1, 1)
                .field(AMOUNT, amount, 1, 1, 1).field(NOTE, "keep", 1, 1)
            val groovy = RuntimeLaws.messagesAfterCompute(kModel, kDoc, java.util.Locale.US)
                .any { it.code() == "SumR" }
            val kDoc2 = RuntimeLaws.doc().group("/Policy", 1).group("/Policy/Items", 1, 1)
                .field(AMOUNT, amount, 1, 1, 1).field(NOTE, "keep", 1, 1)
            val javaStatic = RuntimeLaws.kernelJavaEngine(kModel).messagesAfterCompute(kDoc2)
                .any { it.code() == "SumR" }
            val iDoc = def.newDocument().group("/Policy", 1).group("/Policy/Items", 1, 1)
                .field(AMOUNT, amount, 1, 1, 1).field(NOTE, "keep", 1, 1)
            val interp = DmInterpreter(def)
            val fired = interp.validate(interp.applyComputations(iDoc)).messages.any { it.code == "SumR" }
            return Triple(groovy, javaStatic, fired)
        }

        val (g1, j1, i1) = outcomes("5")
        assertFalse(g1, "constraint-invalid cell — groovy suppresses after apply")
        assertFalse(j1, "constraint-invalid cell — java-static suppresses after apply")
        assertFalse(i1, "constraint-invalid cell — interpreter must keep the constraints on the applied copy")
        val (g2, j2, i2) = outcomes("5.00")
        assertTrue(g2 && j2 && i2, "control — the rule fires on a valid 5.00 in all three engines")
    }
}
