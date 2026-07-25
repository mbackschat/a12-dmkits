package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Differential + **tri-check** (groovy-dynamic runtime == java-static codegen == interpreter) for the
 * OPERATOR-SPECIFIC pass structures of the multi-operand value-list quantifiers (SPEC-2026-07-23-16) —
 * encounter order and WITNESS-specific filter polarity, which one global per-side UNKNOWN/filter summary
 * cannot express.
 *
 * **Citation scope — what this file does NOT separate (2026-07-25, a12-kernel-lean SPEC-2026-07-25-01).** Every
 * case here rides the ORDINARY (`FL`) emission route. `NotAll`'s fields-presence prepass exists only on that
 * route; the optimized (`OL`) route has no prepass and hoists the values side ahead of the condition. Both
 * accounts predict identical fired-code sets for these inputs (the membership test counts only given cells, so
 * the un-prepassed route collapses the same way), so **do not cite this file as evidence that the prepass is
 * the observable contract** — it locks tri-engine agreement on quantifier ordering, not the presence of a
 * prepass. The route predicate and the equivalence argument: KERNEL-FINDINGS §5 supplement "the value-list
 * quantifiers have TWO emission routes". No corpus fixture reaches the OL thresholds on either side.
 *
 * The kernel (30.8.1, both strategies share `RuntimeController`):
 *
 *  - **`AtLeastOne`** consumes the COMPLETE values side first (`fuegePruefungsrelevanteFeldWerteZuWerteListe`
 *    — present cells only; a values-side filter marks the fire OMISSION only when that filtered operand
 *    contributed a PRESENT member), then scans fields in authored order; the fire's polarity follows the
 *    DECIDING operand (`mindestensEinFeldWertInWerteListeIntern` returns TRUE_AF iff the matching operand
 *    is filtered), never an earlier filtered nonwitness.
 *  - **`No`** materializes values poison-sensitively (`fuegeFeldWerteZuWerteListe` — UNKNOWN member poisons;
 *    empty member / omitted tail / reached filter keep sticky omission), then scans fields in authored cell
 *    order (`keinFeldWertInWerteListeIntern`): a MATCH terminates as non-firing before every later
 *    UNKNOWN/filter/tail; a reached UNKNOWN before the match suppresses.
 *  - **`NotAll`** runs a fields-PRESENCE prepass first (`noEntityFilled` — with no present cell it does not
 *    fire WITHOUT consuming the values side), then values (No-style), then a RESTARTED fields scan
 *    (`nichtAlleFeldWerteInWerteListeIntern` — TRUE_AF iff the outside-WITNESS operand is filtered, plus the
 *    values side's omission escalation).
 *
 * Six separator families S1–S6; `ValueListQuantifierHavingDiffTest` keeps the single-operand filter estate,
 * `ValueListQuantifierStarDiffTest` the unfiltered per-cell classification. The interpreter's JVM+Node peer
 * twin is `interpreter … ValueListQuantifierOrderEvalTest`.
 */
class ValueListQuantifierOrderDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")

    private val CN = "/Order/CustomerName"
    private val PC = "/Order/ProductCode"
    private val QTY = "/Order/Quantity"
    private val SOH = "/Order/StockOnHand"
    private val SKU = "/Order/Items/Sku"
    private val CNT = "/Order/Items/Count"
    private val SQ = "/Order/Items/StockQty"

    private fun guarded(cond: String) = "FieldFilled(CustomerName) And ($cond)"

    /** rows = per Items row (field path → value). Builds the kernel Doc + interpreter pair. */
    private fun build(model: String, rows: List<Map<String, String>>, vararg fv: Pair<String, String>):
        Pair<RuntimeLaws.Doc, Pair<DmInterpreter, Document>> {
        val kDoc = RuntimeLaws.doc().group("/Order", 1).field(CN, "Acme", 1, 1)
        val def = ModelLoader.load(model)
        val iDoc = def.newDocument().group("/Order", 1).field(CN, "Acme", 1, 1)
        for ((f, v) in fv) { kDoc.field(f, v, 1, 1); iDoc.field(f, v, 1, 1) }
        rows.forEachIndexed { i, row ->
            val r = i + 1
            kDoc.group("/Order/Items", 1, r); iDoc.group("/Order/Items", 1, r)
            for ((f, v) in row) { kDoc.field(f, v, 1, r, 1); iDoc.field(f, v, 1, r, 1) }
        }
        return kDoc to (DmInterpreter(def) to iDoc)
    }

    /** groovy-dynamic == java-static == interpreter on the fired-code SET. */
    private fun agree(cond0: String, rows: List<Map<String, String>>, vararg fv: Pair<String, String>) {
        val cond = guarded(cond0)
        val model = RuntimeLaws.withRule(order, "/Order", "VLQ", "../CustomerName", "VLQ", cond)
        val (kDoc, interp) = build(model, rows, *fv)
        val groovy = RuntimeLaws.firedCodes(model, kDoc)
        val engine = RuntimeLaws.kernelJavaEngine(model)
        val javaStatic = engine.firedCodesSorted(engine.instance(kDoc)).toSet()
        val outcome = interp.first.validate(interp.second)
        assertTrue(outcome.unsupported.isEmpty()) { "degraded, not evaluated: ${outcome.unsupported} — cond=$cond" }
        val interpCodes = outcome.messages.map { it.code }.toSet()
        assertEquals(groovy, javaStatic, "$cond rows=$rows — groovy vs java-static disagree (tri-check)")
        assertEquals(groovy, interpCodes, "$cond rows=$rows fv=${fv.toList()} — kernel vs interpreter disagree")
    }

    /** groovy-dynamic == java-static == interpreter on the fired VLQ POLARITY (both kernel routes). */
    private fun agreeType(cond0: String, rows: List<Map<String, String>>, vararg fv: Pair<String, String>) {
        val cond = guarded(cond0)
        val model = RuntimeLaws.withRule(order, "/Order", "VLQ", "../CustomerName", "VLQ", cond)
        val (kDoc, interp) = build(model, rows, *fv)
        val groovy = RuntimeLaws.typeOf(model, "VLQ", kDoc).map { it.name }.orElse(null)
        val engine = RuntimeLaws.kernelJavaEngine(model)
        val javaStatic = engine.validationSignaturesSorted(engine.instance(kDoc))
            .firstOrNull { it.startsWith("VLQ|") }?.split("|")?.get(1)
        val msg = interp.first.validate(interp.second).messages.firstOrNull { it.code == "VLQ" }
        val interpType = msg?.let { if (it.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR" }
        assertEquals(groovy, javaStatic, "cond=`$cond` rows=$rows — groovy vs java-static disagree (tri-check)")
        assertEquals(groovy, interpType, "cond=`$cond` rows=$rows fv=${fv.toList()}")
    }

    private val HSQ = "Having ([Items/StockQty] > 5)"    // keep rows whose StockQty > 5

    // ── S1: No's ORDERED fields scan — a match decides non-firing BEFORE a later UNKNOWN; the reverse
    //        order suppresses; neither fires (the fire set is order-insensitive, all three routes agree).
    //        The decided-vs-suppressed verdict itself is interpreter-tier (kernel merges FALSE/UNKNOWN in
    //        `ValidierungsErgebnis`) — pinned in the JVM/Node peer twin's internal-Tri lock. ─────────────
    @Test fun s1NoFieldsMatchAndUnknownAgreeInBothOrders() {
        // Quantity=5 matches StockOnHand=5 in operand 1; the later Items*/Count cell is malformed (UNKNOWN).
        agree("NoFieldValueIncludedInValueList(Quantity, Items*/Count In StockOnHand)",
            listOf(mapOf(SKU to "A", CNT to "abc")), QTY to "5", SOH to "5")
        // Reverse operand order: the UNKNOWN is reached before the match — suppressed, still no fire.
        agree("NoFieldValueIncludedInValueList(Items*/Count, Quantity In StockOnHand)",
            listOf(mapOf(SKU to "A", CNT to "abc")), QTY to "5", SOH to "5")
        // Fires-control: no match, no UNKNOWN → fires on every route, agreed polarity.
        agree("NoFieldValueIncludedInValueList(Quantity, Items*/Count In StockOnHand)",
            listOf(mapOf(SKU to "A", CNT to "9")), QTY to "7", SOH to "5")
        agreeType("NoFieldValueIncludedInValueList(Quantity, Items*/Count In StockOnHand)",
            listOf(mapOf(SKU to "A", CNT to "9")), QTY to "7", SOH to "5")
    }

    // ── S2: polarity follows the DECIDING operand, not an earlier filtered nonwitness ────────────────
    @Test fun s2polarityFollowsTheUnfilteredDecider() {
        // AtLeastOne: the filtered operand keeps a present NONMATCH ("C" ∉ {A,B}); the unfiltered
        // ProductCode="A" decides → kernel VALUE (TRUE_WF), not the global-filter OMISSION.
        agreeType("AtLeastOneFieldValueIncludedInValueList(Items*/Sku $HSQ, ProductCode In \"A\", \"B\")",
            listOf(mapOf(SKU to "C", SQ to "10")), PC to "A")
        // …and when the filter DROPS its only row entirely — the unfiltered decider still means VALUE.
        agreeType("AtLeastOneFieldValueIncludedInValueList(Items*/Sku $HSQ, ProductCode In \"A\", \"B\")",
            listOf(mapOf(SKU to "A", SQ to "3")), PC to "A")
        // NotAll twin: the filtered operand keeps an IN-SET cell ("A" — a nonwitness); the unfiltered
        // ProductCode="Z" is the outside witness → VALUE.
        agreeType("NotAllFieldValuesIncludedInValueList(Items*/Sku $HSQ, ProductCode In \"A\", \"B\")",
            listOf(mapOf(SKU to "A", SQ to "10")), PC to "Z")
    }

    // ── S3: a filtered MATCHING/outside fields operand — filter polarity retained on the decider ─────
    @Test fun s3filterPolarityRetainedOnTheDecider() {
        // AtLeastOne: unfiltered ProductCode="Z" misses; the FILTERED star supplies the deciding match → OMISSION.
        agreeType("AtLeastOneFieldValueIncludedInValueList(ProductCode, Items*/Sku $HSQ In \"A\", \"B\")",
            listOf(mapOf(SKU to "A", SQ to "10")), PC to "Z")
        // NotAll: unfiltered ProductCode="A" is in-set; the FILTERED star supplies the outside witness → OMISSION.
        agreeType("NotAllFieldValuesIncludedInValueList(ProductCode, Items*/Sku $HSQ In \"A\", \"B\")",
            listOf(mapOf(SKU to "C", SQ to "10")), PC to "A")
    }

    // ── S4: a values-side filter keeps AtLeastOne's polarity only for a PRESENT selection ────────────
    @Test fun s4valuesSideFilterPolarityOnlyForAPresentSelection() {
        val cond = "AtLeastOneFieldValueIncludedInValueList(Quantity In Items*/Count $HSQ, StockOnHand)"
        // The filter keeps a row whose Count cell is EMPTY → the filtered operand selected nothing present
        // → the fire (via StockOnHand=7) stays VALUE.
        agreeType(cond, listOf(mapOf(SKU to "A", SQ to "10")), QTY to "7", SOH to "7")
        // The filter keeps a PRESENT member (9 — not even the matched one) → polarity kept → OMISSION.
        agreeType(cond, listOf(mapOf(SKU to "A", CNT to "9", SQ to "10")), QTY to "7", SOH to "7")
        // The filter drops its only row → nothing selected → VALUE.
        agreeType(cond, listOf(mapOf(SKU to "A", CNT to "9", SQ to "3")), QTY to "7", SOH to "7")
    }

    // ── S5: No retains a REACHED values-side filter's omission potential — even a filtered absence ───
    @Test fun s5reachedValuesFilterKeepsOmissionPotentialForNo() {
        val cond = "NoFieldValueIncludedInValueList(Quantity In Items*/Count $HSQ, StockOnHand)"
        // The values filter drops its only row; Quantity=7 ∉ {5} fires → the reached filter keeps sticky
        // omission → OMISSION (the asymmetry to S4's AtLeastOne, where a filtered absence does NOT escalate).
        agreeType(cond, listOf(mapOf(SKU to "A", CNT to "9", SQ to "3")), QTY to "7", SOH to "5")
        // …and when it keeps only an EMPTY cell — same sticky omission → OMISSION.
        agreeType(cond, listOf(mapOf(SKU to "A", SQ to "10")), QTY to "7", SOH to "5")
    }

    // ── S6: NotAll's fields-PRESENCE prepass decides before the values UNKNOWN can poison ────────────
    @Test fun s6notAllPresencePrepassDecidesBeforeValuesPoison() {
        val cond = "NotAllFieldValuesIncludedInValueList(Quantity In Items*/Count)"
        // NO present fields cell (Quantity empty) + an UNKNOWN values member (malformed Count): the prepass
        // says no-fire WITHOUT consuming the poisoned values side — no fire on every route.
        agree(cond, listOf(mapOf(SKU to "A", CNT to "abc")))
        // Present-fields control: the values UNKNOWN now poisons → still no fire (suppressed).
        agree(cond, listOf(mapOf(SKU to "A", CNT to "abc")), QTY to "7")
        // Fires-control: clean values, present outside witness → fires everywhere, agreed polarity.
        agree(cond, listOf(mapOf(SKU to "A", CNT to "5")), QTY to "7")
        agreeType(cond, listOf(mapOf(SKU to "A", CNT to "5")), QTY to "7")
    }
}
