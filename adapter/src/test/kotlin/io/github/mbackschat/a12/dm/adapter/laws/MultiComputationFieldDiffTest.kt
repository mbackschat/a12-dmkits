package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — IF87 (graduated from the IG44 residual): a computed field targeted by MULTIPLE computations
 * (the composed base + subtype-override shape — a single flat model rejects duplicate computations, so this only
 * arises via model composition/includes). Code generation FLATTENS a field's computations, in DOCUMENT
 * (group-declaration) order, into ONE per-field first-match method, and the first **SELECTED** alternative ends
 * that scan — whether or not its operation stores a value (SPEC-2026-07-26-03). Only a computation that was never
 * selected (common precondition false / no guard matched) is a NO-OP that control falls through. The store-level
 * *"for empty values, do like no calculation was done"* (`CalculationController.handleBerechnetenWert`) decides
 * STORAGE and cannot resurrect the scan. The interpreter formerly DEDUPED by target path, keeping only the FIRST
 * computation even when EMPTY; it then over-corrected to first-NON-EMPTY-wins, which let a later computation
 * overwrite a selected-but-empty earlier result — the divergence the selection cases below separate.
 *
 * Both kernel strategies share the runtime calc controller ⟹ codegen-parity (static-JAVA tri-checked).
 *
 * Own-domain (a quote whose `Price` is computed by a base calc in group `Base` and a subtype calc in group `Sub`).
 */
class MultiComputationFieldDiffTest {

    private val base = DocumentModelDraft.named("quote").locale("en_US")
        .rootGroup("Quote") { q ->
            q.numberField("Price") { it.fractionDigits(2) }        // the shared computed target
            q.numberField("Warn") { }                              // a dependent computed field (scale 0)
            q.stringField("Flag") { }                              // a gate for the base calc's precondition
            q.stringField("Note") { }                              // a shared STRING computed target
            q.group("Base") { g -> g.numberField("BaseAmt") { it.fractionDigits(2) }; g.numberField("BadBase") { it.fractionDigits(2, 2) }; g.stringField("BaseText") { } }
            q.group("Sub") { g -> g.numberField("SubAmt") { it.fractionDigits(2) }; g.numberField("BadSub") { it.fractionDigits(2, 2) } }
        }.build().toJson()

    // Price computations live in the sibling subgroups (element-relative `../../Price` reaches the shared target).
    private fun priceUncond(m: String, group: String, name: String, op: String) =
        RuntimeLaws.withComputation(m, group, name, "../../Price", op)
    private fun priceGuarded(m: String, group: String, name: String, guard: String, op: String) =
        RuntimeLaws.withTieredComputation(m, group, name, "../../Price", guard, listOf(RuntimeLaws.CompAlt(null, op)))

    /** groovy-dynamic == java-static == interpreter compute outcomes, as a `path → outcome` map (every target seeded). */
    private fun agree(kModel: String, msg: String, expect: Map<String, String>, build: (Both) -> Unit) {
        val def = ModelLoader.load(kModel)
        val b = Both(RuntimeLaws.doc(), def.newDocument()); build(b)
        val groovy = RuntimeLaws.compute(kModel, b.kDoc).associate { it.path() to kSig(it) }
        val engine = RuntimeLaws.kernelJavaEngine(kModel)
        val kDoc2 = RuntimeLaws.doc(); build(Both(kDoc2, def.newDocument()))
        val javaStatic = engine.computeOutcomesSorted(engine.instance(kDoc2)).associate {
            it.substringBefore('|') to when (val v = it.substringAfter('|')) { "ERR" -> "ERRORED"; "CLEARED" -> "CLEARED"; else -> "VALUE|$v" }
        }
        val interp = DmInterpreter(def).compute(b.iDoc).associate { it.path to iSig(it) }
        assertEquals(expect, groovy, "$msg — the kernel groovy-dynamic outcome")
        assertEquals(groovy, javaStatic, "$msg — groovy vs java-static (tri-check)")
        assertEquals(groovy, interp, "$msg — kernel vs interpreter")
    }

    private fun kSig(c: RuntimeLaws.Computed): String =
        if (c.cleared()) "CLEARED" else if (c.errored()) "ERRORED" else "VALUE|${c.value().orElse("")}"
    private fun iSig(c: io.github.mbackschat.a12.dm.interpreter.model.Computed): String = when (c.outcome) {
        ComputeOutcome.CLEARED -> "CLEARED"; ComputeOutcome.ERRORED -> "ERRORED"; ComputeOutcome.VALUE -> "VALUE|${c.value}"
    }

