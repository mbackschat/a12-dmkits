package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft.GroupBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.function.Consumer

/**
 * Differential — the kernel's **compute-time poison-on-read** semantics (IF85, graduated from IG54): during
 * `compute()`, formal invalidity is NOT validation's "excluded from the tally" — the invalid entities are
 * converted so that ANY read (a value read, a presence check, a quantifier scan visit, a `Having` filter read)
 * of (a) a formally-invalid INPUT field or (b) a computed dependency that ended INVALID (errored value / itself
 * poisoned) aborts the reading computation instance: it is marked "fehlerhaft berechnet" (silently), the
 * REMAINING alternatives are skipped, and it produces NO value — reported CLEARED when the input had the cell
 * filled, absent otherwise — and, being invalid, poisons its own dependents' reads transitively
 * (`CalculationCache.getWertXML`/`isEntityFilled` → `CalculationFieldException`, `markiereAlsFehlerhaftBerechnet`).
 *
 * The poison is READ-driven, so evaluation ORDER is observable and must match:
 * - the fill-quantifier scans stop at their deciding cell (`AtLeastOneFieldFilled` at the first filled cell,
 *   `NoFieldFilled` at the first filled, `AllFieldsFilled`/`NotAllFieldsFilled` at the first empty) — a cell
 *   after the stop is never read, so a later invalid cell does NOT poison;
 * - `And` short-circuits a not-TRUE left (FALSE and UNKNOWN alike — the generated `isKnownAndTrue` collapse),
 *   `Or` a TRUE left;
 * - a compute-side `Having` keeps only TRUE rows (the 2-valued collapse — validation's not-FALSE keep-law does
 *   not apply on the compute path).
 *
 * A **precondition-false / no-alternative clear stays a plain clear** — dependents read it EMPTY (IF77's
 * boundary, unchanged). Own-domain (e-commerce cart); tri-check groovy-dynamic == java-static == interpreter.
 */
class ComputePoisonReadDiffTest {

    private val base = DocumentModelDraft.named("cart").locale("en_US")
        .rootGroup("Cart", Consumer { g: GroupBuilder ->
            g.numberField("Flag", Consumer { })                              // downstream computed target (scale 0)
            g.numberField("Bad", Consumer { })                               // invalid-value holder (scale 0)
            g.numberField("Ref", Consumer { })                               // computed dependency target (scale 0)
            g.numberField("Src", Consumer { })                               // plain operand
            g.numberField("Cap", Consumer { it.max("999") })                 // capped target (an ERRORED value)
            g.numberField("Ref2", Consumer { it.fractionDigits(2, 2) })      // scale-2 dependency target (Sum)
            g.stringField("Name", Consumer { })                              // empty-string comparison → UNKNOWN
            g.group("Lines", Consumer { l ->
                l.repeatable(99).indexField("ItemId")
                    .stringField("ItemId", Consumer { })
                    .numberField("Price", Consumer { it.fractionDigits(2, 2) })
                    .numberField("Qty", Consumer { })
                    .stringField("Tag", Consumer { })
            })
        }).build().toJson()

    private class Both(val kDoc: RuntimeLaws.Doc, val iDoc: Document) {
        fun group(path: String, vararg reps: Int): Both { kDoc.group(path, *reps); iDoc.group(path, *reps); return this }
        fun field(path: String, v: String, vararg reps: Int): Both { kDoc.field(path, v, *reps); iDoc.field(path, v, *reps); return this }
    }

    /** All three engines' compute outcomes as a `path → outcome` map; asserts groovy == java-static == interp.
     *  Every computed target the scenario cares about is SEEDED, so a kernel clear is reported and the maps
     *  compare directly (the unseeded reporting split is pinned separately below). */
    private fun agree(kModel: String, msg: String, expect: Map<String, String>, build: (Both) -> Unit) {
        val def = ModelLoader.load(kModel)
        val b = Both(RuntimeLaws.doc(), def.newDocument())
        build(b)
        val groovy = RuntimeLaws.compute(kModel, b.kDoc).associate { it.path() to kSig(it) }
        val engine = RuntimeLaws.kernelJavaEngine(kModel)
        val kDoc2 = RuntimeLaws.doc()
        build(Both(kDoc2, def.newDocument()))
        val javaStatic = engine.computeOutcomesSorted(engine.instance(kDoc2)).associate {
            it.substringBefore('|') to when (val v = it.substringAfter('|')) {
                "ERR" -> "ERRORED"; "CLEARED" -> "CLEARED"; else -> "VALUE|$v"
            }
        }
        val interp = DmInterpreter(def).compute(b.iDoc).associate { it.path to iSig(it) }
        assertEquals(expect, groovy, "$msg — the kernel groovy-dynamic outcome")
        assertEquals(groovy, javaStatic, "$msg — groovy vs java-static (tri-check)")
        assertEquals(groovy, interp, "$msg — kernel vs interpreter")
    }

