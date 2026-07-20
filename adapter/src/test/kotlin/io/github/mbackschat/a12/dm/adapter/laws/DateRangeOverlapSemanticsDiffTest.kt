package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential + **tri-check** (groovy-dynamic runtime == java-static codegen == interpreter) for the corrected
 * `DateRangesOverlap` / `AtLeastOneDateRangeOverlaps` semantics (IF72). Kernel truth clean-roomed from
 * `RuntimeController.datumsBereicheUeberlappend` / `mindestensEinDatumsBereichUeberlappendFL` + `DatumBereich`:
 *
 *  1. `DateRangesOverlap(op0, op1, …)` is **any-pair among ALL** operands' kept, filled range cells — one
 *     growing set in operand order, fire on the first range overlapping any already-seen member (no
 *     distinguished first operand). So the same row's cell arriving via a scalar ref AND via the star is two
 *     members → the **self-pair** fires; a list-internal pair (arg0 disjoint) fires though arg0-vs-list misses it.
 *  2. Empty / malformed cells are SKIPPED (no unknown-suppression); an INVALID range (start > end) never overlaps.
 *  3. A `Having` filter on a rep-list arg is legal and APPLIED per cell — **keep iff the complete filter is
 *     TRUE**. A `$`-bearing term has no outer binding for a plain predicate's arg, so the codegen makes the
 *     complete comparison/predicate trivially TRUE. Thus `!= $RuleGroup` / `== $RuleGroup` keeps ALL rows.
 *  4. Polarity is **filter-derived**: OMISSION iff a filled cell of a filter-bearing operand was processed
 *     at/before the fire (operand-order-dependent), VALUE otherwise.
 *  5. `AtLeastOneDateRangeOverlaps(scalar, list)` is genuinely scalar-vs-list (no self-pair); a not-filled
 *     scalar → no fire; OMISSION iff the MATCHED container has a filter.
 *
 * Own-domain (an insurance/e-commerce order: coverage windows on the order and per-item). Conditions are the
 * kernel's stored form fed to all three engines (the interpreter's PARSER accepting `Having` on a predicate
 * rep-list arg is under test), so they are authored as the string the kernel emits, not re-derived.
 */
class DateRangeOverlapSemanticsDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")

    // Three item coverages: rows 1 & 2 OVERLAP (Jan and mid-Jan..mid-Feb), row 3 (March) is disjoint from both.
    private val cov = listOf<String?>("01.01.2026-31.01.2026", "15.01.2026-15.02.2026", "01.03.2026-31.03.2026")

    // ---- the tri-differential core ---------------------------------------------------------------------------

    /** A field placement (path + 1-based reps + value) applied identically to the kernel and interpreter docs. */
    private data class Cell(val path: String, val reps: IntArray, val value: String)

    /** The Items rows carrying [coverages] (+ an always-present Sku so each row is instantiated); the caller adds
     *  any /Order-level scalars (CustomerName, CoverageWindow). Group instances are emitted for the kernel doc. */
    private fun itemRows(coverages: List<String?>): List<Cell> = buildList {
        coverages.forEachIndexed { i, c ->
            val r = i + 1
            add(Cell(SKU, intArrayOf(1, r, 1), "S$r"))
            if (c != null) add(Cell(IC, intArrayOf(1, r, 1), c))
        }
    }

    private fun kDoc(scalars: List<Cell>, coverages: List<String?>): RuntimeLaws.Doc {
        val d = RuntimeLaws.doc().group("/Order", 1)
        scalars.forEach { d.field(it.path, it.value, *it.reps) }
        coverages.indices.forEach { d.group("/Order/Items", 1, it + 1) }
        itemRows(coverages).forEach { d.field(it.path, it.value, *it.reps) }
        return d
    }

    private fun iDoc(model: String, scalars: List<Cell>, coverages: List<String?>): Pair<DmInterpreter, Document> {
        val def = ModelLoader.load(model)
        val doc = def.newDocument()
        scalars.forEach { doc.field(it.path, it.value, *it.reps) }
        itemRows(coverages).forEach { doc.field(it.path, it.value, *it.reps) }
        return DmInterpreter(def) to doc
    }

    // The kernel requires the error field to be REFERENCED in the condition (MVK_ERROR_FIELD_NOT_REFERENCED), so
    // each rule's error field is the range field it references: `../ItemCoverage` (a per-row rule on /Order/Items)
    // or `../CoverageWindow` (a rule on /Order). Referenced ≠ filled — an EMPTY referenced range is still legal.
    /** Assert the three engines agree on how many times [code] fires; return the (shared) count. */
    private fun triCount(group: String, errRel: String, code: String, cond: String,
                         scalars: List<Cell>, coverages: List<String?>): Int {
        val model = RuntimeLaws.withRule(order, group, "R_$code", errRel, code, cond)
        val kDoc = kDoc(scalars, coverages)
        val groovy = RuntimeLaws.messages(model, kDoc).count { it.code() == code }
        val engine = RuntimeLaws.kernelJavaEngine(model)
        val javaStatic = engine.messages(engine.instance(kDoc)).count { it.code() == code }
        val (interp, doc) = iDoc(model, scalars, coverages)
        val interpCount = interp.validateFull(doc).count { it.code == code }
        assertEquals(groovy, javaStatic, "$cond — groovy-dynamic vs java-static codegen disagree (tri-check)")
        assertEquals(groovy, interpCount, "$cond — kernel vs interpreter disagree (groovy=$groovy interp=$interpCount)")
        return groovy
    }

    /** Assert the three engines agree on the polarity (OMISSION/VALUE) of the FIRST [code] message. */
    private fun triType(group: String, errRel: String, code: String, cond: String,
                        scalars: List<Cell>, coverages: List<String?>): String? {
        val model = RuntimeLaws.withRule(order, group, "R_$code", errRel, code, cond)
        val kDoc = kDoc(scalars, coverages)
        val groovy = RuntimeLaws.typeOf(model, code, kDoc).map { it.name }.orElse(null)
        val (interp, doc) = iDoc(model, scalars, coverages)
        val interpType = interp.validateFull(doc).firstOrNull { it.code == code }
            ?.let { if (it.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR" }
        assertEquals(groovy, interpType, "$cond — polarity kernel=$groovy interp=$interpType")
        return groovy
    }

    private val acme = Cell(CN, intArrayOf(1, 1), "Acme")
    private fun window(w: String) = listOf(acme, Cell(CW, intArrayOf(1, 1), w))

    // ---- A. any-pair among ALL (no Having) -------------------------------------------------------------------

    /** Rule ON the item row: `DateRangesOverlap(ItemCoverage, ../Items* / ItemCoverage)`. arg0 (the row's own cell)
     *  is a member of the same set as the whole list, so the SELF-PAIR fires — every filled row fires (each row
     *  cross-overlaps another, and even a lone row would fire against its own duplicate). */
    @Test fun selfPairFiresEveryRow() =
        assertEquals(3, triCount("/Order/Items", "../ItemCoverage", "SELF",
            "DateRangesOverlap(ItemCoverage, ../Items*/ItemCoverage)", listOf(acme), cov))

    /** Rule ON /Order: arg0 = CoverageWindow DISJOINT from every item, but items 1 & 2 overlap EACH OTHER →
     *  any-pair fires (the class arg0-vs-list missed; kernel=1 / old-interp=0). */
    @Test fun listInternalPairFires() =
        assertEquals(1, triCount("/Order", "../CoverageWindow", "LIP",
            "DateRangesOverlap(CoverageWindow, Items*/ItemCoverage)", window("01.06.2026-30.06.2026"), cov))

    /** arg0 disjoint AND the two remaining items disjoint from each other → no pair anywhere → no fire. */
    @Test fun noPairNoFire() =
        assertEquals(0, triCount("/Order", "../CoverageWindow", "NOP",
            "DateRangesOverlap(CoverageWindow, Items*/ItemCoverage)", window("01.06.2026-30.06.2026"),
            listOf("01.01.2026-31.01.2026", "01.09.2026-30.09.2026", "01.12.2026-31.12.2026")))

    /** An INVALID range (start after end) never overlaps anything — even an item that SPANS both its inverted
     *  endpoints (`01.05..31.07` covers the `30.06`↔`01.06` window) does not fire, and the remaining items are
     *  disjoint → 0. (`DatumBereich.ueberlappt` returns false for either side invalid; the naive
     *  `end1>=start2 && end2>=start1` without the validity guard would spuriously fire here.) */
    @Test fun invalidRangeNeverOverlaps() =
        assertEquals(0, triCount("/Order", "../CoverageWindow", "INV",
            "DateRangesOverlap(CoverageWindow, Items*/ItemCoverage)", window("30.06.2026-01.06.2026"),
            listOf("01.05.2026-31.07.2026", "01.09.2026-30.09.2026", null)))

    // ---- B. the Having keep-law (rule ON the item row; `$` unbinds) ------------------------------------------

    private fun having(filter: String) = "DateRangesOverlap(ItemCoverage, ../Items*/ItemCoverage Having ($filter))"

    /** An always-FALSE filter drops every star cell → the set is just arg0 (one member) → no pair → 0.
     *  (Proves the kernel APPLIES the Having on a predicate arg — it does not drop the whole filter.) */
    @Test fun alwaysFalseFilterDropsAll() =
        assertEquals(0, triCount("/Order/Items", "../ItemCoverage", "AF",
            having("CurrentRepetition(/Order/Items) != CurrentRepetition(/Order/Items)"), listOf(acme), cov))

    /** A `$`-self-"exclusion" `!=` — the `$` term has no outer binding here, so the complete comparison is
     *  trivially TRUE and keeps ALL rows; the self-pair fires every row. */
    @Test fun dollarSelfExclusionKeepsAll() =
        assertEquals(3, triCount("/Order/Items", "../ItemCoverage", "DSE",
            having("CurrentRepetition(/Order/Items) != CurrentRepetition(\$/Order/Items)"), listOf(acme), cov))

    /** A `$`-self-"inclusion" `==` is likewise trivially TRUE and keeps ALL rows (the mirror of the `!=` case). */
    @Test fun dollarSelfInclusionKeepsAll() =
        assertEquals(3, triCount("/Order/Items", "../ItemCoverage", "DSI",
            having("CurrentRepetition(/Order/Items) == CurrentRepetition(\$/Order/Items)"), listOf(acme), cov))

    /** A FALSE conjunct ANDed with a trivially-TRUE `$` term is FALSE, so it drops all and leaves only arg0. */
    @Test fun falseAndDollarDropsAll() =
        assertEquals(0, triCount("/Order/Items", "../ItemCoverage", "FAD",
            having("CurrentRepetition(/Order/Items) != CurrentRepetition(/Order/Items) " +
                "And CurrentRepetition(/Order/Items) == CurrentRepetition(\$/Order/Items)"), listOf(acme), cov))

    /** A TRUE-valued constant-index filter keeps a subset (rows 1 & 2); with arg0 always a member the tri-check
     *  just has to AGREE (the exact count is the kernel's). */
    @Test fun constantIndexFilterAgrees() =
        triCount("/Order/Items", "../ItemCoverage", "CIX", having("CurrentRepetition(/Order/Items) <= 2"), listOf(acme), cov)

    @Test fun constantIndexFilterRow1Agrees() =
        triCount("/Order/Items", "../ItemCoverage", "CI1", having("CurrentRepetition(/Order/Items) == 1"), listOf(acme), cov)

    /** The one case the characterization left open, now LOCKED (tri-check): a MALFORMED (non-`$`) filter operand
     *  → the comparison is runtime-UNKNOWN → the kernel's compiled boolean filter is not-TRUE → the row is
     *  DROPPED (unlike a `$`-unbound term, which is trivially TRUE → kept). Every star cell drops → the set is
     *  just arg0 per row → no pair → 0. A malformed `Count` (a non-numeric value) is a formal error on `Count`
     *  alone; it does not suppress THIS rule (which references `ItemCoverage`), so the rule is still evaluated. */
    @Test fun malformedFilterOperandDropsAll() =
        assertEquals(0, triCount("/Order/Items", "../ItemCoverage", "MAL", having("[Count] <= 2"),
            listOf(acme) + (1..3).map { Cell(CNT, intArrayOf(1, it, 1), "abc") }, cov))

    // ---- C. AtLeastOneDateRangeOverlaps — scalar-vs-list ----------------------------------------------------

    /** scalar overlaps a list cell → fires (scalar NOT in the set; genuinely scalar-vs-list). */
    @Test fun atLeastOneScalarOverlapsFires() =
        assertEquals(1, triCount("/Order", "../CoverageWindow", "AO1",
            "AtLeastOneDateRangeOverlaps(CoverageWindow In Items*/ItemCoverage)",
            window("10.01.2026-20.01.2026"), cov))

    /** an EMPTY scalar → no fire (FALSE_OR_UNKNOWN — NOT unknown-suppression); items overlapping each other do
     *  NOT rescue it (unlike DateRangesOverlap, there is no list-internal pairing). */
    @Test fun atLeastOneEmptyScalarNoFire() =
        assertEquals(0, triCount("/Order", "../CoverageWindow", "AOE",
            "AtLeastOneDateRangeOverlaps(CoverageWindow In Items*/ItemCoverage)", listOf(acme), cov))

    // ---- D. filter-derived polarity -------------------------------------------------------------------------

    /** No filter anywhere → the fired overlap is a present-value error → VALUE. */
    @Test fun polarityNoFilterIsValue() =
        assertEquals("VALUE_ERROR", triType("/Order", "../CoverageWindow", "PV",
            "DateRangesOverlap(CoverageWindow, Items*/ItemCoverage)", window("10.01.2026-20.01.2026"), cov))

    /** A filter-bearing operand contributed the firing cell → OMISSION (kernel `containsFilter` → TRUE_AF). */
    @Test fun polarityFilterBearingIsOmission() =
        assertEquals("OMISSION_ERROR", triType("/Order/Items", "../ItemCoverage", "PO",
            having("CurrentRepetition(/Order/Items) <= 3"), listOf(acme), cov))

    private companion object {
        const val CN = "/Order/CustomerName"
        const val CW = "/Order/CoverageWindow"
        const val SKU = "/Order/Items/Sku"
        const val IC = "/Order/Items/ItemCoverage"
        const val CNT = "/Order/Items/Count"
    }
}
