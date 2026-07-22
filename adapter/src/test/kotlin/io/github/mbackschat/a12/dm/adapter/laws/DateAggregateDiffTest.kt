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
 * Differential — `MinValue`/`MaxValue`/`Min`/`Max` over **DATE** operands (the WI-7 sweep's incomparable-values crash
 * class — IF58). The kernel evaluates date aggregates through a dedicated `DateCombiner` (`minWertDatum`/`maxWertDatum`,
 * `minDatum`/`maxDatum`) whose semantics differ from the number combiner in three kernel-verified ways:
 *
 *  1. **Empty identity** — an all-empty date list yields a NULL date (`VkDate.NULL_DATE`), and
 *     `vergleicheDATUM` returns `FALSE_OR_UNKNOWN` when either side is null → the comparison does NOT fire.
 *     (The number combiner's empty identity is `0`, IF51 — which crashed the interpreter here: a numeric `0`
 *     compared against an ISO date text.)
 *  2. **Operand-list emptiness** — `Min(dateA, dateB)`'s combine SKIPS a valueless operand (the filled side
 *     wins; `VkDate.combine` keeps the date-bearing value), it does not suppress the whole aggregate. A
 *     formally-INVALID operand still poisons (nicht prüfungsrelevant → `FALSE_OR_UNKNOWN`).
 *  3. **Symmetric polarity** — `DateCombiner` has no directional `kannGroesser/KleinerWerden`; it carries only
 *     the symmetric `nichtAngegeben` (any empty cell, a `Having` filter, or an omitted tail), and a fired date
 *     comparison types `TRUE_AF` (OMISSION) iff either side is nichtAngegeben — regardless of the comparison
 *     direction (unlike the number star's directional fill).
 *
 * Both kernel strategies share this runtime (codegen emits `controller.minWertDatum(...)`), locked tri-way on a
 * representative subset. Conditions stay in raw stored form here because this suite probes runtime semantics;
 * typed DATE operand-list authoring and read-back are locked by `DateMinMaxConditionTest` and
 * `TemporalOperandMinMaxReadTest`.
 */
class DateAggregateDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")

    private val DD = "/Order/DeliveryDate"
    private val OD = "/Order/OrderDate"
    private val AD = "/Order/ApproxDate"
    private val PD = "/Order/Items/PromiseDate"
    private val SKU = "/Order/Items/Sku"

    /** Rows for the kernel doc: each entry = the row's PromiseDate (null = instantiated row, date cell empty). */
    private fun kernelDoc(rows: List<String?>, vararg fv: Pair<String, String>): RuntimeLaws.Doc {
        val d = RuntimeLaws.doc().group("/Order", 1)
        for ((f, v) in fv) d.field(f, v, 1, 1)
        rows.forEachIndexed { i, pd ->
            val r = i + 1
            d.group("/Order/Items", 1, r).field(SKU, "S$r", 1, r, 1)
            if (pd != null) d.field(PD, pd, 1, r, 1)
        }
        return d
    }

    private fun interpDoc(model: String, rows: List<String?>, vararg fv: Pair<String, String>): Pair<DmInterpreter, Document> {
        val def = ModelLoader.load(model)
        val doc = def.newDocument().group("/Order", 1)
        for ((f, v) in fv) doc.field(f, v, 1, 1)
        rows.forEachIndexed { i, pd ->
            val r = i + 1
            doc.group("/Order/Items", 1, r).field(SKU, "S$r", 1, r, 1)
            if (pd != null) doc.field(PD, pd, 1, r, 1)
        }
        return DmInterpreter(def) to doc
    }

    /** Fired-code parity for a rule [cond] (error field `../DeliveryDate`), and NO unsupported degrade. */
    private fun agree(cond: String, rows: List<String?>, vararg fv: Pair<String, String>) {
        val model = RuntimeLaws.withRule(order, "/Order", "DAGG", "../DeliveryDate", "DAGG", cond)
        val kernel = RuntimeLaws.firedCodes(model, kernelDoc(rows, *fv))
        val (interp, doc) = interpDoc(model, rows, *fv)
        val outcome = interp.validate(doc)
        assertTrue(outcome.unsupported.isEmpty()) { "degraded, not evaluated: ${outcome.unsupported}" }
        assertEquals(kernel, outcome.messages.map { it.code }.toSet(), "cond=`$cond` rows=$rows fv=${fv.toList()}")
    }

    /** Fired-polarity parity (kernel `MessageType` vs interpreter [MsgType]) for a fired [cond]. */
    private fun agreeType(cond: String, rows: List<String?>, vararg fv: Pair<String, String>) {
        val model = RuntimeLaws.withRule(order, "/Order", "DAGG", "../DeliveryDate", "DAGG", cond)
        val kernel = RuntimeLaws.typeOf(model, "DAGG", kernelDoc(rows, *fv)).map { it.name }.orElse(null)
        val (interp, doc) = interpDoc(model, rows, *fv)
        val msg = interp.validate(doc).messages.firstOrNull { it.code == "DAGG" }
        val interpType = msg?.let { if (it.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR" }
        assertEquals(kernel, interpType, "cond=`$cond` rows=$rows fv=${fv.toList()}")
    }

    // ── 1. the crash class: all-empty date star → NULL date → the comparison does not fire ────────────────
    @Test fun minValueOverAllEmptyDateStarDoesNotFire() {
        agree("[$DD] < MinValue(/Order/Items*/PromiseDate)", listOf(null, null), DD to "2026-01-10")
        agree("[$DD] > MaxValue(/Order/Items*/PromiseDate)", listOf(null, null), DD to "2026-01-10")
        // zero rows: the star iterates nothing at all — same NULL-date identity
        agree("[$DD] < MinValue(/Order/Items*/PromiseDate)", emptyList(), DD to "2026-01-10")
    }

    // ── 2. filled stars: the min/max date compares chronologically ────────────────────────────────────────
    @Test fun minMaxValueOverFilledDateStar() {
        agree("[$DD] < MinValue(/Order/Items*/PromiseDate)", listOf("2026-02-01", "2026-03-01"), DD to "2026-01-10") // fires
        agree("[$DD] < MinValue(/Order/Items*/PromiseDate)", listOf("2026-02-01", "2026-03-01"), DD to "2026-02-15") // no
        agree("[$DD] > MaxValue(/Order/Items*/PromiseDate)", listOf("2026-02-01", "2026-03-01"), DD to "2026-03-15") // fires
        // a mixed star (one empty cell among filled) still aggregates the filled ones
        agree("[$DD] < MinValue(/Order/Items*/PromiseDate)", listOf("2026-02-01", null), DD to "2026-01-10")         // fires
    }

    // ── 3. polarity: a fired date-star comparison is OMISSION iff nichtAngegeben (symmetric — any empty
    //      cell / un-instantiated tail), NOT the number star's directional Min-shrink/Max-grow rule ─────────
    @Test fun firedDateStarPolarityIsSymmetricNichtAngegeben() {
        // 2 of 50 declared rows instantiated → omitted tail → OMISSION (the directional rule would say VALUE:
        // a min can only shrink, so `<` could "never" be cleared by filling)
        agreeType("[$DD] < MinValue(/Order/Items*/PromiseDate)", listOf("2026-02-01", "2026-03-01"), DD to "2026-01-10")
        agreeType("[$DD] > MaxValue(/Order/Items*/PromiseDate)", listOf("2026-02-01", "2026-03-01"), DD to "2026-03-15")
        // an empty cell among the filled rows → nichtAngegeben → OMISSION
        agreeType("[$DD] < MinValue(/Order/Items*/PromiseDate)", listOf("2026-02-01", null), DD to "2026-01-10")
        // the CROSS directions — where the number star's directional rule would say VALUE (a min can't grow,
        // a max can't shrink) but the kernel's symmetric nichtAngegeben says OMISSION
        agreeType("[$DD] > MinValue(/Order/Items*/PromiseDate)", listOf("2026-02-01", "2026-03-01"), DD to "2026-02-15")
        agreeType("[$DD] < MaxValue(/Order/Items*/PromiseDate)", listOf("2026-02-01", "2026-03-01"), DD to "2026-02-15")
    }

    // ── 4. operand-list Min/Max over plain date fields: a valueless operand is SKIPPED, not suppressing.
    //      The RHS is a German date literal — the kernel types it DATETIME beside the date-valued Min, so the
    //      interpreter must coerce it too (min/max over dates are date-producing) ────────────────────────────
    @Test fun operandListMinOverDatesSkipsEmptyOperand() {
        // OrderDate empty → kernel's combine keeps DeliveryDate's value → fires (2026-03-10 < 2026-03-20)
        agree("Min([$OD], [$DD]) < \"20.03.2026\"", emptyList(), DD to "2026-03-10")
        // both filled → plain chronological min (2026-01-05 < 2026-02-01 → fires; not < 2026-01-01 → no)
        agree("Min([$OD], [$DD]) < \"01.02.2026\"", emptyList(), OD to "2026-01-05", DD to "2026-03-10")
        agree("Min([$OD], [$DD]) < \"01.01.2026\"", emptyList(), OD to "2026-01-05", DD to "2026-03-10")
        // both empty → NULL date → does not fire
        agree("Min([$OD], [$DD]) < \"20.03.2026\"", emptyList())
        // Max mirror
        agree("Max([$OD], [$DD]) > \"01.02.2026\"", emptyList(), DD to "2026-03-10")
    }

    // fired with an empty operand → nichtAngegeben → OMISSION
    @Test fun operandListMinOverDatesPolarity() {
        agreeType("Min([$OD], [$DD]) < \"20.03.2026\"", emptyList(), DD to "2026-03-10")
    }

    // a malformed date operand poisons (nicht prüfungsrelevant) → the rule is suppressed, the formal error fires
    @Test fun operandListMinWithMalformedDateSuppresses() {
        agree("Min([$OD], [$DD]) < \"20.03.2026\"", emptyList(), OD to "not-a-date", DD to "2026-03-10")
    }

    // ── 5. compute: a date-star aggregate computation computes NO value on all-empty (a prefilled target is
    //      CLEARED; an already-empty one reports no kernel outcome), and the min date else ─────────────────
    @Test fun computeMinDateStar() {
        val model = RuntimeLaws.withComputation(order, "/Order", "EARLIEST", "../DeliveryDate",
            "MinValue(/Order/Items*/PromiseDate)")

        // all-empty star, PREFILLED target → both engines clear it (the bug computed the numeric identity 0)
        val kEmpty = RuntimeLaws.compute(model, kernelDoc(listOf(null, null), DD to "2026-05-05"))
        assertTrue(RuntimeLaws.cleared(kEmpty, DD)) { "kernel should clear on all-empty: $kEmpty" }
        val (i1, d1) = interpDoc(model, listOf(null, null), DD to "2026-05-05")
        val iEmpty = i1.compute(d1).single { it.path == DD }
        assertEquals("CLEARED", iEmpty.outcome.name, "interp compute on all-empty star: $iEmpty")

        // filled star → the earliest date, in the target's stored format
        val kVal = RuntimeLaws.computedValue(RuntimeLaws.compute(model,
            kernelDoc(listOf("2026-03-01", "2026-02-01"))), DD).orElse(null)
        val (i2, d2) = interpDoc(model, listOf("2026-03-01", "2026-02-01"))
        val iVal = i2.compute(d2).single { it.path == DD }
        assertEquals("VALUE", iVal.outcome.name, "interp compute on filled star: $iVal")
        assertEquals(kVal, iVal.value, "computed min date")
    }

    // ── 6. codegen parity: both kernel strategies share the DateCombiner runtime ──────────────────────────
    @Test fun codegenEvaluatesDateAggregatesIdentically() {
        agreeTri("[$DD] < MinValue(/Order/Items*/PromiseDate)", listOf(null, null), DD to "2026-01-10")
        agreeTri("[$DD] < MinValue(/Order/Items*/PromiseDate)", listOf("2026-02-01", "2026-03-01"), DD to "2026-01-10")
        agreeTri("Min([$OD], [$DD]) < \"20.03.2026\"", emptyList(), DD to "2026-03-10")
    }

    private fun agreeTri(cond: String, rows: List<String?>, vararg fv: Pair<String, String>) {
        val model = RuntimeLaws.withRule(order, "/Order", "DAGG", "../DeliveryDate", "DAGG", cond)
        val groovy = RuntimeLaws.firedCodes(model, kernelDoc(rows, *fv))
        val engine = RuntimeLaws.kernelJavaEngine(model)
        val javaStatic = engine.firedCodesSorted(engine.instance(kernelDoc(rows, *fv))).toSet()
        val (interp, doc) = interpDoc(model, rows, *fv)
        val outcome = interp.validate(doc)
        assertTrue(outcome.unsupported.isEmpty()) { "degraded: ${outcome.unsupported}" }
        assertEquals(groovy, outcome.messages.map { it.code }.toSet(), "interp≠groovy | cond=`$cond`")
        assertEquals(groovy, javaStatic, "java-static≠groovy | cond=`$cond`")
    }
}
