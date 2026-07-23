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
 * Differential + **tri-check** (groovy-dynamic runtime == java-static codegen == interpreter) for a `Having`
 * filter on a value-list quantifier's operand (`AtLeastOne/No/NotAllFieldValueIncludedInValueList`) — the
 * kernel ACCEPTS it on BOTH the fields side and the field-ref values side (probed authoring + runtime; IG48).
 *
 * The kernel's filtered-VLQ semantics (30.8.1, both strategies share `RuntimeController`):
 *  - the filter DROPS a row BEFORE the per-cell classification — a dropped row contributes NOTHING (not to
 *    the membership test, `anyEmpty`, or `anyUnknown`);
 *  - keep-law (the IF72 overlap-predicate one): keep iff the `Having` fires TRUE per row — a malformed filter
 *    operand → runtime-UNKNOWN → not-TRUE → DROP;
 *  - the per-cell classification of KEPT rows is unchanged (IF60): present / empty / malformed (an index-field
 *    empty/dup cell → UNKNOWN, field-local);
 *  - POLARITY is witness-/reached-specific (SPEC-2026-07-23-16, IF218): every filtered operand here is the
 *    single fields/values operand, so it IS the deciding match / outside witness / reached values filter —
 *    hence each fired case below is OMISSION (kernel TRUE_AF). A filtered NONwitness beside an unfiltered
 *    decider does NOT escalate — that multi-operand separator estate is `ValueListQuantifierOrderDiffTest`.
 *
 * `ValueListQuantifierStarDiffTest` locks the unfiltered starred/per-cell classification; this adds the filter.
 */
class ValueListQuantifierHavingDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")

    private val CN = "/Order/CustomerName"
    private val SKU = "/Order/Items/Sku"
    private val COUNT = "/Order/Items/Count"
    private val QTY = "/Order/Quantity"
    private val PC = "/Order/ProductCode"

    private fun guarded(cond: String) = "FieldFilled(CustomerName) And ($cond)"

    /** rows = per Items row (sku|null, count|null); fv = extra plain fields. Builds the kernel Doc + interp pair. */
    private fun build(model: String, rows: List<Pair<String?, String?>>, vararg fv: Pair<String, String>):
        Pair<RuntimeLaws.Doc, Pair<DmInterpreter, Document>> {
        val kDoc = RuntimeLaws.doc().group("/Order", 1).field(CN, "Acme", 1, 1)
        val def = ModelLoader.load(model)
        val iDoc = def.newDocument().group("/Order", 1).field(CN, "Acme", 1, 1)
        for ((f, v) in fv) { kDoc.field(f, v, 1, 1); iDoc.field(f, v, 1, 1) }
        rows.forEachIndexed { i, (sku, count) ->
            val r = i + 1
            kDoc.group("/Order/Items", 1, r); iDoc.group("/Order/Items", 1, r)
            if (sku != null) { kDoc.field(SKU, sku, 1, r, 1); iDoc.field(SKU, sku, 1, r, 1) }
            if (count != null) { kDoc.field(COUNT, count, 1, r, 1); iDoc.field(COUNT, count, 1, r, 1) }
        }
        return kDoc to (DmInterpreter(def) to iDoc)
    }

    /** groovy-dynamic == java-static == interpreter on the fired-code SET. */
    private fun agree(cond0: String, rows: List<Pair<String?, String?>>, vararg fv: Pair<String, String>) {
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

    /** groovy-dynamic == interpreter on the fired VLQ polarity (OMISSION/VALUE). */
    private fun agreeType(cond0: String, rows: List<Pair<String?, String?>>, vararg fv: Pair<String, String>) {
        val cond = guarded(cond0)
        val model = RuntimeLaws.withRule(order, "/Order", "VLQ", "../CustomerName", "VLQ", cond)
        val (kDoc, interp) = build(model, rows, *fv)
        val kernel = RuntimeLaws.typeOf(model, "VLQ", kDoc).map { it.name }.orElse(null)
        val msg = interp.first.validate(interp.second).messages.firstOrNull { it.code == "VLQ" }
        val interpType = msg?.let { if (it.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR" }
        assertEquals(kernel, interpType, "cond=`$cond` rows=$rows fv=${fv.toList()}")
    }

    private val H = "Having ([Items/Count] > 5)"        // keep rows whose Count > 5
    private val HK = "Having ([Items/Sku] == \"K\")"     // keep rows whose Sku == K

    // ── AtLeastOne: the filter drops non-matching rows BEFORE membership ─────────────────────────────
    @Test fun atLeastOneFilterDropsBeforeMembership() {
        // A@row1 would match but Count=3 ≤ 5 → dropped; only C@row2 (Count10) kept, ∉ {A,B} → no fire.
        agree("AtLeastOneFieldValueIncludedInValueList(Items*/Sku $H In \"A\", \"B\")",
            listOf("A" to "3", "C" to "10"))
        // A@row1 kept (Count10) ∈ {A,B} → fire.
        agree("AtLeastOneFieldValueIncludedInValueList(Items*/Sku $H In \"A\", \"B\")",
            listOf("A" to "10", "C" to "3"))
        // malformed filter operand → drop the row (keep-law): A@row1 Count malformed → dropped; C kept ∉ → no fire.
        agree("AtLeastOneFieldValueIncludedInValueList(Items*/Sku $H In \"A\")",
            listOf("A" to "abc", "C" to "10"))
        // empty value SET guard still holds under a filter.
        agree("AtLeastOneFieldValueIncludedInValueList(Items*/Count $HK In $QTY)", listOf("K" to "5"))
    }

    // ── No: a dropped row's cell must not poison/count; fires vacuously when the filter drops all ─────
    @Test fun noFilterDropsThenClassifiesKept() {
        agree("NoFieldValueIncludedInValueList(Items*/Sku $H In \"A\")", listOf("A" to "3", "C" to "10"))
        agree("NoFieldValueIncludedInValueList(Items*/Sku $H In \"A\")", listOf("A" to "10", "C" to "3"))
        // kept row with an empty INDEX cell → mandatory-empty → UNKNOWN → poisons No.
        agree("NoFieldValueIncludedInValueList(Items*/Sku $H In \"A\")", listOf(null to "10", "C" to "3"))
        // Count (non-index) star: a dropped row's empty Count must not poison; a kept K=7 ∉ {Qty5} fires.
        agree("NoFieldValueIncludedInValueList(Items*/Count $HK In $QTY)", listOf("K" to "7", "C" to null), QTY to "5")
        // filter drops ALL rows → kept set empty → No fires vacuously.
        agree("NoFieldValueIncludedInValueList(Items*/Count $HK In $QTY)", listOf("X" to "5", "Y" to "9"), QTY to "5")
    }

    // ── NotAll: needs a filled kept cell; no kept row → no fire ──────────────────────────────────────
    @Test fun notAllNeedsAKeptFilledCell() {
        agree("NotAllFieldValuesIncludedInValueList(Items*/Count $HK In $QTY)", listOf("K" to "9", "X" to "7"), QTY to "7")
        agree("NotAllFieldValuesIncludedInValueList(Items*/Count $HK In $QTY)", listOf("K" to "7", "X" to "9"), QTY to "7")
        agree("NotAllFieldValuesIncludedInValueList(Items*/Count $HK In $QTY)", listOf("X" to "9", "Y" to "7"), QTY to "7")
    }

    // ── values-side Having (values as field refs → entityListe → filterable) ─────────────────────────
    @Test fun valuesSideHaving() {
        agree("AtLeastOneFieldValueIncludedInValueList(ProductCode In Items*/Sku $H)",
            listOf("A" to "3", "C" to "10"), PC to "A")
        agree("AtLeastOneFieldValueIncludedInValueList(ProductCode In Items*/Sku $H)",
            listOf("A" to "10", "C" to "3"), PC to "A")
    }

    // ── POLARITY: the single filtered operand is the decider/witness/reached filter → OMISSION on fire ─
    @Test fun filterDerivedOmissionPolarity() {
        // AtLeastOne is VALUE unfiltered; with the DECIDING operand filtered (even by a filter that drops
        // nothing) a fire is OMISSION.
        agreeType("AtLeastOneFieldValueIncludedInValueList(Items*/Sku Having ([Items/Count] > 0) In \"A\", \"B\")",
            listOf("A" to "10", "C" to "10"))
        agreeType("AtLeastOneFieldValueIncludedInValueList(Items*/Sku $H In \"A\", \"B\")",
            listOf("A" to "10", "C" to "3"))
        // baseline: unfiltered AtLeastOne fire is VALUE (guards the "filter forces OMISSION" is filter-scoped).
        agreeType("AtLeastOneFieldValueIncludedInValueList(Items*/Sku In \"A\", \"B\")",
            listOf("A" to "10", "C" to "3"))
        // No + NotAll fired under a filter → OMISSION.
        agreeType("NoFieldValueIncludedInValueList(Items*/Sku $H In \"A\")", listOf("A" to "3", "C" to "10"))
        agreeType("NotAllFieldValuesIncludedInValueList(Items*/Count $HK In $QTY)", listOf("K" to "9", "X" to "7"), QTY to "7")
    }
}
