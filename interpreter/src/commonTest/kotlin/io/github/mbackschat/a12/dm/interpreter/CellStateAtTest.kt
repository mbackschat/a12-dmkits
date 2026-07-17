package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.StoredCellState
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.ComputationAlternative
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multiplatform (JVM + JS) lock for [Document.cellStateAt] — the public per-cell read that keeps ABSENT,
 * PRESENT-EMPTY, and PRESENT-VALUE(raw) distinct (IG72 → IF126). The contract mirrors the kernel's
 * apply-to-a-fresh-`DocumentV2` route (`IDocumentComputationResult.applyTo`; KERNEL-FINDINGS §11
 * "post-application cell state"): a VALUE lands as present-value (creating the cell if absent), a
 * cleared/errored instance empties a PRESENT cell but never creates one (an absent target stays absent),
 * and untouched cells keep their placement state. The kernel differential (tri-check, both strategies)
 * is `adapter.laws.AppliedCellStateDiffTest`; this pins the read on both platforms.
 */
class CellStateAtTest {

    private companion object {
        const val HOLDER = "/Policy/HolderName"
        const val RIDER = "/Policy/RiderCode"
        const val LABEL = "/Policy/Label"
        const val NOTE = "/Policy/Note"
        val CELL = listOf(1, 1)
    }

    private fun model(labelMinLength: Int? = null, comps: List<ComputationDef> = emptyList()) = ModelDef(
        fields = listOf(
            FieldDef(HOLDER, Kind.STRING),
            FieldDef(RIDER, Kind.STRING),
            FieldDef(LABEL, Kind.STRING, minLength = labelMinLength),
            FieldDef(NOTE, Kind.STRING),
        ),
        rules = emptyList(),
        computations = comps,
    )

    private fun label(op: String) = ComputationDef(LABEL, "/Policy", listOf(ComputationAlternative(null, op)))

    // ---- the plain read (no computations involved) -----------------------------------------------------

    @Test fun anUnplacedCellReadsAbsent() {
        val doc = model().newDocument().group("/Policy", 1)
        assertEquals(StoredCellState.Absent, doc.cellStateAt(LABEL, CELL))
    }

    @Test fun anEmptyPlacedCellReadsPresentEmpty() {
        val doc = model().newDocument().group("/Policy", 1).emptyField(LABEL, 1, 1)
        assertEquals(StoredCellState.PresentEmpty, doc.cellStateAt(LABEL, CELL))
    }

    // A "" value is NO value (the kernel's `DocumentUtils.valueExists`: null or empty string) — every
    // supported ingestion path already maps "" to an empty placement; the programmatic edge reads the same.
    @Test fun anEmptyStringValuedCellReadsPresentEmpty() {
        val doc = model().newDocument().group("/Policy", 1).field(LABEL, "", 1, 1)
        assertEquals(StoredCellState.PresentEmpty, doc.cellStateAt(LABEL, CELL))
    }

    @Test fun aValuedCellReadsPresentValueWithItsRawText() {
        val doc = model().newDocument().group("/Policy", 1).field(LABEL, "GOLD", 1, 1)
        assertEquals(StoredCellState.PresentValue("GOLD"), doc.cellStateAt(LABEL, CELL))
    }

    // The raw STORED text is reported — the eval-side CRLF→LF normalization (IF124) never leaks into this
    // read (the kernel's document store keeps CRLF; only its eval caches normalize).
    @Test fun theRawTextKeepsCrlfUnnormalized() {
        val doc = model().newDocument().group("/Policy", 1).field(LABEL, "A\r\nB", 1, 1)
        assertEquals(StoredCellState.PresentValue("A\r\nB"), doc.cellStateAt(LABEL, CELL))
    }

    // A group path is not a cell — it reads ABSENT (the kernel's directField on a group name is null).
    @Test fun aGroupPathReadsAbsent() {
        val doc = model().newDocument().group("/Policy", 1)
        assertEquals(StoredCellState.Absent, doc.cellStateAt("/Policy", listOf(1)))
    }

    // ---- the post-applyComputations states (the kernel-apply contract) ---------------------------------

    private fun applied(m: ModelDef, build: (Document) -> Unit): Document {
        val doc = m.newDocument().group("/Policy", 1); build(doc)
        return DmInterpreter(m).applyComputations(doc)
    }