    private fun seedPriceRows(d: Both) {
        d.group("/Quote", 1).field("/Quote/Price", "99.00", 1, 1)
        d.group("/Quote/Base", 1, 1); d.group("/Quote/Sub", 1, 1)
    }

    // ── value-first wins over a later value (control — the interpreter already keeps the first computation) ──
    @Test fun valueFirstWinsOverLaterValue() {
        val m = priceUncond(priceUncond(base, "/Quote/Base", "CA", "[BaseAmt]"), "/Quote/Sub", "CB", "[SubAmt]")
        agree(m, "Base value(10) is document-first → wins over Sub value(20)", mapOf("/Quote/Price" to "VALUE|10")) { d ->
            seedPriceRows(d); d.field("/Quote/Base/BaseAmt", "10.00", 1, 1, 1); d.field("/Quote/Sub/SubAmt", "20.00", 1, 1, 1)
        }
    }

    // ── an EMPTY base calc is a no-op → control falls through to the later value (was: interp CLEARED) ──
    @Test fun emptyFirstFallsThroughToLaterValue() {
        val m = priceUncond(priceGuarded(base, "/Quote/Base", "CA", "FieldFilled(../Flag)", "[BaseAmt]"), "/Quote/Sub", "CB", "[SubAmt]")
        agree(m, "Base precondition FALSE (empty, no-op) → Sub value(20) wins", mapOf("/Quote/Price" to "VALUE|20")) { d ->
            seedPriceRows(d); d.field("/Quote/Base/BaseAmt", "10.00", 1, 1, 1); d.field("/Quote/Sub/SubAmt", "20.00", 1, 1, 1)
        }
    }

    // ── an empty SECOND calc is a no-op → the first value survives (control) ──
    @Test fun emptySecondIsNoOp() {
        val m = priceGuarded(priceUncond(base, "/Quote/Base", "CA", "[BaseAmt]"), "/Quote/Sub", "CB", "FieldFilled(../Flag)", "[SubAmt]")
        agree(m, "Base value(10) wins; the empty Sub calc is a no-op", mapOf("/Quote/Price" to "VALUE|10")) { d ->
            seedPriceRows(d); d.field("/Quote/Base/BaseAmt", "10.00", 1, 1, 1); d.field("/Quote/Sub/SubAmt", "20.00", 1, 1, 1)
        }
    }

    // ── value-first wins over a later POISON (control — interp keeps the first, valid, computation) ──
    @Test fun valueFirstWinsOverLaterPoison() {
        val m = priceUncond(priceUncond(base, "/Quote/Base", "CA", "[BaseAmt]"), "/Quote/Sub", "CB", "[BadSub]")
        agree(m, "Base value(10) wins; the later invalid-operand Sub calc is never reached", mapOf("/Quote/Price" to "VALUE|10")) { d ->
            seedPriceRows(d); d.field("/Quote/Base/BaseAmt", "10.00", 1, 1, 1); d.field("/Quote/Sub/BadSub", "5", 1, 1, 1)
        }
    }

    // ── poison-first wins over a later value (control — interp keeps the first, poisoning, computation) ──
    @Test fun poisonFirstWinsOverLaterValue() {
        val m = priceUncond(priceUncond(base, "/Quote/Base", "CA", "[BadBase]"), "/Quote/Sub", "CB", "[SubAmt]")
        agree(m, "Base invalid-operand poison is document-first → wins; the later Sub value is never reached", mapOf("/Quote/Price" to "CLEARED")) { d ->
            seedPriceRows(d); d.field("/Quote/Base/BadBase", "5", 1, 1, 1); d.field("/Quote/Sub/SubAmt", "20.00", 1, 1, 1)
        }
    }

    // ── SPEC-2026-07-26-03's discriminator: a SELECTED first computation that stores NO value ───────────────
    // Every case above makes its empty-first computation empty by a FALSE GUARD, i.e. UNSELECTED — and both
    // rival accounts agree there. The separating shape needs the first computation SELECTED (unguarded) with an
    // operation that yields no storable value: a bare copy of an empty STRING source (`VkString.hasValue()` is
    // non-empty text, so an empty String result stores nothing — KERNEL-FINDINGS §11). Number targets cannot
    // show it: a number renders empty-as-0 and stores a real 0.
    private fun noteUncond(m: String, group: String, name: String, op: String) =
        RuntimeLaws.withComputation(m, group, name, "../../Note", op)
    private fun noteGuarded(m: String, group: String, name: String, guard: String, op: String) =
        RuntimeLaws.withTieredComputation(m, group, name, "../../Note", guard, listOf(RuntimeLaws.CompAlt(null, op)))