    // ERRORED compares without its value (the java-static harness leg does not render one; the errored-value
    // parity is ComputeErrorBucketDiffTest's lock).
    private fun kSig(c: RuntimeLaws.Computed): String =
        if (c.cleared()) "CLEARED" else if (c.errored()) "ERRORED" else "VALUE|${c.value().orElse("")}"

    private fun iSig(c: io.github.mbackschat.a12.dm.interpreter.model.Computed): String = when (c.outcome) {
        ComputeOutcome.CLEARED -> "CLEARED"
        ComputeOutcome.ERRORED -> "ERRORED"
        ComputeOutcome.VALUE -> "VALUE|${c.value}"
    }

    private fun flagQuant() = RuntimeLaws.withTieredComputation(base, "/Cart", "FlagComp", "../Flag", null,
        listOf(RuntimeLaws.CompAlt("AtLeastOneFieldFilled(Lines* / Price)", "1")))

    // ── the quantifier scan: poison is read-order-dependent ────────────────────────────────────────────────

    @Test fun atLeastOneVisitingAnInvalidCellBeforeTheFirstFilledPoisons() =
        agree(flagQuant(), "invalid Price in row 1 is read before the filled row 2 → poison",
            mapOf("/Cart/Flag" to "CLEARED")) { d ->
            d.group("/Cart", 1).field("/Cart/Flag", "9", 1, 1)
            d.group("/Cart/Lines", 1, 1).field("/Cart/Lines/ItemId", "K1", 1, 1, 1).field("/Cart/Lines/Price", "5", 1, 1, 1)
            d.group("/Cart/Lines", 1, 2).field("/Cart/Lines/ItemId", "K2", 1, 2, 1).field("/Cart/Lines/Price", "3.10", 1, 2, 1)
        }

    @Test fun atLeastOneStopsAtTheFirstFilledCellBeforeALaterInvalidOne() =
        agree(flagQuant(), "filled row 1 decides TRUE — the invalid row 2 is never read",
            mapOf("/Cart/Flag" to "VALUE|1")) { d ->
            d.group("/Cart", 1).field("/Cart/Flag", "9", 1, 1)
            d.group("/Cart/Lines", 1, 1).field("/Cart/Lines/ItemId", "K1", 1, 1, 1).field("/Cart/Lines/Price", "3.10", 1, 1, 1)
            d.group("/Cart/Lines", 1, 2).field("/Cart/Lines/ItemId", "K2", 1, 2, 1).field("/Cart/Lines/Price", "5", 1, 2, 1)
        }

    @Test fun noFieldFilledVisitingAnInvalidCellPoisons() {
        val m = RuntimeLaws.withTieredComputation(base, "/Cart", "FlagComp", "../Flag", null,
            listOf(RuntimeLaws.CompAlt("NoFieldFilled(Lines* / Price)", "1")))
        agree(m, "the NoFieldFilled scan reads the invalid row-1 cell → poison",
            mapOf("/Cart/Flag" to "CLEARED")) { d ->
            d.group("/Cart", 1).field("/Cart/Flag", "9", 1, 1)
            d.group("/Cart/Lines", 1, 1).field("/Cart/Lines/ItemId", "K1", 1, 1, 1).field("/Cart/Lines/Price", "5", 1, 1, 1)
            d.group("/Cart/Lines", 1, 2).field("/Cart/Lines/ItemId", "K2", 1, 2, 1)
        }
    }

    // ── direct presence reads ──────────────────────────────────────────────────────────────────────────────