    @Test fun aComputedValueIntoAnAbsentCellReadsPresentValue() {
        val doc = applied(model(comps = listOf(label("[HolderName] + [RiderCode]")))) {
            it.field(HOLDER, "ALPHA", 1, 1).field(RIDER, "-GOLD", 1, 1)
        }
        assertEquals(StoredCellState.PresentValue("ALPHA-GOLD"), doc.cellStateAt(LABEL, CELL))
    }

    @Test fun aComputedValueReplacesAStaleCell() {
        val doc = applied(model(comps = listOf(label("[HolderName] + [RiderCode]")))) {
            it.field(HOLDER, "ALPHA", 1, 1).field(RIDER, "-GOLD", 1, 1).field(LABEL, "STALE", 1, 1)
        }
        assertEquals(StoredCellState.PresentValue("ALPHA-GOLD"), doc.cellStateAt(LABEL, CELL))
    }

    // The all-empty concat computes nothing (IF123): a stale-FILLED target is emptied but stays PRESENT —
    // the kernel's cleared instance does `withFieldValue(pointer, null)`, never a removal.
    @Test fun aClearedStaleCellReadsPresentEmptyNeverAbsent() {
        val doc = applied(model(comps = listOf(label("[HolderName] + [RiderCode]")))) {
            it.field(LABEL, "STALE", 1, 1)
        }
        assertEquals(StoredCellState.PresentEmpty, doc.cellStateAt(LABEL, CELL))
    }

    // …and an ABSENT target stays ABSENT: the kernel mints a cleared instance only for an input-FILLED
    // cell, so nothing is ever created by clearing.
    @Test fun aClearedAbsentCellStaysAbsent() {
        val doc = applied(model(comps = listOf(label("[HolderName] + [RiderCode]")))) {
            it.field(NOTE, "keep", 1, 1)
        }
        assertEquals(StoredCellState.Absent, doc.cellStateAt(LABEL, CELL))
    }

    // An ERRORED result (formally invalid for the target) EMPTIES a present cell — the kernel applies the
    // errored instance as a guarded clear.
    @Test fun anErroredResultEmptiesAStaleCell() {
        val doc = applied(model(labelMinLength = 2, comps = listOf(label("[HolderName] + [RiderCode]")))) {
            it.field(HOLDER, "A", 1, 1).field(LABEL, "STALE", 1, 1)
        }
        assertEquals(StoredCellState.PresentEmpty, doc.cellStateAt(LABEL, CELL))
    }

    // …but never CREATES one: the kernel's errored apply is guarded on the instance existing
    // (`ComputedFieldInstanceImpl.applyTo(DocumentV2)`), so an absent target stays absent.
    @Test fun anErroredResultOverAnAbsentCellStaysAbsent() {
        val doc = applied(model(labelMinLength = 2, comps = listOf(label("[HolderName] + [RiderCode]")))) {
            it.field(HOLDER, "A", 1, 1)
        }
        assertEquals(StoredCellState.Absent, doc.cellStateAt(LABEL, CELL))
    }

    // Untouched neighbours keep their placement state through the apply copy.
    @Test fun untouchedCellsKeepTheirStates() {
        val doc = applied(model(comps = listOf(label("[HolderName] + [RiderCode]")))) {
            it.field(HOLDER, "ALPHA", 1, 1).emptyField(RIDER, 1, 1)
        }
        assertEquals(StoredCellState.PresentValue("ALPHA"), doc.cellStateAt(HOLDER, CELL))
        assertEquals(StoredCellState.PresentEmpty, doc.cellStateAt(RIDER, CELL))
        assertEquals(StoredCellState.Absent, doc.cellStateAt(NOTE, CELL))
    }

    // The original document is untouched by applyComputations — the read sees the pre-apply state there.
    @Test fun theOriginalDocumentIsUnchangedByApply() {
        val m = model(comps = listOf(label("[HolderName] + [RiderCode]")))
        val doc = m.newDocument().group("/Policy", 1).field(HOLDER, "ALPHA", 1, 1)
        DmInterpreter(m).applyComputations(doc)
        assertEquals(StoredCellState.Absent, doc.cellStateAt(LABEL, CELL))
    }
}