    private fun seedNoteRows(d: Both) {
        d.group("/Quote", 1).field("/Quote/Note", "STALE", 1, 1)
        d.group("/Quote/Base", 1, 1); d.group("/Quote/Sub", 1, 1)
    }

    @Test fun selectedButEmptyFirstComputationEndsTheScan() {
        val m = noteUncond(noteUncond(base, "/Quote/Base", "NA", "[BaseText]"), "/Quote/Sub", "NB", "\"OVERRIDE\"")
        agree(m, "Base copies an EMPTY string (selected, stores nothing) → the scan ends; Sub never stores",
            mapOf("/Quote/Note" to "CLEARED")) { d -> seedNoteRows(d) }
    }

    @Test fun aFalseGuardStillFallsThroughAcrossComputationBoundaries() {
        val m = noteUncond(noteGuarded(base, "/Quote/Base", "NA", "FieldFilled(../Flag)", "[BaseText]"),
            "/Quote/Sub", "NB", "\"OVERRIDE\"")
        agree(m, "Base precondition FALSE (never selected) → Sub's literal wins — the opposite control",
            mapOf("/Quote/Note" to "VALUE|OVERRIDE")) { d -> seedNoteRows(d) }
    }

    @Test fun aSelectedFirstComputationWithAValueStillWins() {
        val m = noteUncond(noteUncond(base, "/Quote/Base", "NA", "[BaseText]"), "/Quote/Sub", "NB", "\"OVERRIDE\"")
        agree(m, "Base copies a NONEMPTY string → it wins, as it already did", mapOf("/Quote/Note" to "VALUE|FIRST")) { d ->
            seedNoteRows(d); d.field("/Quote/Base/BaseText", "FIRST", 1, 1, 1)
        }
    }

    /**
     * The same discriminator through an all-empty CONCATENATION rather than a bare copy — the exact shape
     * IF123 is about. It is measured, not inferred from the copy case, because IF123's own fall-through
     * consequence was inherited from IF87 rather than observed and turned out to be wrong.
     */
    @Test fun aSelectedAllEmptyConcatenationAlsoEndsTheScan() {
        val m = noteUncond(noteUncond(base, "/Quote/Base", "NA", "[BaseText] + [BaseText]"),
            "/Quote/Sub", "NB", "\"OVERRIDE\"")
        agree(m, "Base concatenates two empty strings (selected, stores nothing) → the scan ends",
            mapOf("/Quote/Note" to "CLEARED")) { d -> seedNoteRows(d) }
    }

    // ── the corpus shape: empty base + poisoning subtype → Price POISONED → a FieldNotFilled(Price) dependent cascades ──
    @Test fun emptyThenPoisonCascadesToDependent() {
        var m = priceGuarded(base, "/Quote/Base", "CA", "FieldFilled(../Flag)", "[BaseAmt]")   // empty (gate false) — document-first
        m = priceUncond(m, "/Quote/Sub", "CB", "[BadSub]")                                          // poison (invalid operand)
        // The dependent lives in Sub (after Base) so the base calc stays document-first — the interp's old
        // order-dependent dedup then keeps the EMPTY base calc, and the divergence is deterministic.
        m = RuntimeLaws.withTieredComputation(m, "/Quote/Sub", "WarnComp", "../../Warn", null,
            listOf(RuntimeLaws.CompAlt("FieldNotFilled(../Price)", "1")))                            // reads Price → poisons
        agree(m, "Price poisoned by the subtype calc → the dependent's FieldNotFilled(Price) read poisons → CLEARED",
            mapOf("/Quote/Price" to "CLEARED", "/Quote/Warn" to "CLEARED")) { d ->
            seedPriceRows(d); d.field("/Quote/Warn", "7", 1, 1)
            d.field("/Quote/Base/BaseAmt", "10.00", 1, 1, 1); d.field("/Quote/Sub/BadSub", "5", 1, 1, 1)
        }
    }
}