    private fun flagNotFilledBad() = RuntimeLaws.withTieredComputation(base, "/Cart", "FlagComp", "../Flag", null,
        listOf(RuntimeLaws.CompAlt("FieldNotFilled(Bad)", "1")))

    @Test fun directPresenceCheckOnAnInvalidInputFieldPoisons() =
        agree(flagNotFilledBad(), "FieldNotFilled reads the invalid Bad → poison",
            mapOf("/Cart/Flag" to "CLEARED")) { d ->
            d.group("/Cart", 1).field("/Cart/Flag", "9", 1, 1).field("/Cart/Bad", "7.5", 1, 1)
        }

    @Test fun directPresenceCheckOnAnEmptyFieldComputes() =
        agree(flagNotFilledBad(), "Bad empty → FieldNotFilled TRUE (empty is a clean read)",
            mapOf("/Cart/Flag" to "VALUE|1")) { d ->
            d.group("/Cart", 1).field("/Cart/Flag", "9", 1, 1)
        }

    // ── the cascade: poisoned vs cleared dependencies read differently ─────────────────────────────────────

    private fun refThenFlag(refOp: String, refCommon: String? = null): String {
        var m = RuntimeLaws.withTieredComputation(base, "/Cart", "RefComp", "../Ref", refCommon,
            listOf(RuntimeLaws.CompAlt(null, refOp)))
        m = RuntimeLaws.withTieredComputation(m, "/Cart", "FlagComp", "../Flag", null,
            listOf(RuntimeLaws.CompAlt("FieldNotFilled(Ref)", "1")))
        return m
    }

    @Test fun presenceCheckOnAnOperandInvalidityPoisonedDependencyPoisons() =
        // Ref = [Bad] with Bad invalid → Ref's read throws → Ref is INVALID (not merely cleared) → Flag's
        // FieldNotFilled(Ref) read throws too → both CLEARED. The IG54 workspace shape, second clear-cause.
        agree(refThenFlag("[Bad]"), "an operand-invalidity clear is a POISON — it cascades as invalid",
            mapOf("/Cart/Ref" to "CLEARED", "/Cart/Flag" to "CLEARED")) { d ->
            d.group("/Cart", 1).field("/Cart/Flag", "9", 1, 1).field("/Cart/Ref", "4", 1, 1).field("/Cart/Bad", "7.5", 1, 1)
        }

    @Test fun presenceCheckOnAPreconditionClearedDependencyReadsEmpty() =
        // IF77's boundary holds: a precondition-false clear is SILENT — the dependency reads EMPTY downstream.
        agree(refThenFlag("[Src]", refCommon = "FieldFilled(Src)"), "a precondition clear cascades as EMPTY (IF77)",
            mapOf("/Cart/Ref" to "CLEARED", "/Cart/Flag" to "VALUE|1")) { d ->
            d.group("/Cart", 1).field("/Cart/Flag", "9", 1, 1).field("/Cart/Ref", "4", 1, 1)
        }

    @Test fun seededDependentOfAnErroredDependencyIsCleared() {
        // Cap computes 5000 > max 999 → ERRORED; the seeded Flag reads [Cap] → poisoned → CLEARED (IF40's
        // "dependent is absent" is the UNSEEDED case — the kernel reports a clear for an input-FILLED dependent).
        var m = RuntimeLaws.withTieredComputation(base, "/Cart", "CapComp", "../Cap", null,
            listOf(RuntimeLaws.CompAlt(null, "5000")))
        m = RuntimeLaws.withTieredComputation(m, "/Cart", "FlagComp", "../Flag", null,
            listOf(RuntimeLaws.CompAlt(null, "[Cap]")))
        agree(m, "a seeded dependent of an ERRORED dependency reports CLEARED",
            mapOf("/Cart/Cap" to "ERRORED", "/Cart/Flag" to "CLEARED")) { d ->
            d.group("/Cart", 1).field("/Cart/Flag", "9", 1, 1)
        }
    }

    @Test fun aggregatePoisonedDependencyPoisonsTheDependentChain() {
        // Ref2 = Sum over an invalid cell → poisoned (not a plain clear); Flag's presence check on Ref2 poisons.
        var m = RuntimeLaws.withTieredComputation(base, "/Cart", "Ref2Comp", "../Ref2", null,
            listOf(RuntimeLaws.CompAlt(null, "Sum(Lines* / Price)")))
        m = RuntimeLaws.withTieredComputation(m, "/Cart", "FlagComp", "../Flag", null,
            listOf(RuntimeLaws.CompAlt("FieldNotFilled(Ref2)", "1")))
        agree(m, "a value aggregate reads ALL selected cells — one invalid poisons the chain",
            mapOf("/Cart/Ref2" to "CLEARED", "/Cart/Flag" to "CLEARED")) { d ->
            d.group("/Cart", 1).field("/Cart/Flag", "9", 1, 1).field("/Cart/Ref2", "4.00", 1, 1)
            d.group("/Cart/Lines", 1, 1).field("/Cart/Lines/ItemId", "K1", 1, 1, 1).field("/Cart/Lines/Price", "5", 1, 1, 1)
            d.group("/Cart/Lines", 1, 2).field("/Cart/Lines/ItemId", "K2", 1, 2, 1).field("/Cart/Lines/Price", "3.10", 1, 2, 1)
        }
    }

    // ── the boolean connectives short-circuit (the isKnownAndTrue collapse) ────────────────────────────────

    private fun twoAlt(pre1: String): String = RuntimeLaws.withTieredComputation(base, "/Cart", "FlagComp", "../Flag", null,
        listOf(RuntimeLaws.CompAlt(pre1, "1"), RuntimeLaws.CompAlt("FieldFilled(Src)", "2")))

    @Test fun andShortCircuitsAFalseLeftBeforeAPoisoningRight() =
        agree(twoAlt("FieldNotFilled(Src) And AtLeastOneFieldFilled(Lines* / Price)"),
            "left FALSE → the poisoning right conjunct is never evaluated → alternative 2 fires",
            mapOf("/Cart/Flag" to "VALUE|2")) { d ->
            d.group("/Cart", 1).field("/Cart/Flag", "9", 1, 1).field("/Cart/Src", "1", 1, 1)
            d.group("/Cart/Lines", 1, 1).field("/Cart/Lines/ItemId", "K1", 1, 1, 1).field("/Cart/Lines/Price", "5", 1, 1, 1)
        }

    @Test fun andShortCircuitsAnUnknownLeftBeforeAPoisoningRight() =
        agree(twoAlt("[Name] == \"X\" And AtLeastOneFieldFilled(Lines* / Price)"),
            "left UNKNOWN (empty-string comparison) also collapses not-TRUE → right never evaluated",
            mapOf("/Cart/Flag" to "VALUE|2")) { d ->
            d.group("/Cart", 1).field("/Cart/Flag", "9", 1, 1).field("/Cart/Src", "1", 1, 1)
            d.group("/Cart/Lines", 1, 1).field("/Cart/Lines/ItemId", "K1", 1, 1, 1).field("/Cart/Lines/Price", "5", 1, 1, 1)
        }

    @Test fun aPoisonInAnAlternativePreconditionSkipsTheRemainingAlternatives() =
        agree(twoAlt("AtLeastOneFieldFilled(Lines* / Price) And FieldNotFilled(Src)"),
            "the poisoning quantifier is the LEFT conjunct → read → poison; alternative 2 is NOT tried",
            mapOf("/Cart/Flag" to "CLEARED")) { d ->
            d.group("/Cart", 1).field("/Cart/Flag", "9", 1, 1).field("/Cart/Src", "1", 1, 1)
            d.group("/Cart/Lines", 1, 1).field("/Cart/Lines/ItemId", "K1", 1, 1, 1).field("/Cart/Lines/Price", "5", 1, 1, 1)
        }

    @Test fun orShortCircuitsATrueLeftBeforeAPoisoningRight() {
        val m = RuntimeLaws.withTieredComputation(base, "/Cart", "FlagComp", "../Flag", null,
            listOf(RuntimeLaws.CompAlt("FieldFilled(Src) Or AtLeastOneFieldFilled(Lines* / Price)", "1")))
        agree(m, "left TRUE decides the Or — the poisoning right is never evaluated",
            mapOf("/Cart/Flag" to "VALUE|1")) { d ->
            d.group("/Cart", 1).field("/Cart/Flag", "9", 1, 1).field("/Cart/Src", "1", 1, 1)
            d.group("/Cart/Lines", 1, 1).field("/Cart/Lines/ItemId", "K1", 1, 1, 1).field("/Cart/Lines/Price", "5", 1, 1, 1)
        }
    }

    // ── the Having filter on the compute path ──────────────────────────────────────────────────────────────

    @Test fun havingFilterReadOfAnInvalidCellPoisons() {
        val m = RuntimeLaws.withTieredComputation(base, "/Cart", "Ref2Comp", "../Ref2", null,
            listOf(RuntimeLaws.CompAlt(null, "Sum(Lines* / Price Having ([Lines/Qty] > 0))")))
        agree(m, "the filter reads the invalid row-1 Qty → poison",
            mapOf("/Cart/Ref2" to "CLEARED")) { d ->
            d.group("/Cart", 1).field("/Cart/Ref2", "4.00", 1, 1)
            d.group("/Cart/Lines", 1, 1).field("/Cart/Lines/ItemId", "K1", 1, 1, 1)
                .field("/Cart/Lines/Price", "2.00", 1, 1, 1).field("/Cart/Lines/Qty", "1.5", 1, 1, 1)
            d.group("/Cart/Lines", 1, 2).field("/Cart/Lines/ItemId", "K2", 1, 2, 1)
                .field("/Cart/Lines/Price", "3.10", 1, 2, 1).field("/Cart/Lines/Qty", "2", 1, 2, 1)
        }
    }

    @Test fun computeSideHavingKeepsOnlyTrueRows() {
        // An UNKNOWN filter row (empty-string comparison) is DROPPED on the compute path — the 2-valued
        // collapse, not validation's not-FALSE keep-law.
        val m = RuntimeLaws.withTieredComputation(base, "/Cart", "Ref2Comp", "../Ref2", null,
            listOf(RuntimeLaws.CompAlt(null, "Sum(Lines* / Price Having ([Lines/Tag] == \"X\"))")))
        agree(m, "row 1's UNKNOWN filter drops the row — Sum = row 2 only",
            mapOf("/Cart/Ref2" to "VALUE|3.10")) { d ->
            d.group("/Cart", 1).field("/Cart/Ref2", "4.00", 1, 1)
            d.group("/Cart/Lines", 1, 1).field("/Cart/Lines/ItemId", "K1", 1, 1, 1)
                .field("/Cart/Lines/Price", "2.00", 1, 1, 1)
            d.group("/Cart/Lines", 1, 2).field("/Cart/Lines/ItemId", "K2", 1, 2, 1)
                .field("/Cart/Lines/Price", "3.10", 1, 2, 1).field("/Cart/Lines/Tag", "X", 1, 2, 1)
        }
    }

    // ── the reporting contract for an UNSEEDED poisoned dependent ──────────────────────────────────────────

    @Test fun unseededPoisonedDependentIsAbsentOnTheKernelAndClearedOnTheInterpreter() {
        // The kernel reports a clear only for an input-FILLED cell; the interpreter reports EVERY instance
        // (report-all) — kernelComputeGranularity projects its unseeded CLEARED away (KERNEL-FINDINGS
        // "compute reporting granularity", the IF40 refinement).
        val m = refThenFlag("[Bad]")
        val def = ModelLoader.load(m)
        val b = Both(RuntimeLaws.doc(), def.newDocument())
        b.group("/Cart", 1).field("/Cart/Bad", "7.5", 1, 1)   // Ref + Flag both UNSEEDED
        val kernel = RuntimeLaws.compute(m, b.kDoc).associate { it.path() to kSig(it) }
        val interp = DmInterpreter(def).compute(b.iDoc).associate { it.path to iSig(it) }
        assertEquals(emptyMap<String, String>(), kernel, "unseeded poisoned instances are ABSENT from the kernel report")
        assertEquals(mapOf("/Cart/Ref" to "CLEARED", "/Cart/Flag" to "CLEARED"), interp,
            "the interpreter reports them CLEARED (report-all; the granularity projection drops them for comparison)")
    }
}
