package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft.GroupBuilder
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.function.Consumer

/**
 * Differential — **`FirstFilledValue` over a DATE / STRING star with NO filled cell** (the WI-T IG44 re-drill
 * residual). IF80 fixed the NUMBER case (empty selection → the fillable `0`, the kernel's `NumberCombiner`), but
 * did so **unconditionally**: `firstFilledValue` returned `Val.Num(0)` for EVERY kind. The kernel's
 * `FirstValueCombiner` has a per-kind family (`FirstValueCombinerDate`/`String`/`Number`), and on an empty
 * selection each returns its OWN kind's "not-given" value — a NOT_GIVEN date / string, never a number. So a DATE
 * `FirstFilledValue(empty)` compared to a date crashed the interpreter (`ValCompare.language(Txt(date), Num(0))`
 * — the IF58 sibling: a numeric identity where a date/string was expected). This was the dominant crash class in
 * the workspaces sweep (`incomparable values Txt(date/string) vs Num`).
 *
 * The kernel's per-kind empty identity, differential-confirmed below:
 *  - NUMBER → the fillable `0` (a VALUE; a `< n` comparison fires) — IF80, unchanged.
 *  - DATE / STRING → a NOT-GIVEN value: the comparison does NOT fire (`vergleiche…` on a not-given side), and a
 *    computation into that target is CLEARED (no value).
 *
 * Own-domain (e-commerce): `FirstFilledValue` over a repeatable `Lines` group's `PromiseDate`/`Note`/`Qty`.
 */
class FirstFilledValueKindDiffTest {

    private val CUTOFF = "/Cart/Cutoff"
    private val LABEL = "/Cart/Label"
    private val FIRST_D = "/Cart/FirstPromise"
    private val FIRST_T = "/Cart/FirstNote"
    private val FIRST_Q = "/Cart/FirstQty"
    private val ITEM = "/Cart/Lines/ItemId"
    private val PROMISE = "/Cart/Lines/PromiseDate"
    private val NOTE = "/Cart/Lines/Note"
    private val QTY = "/Cart/Lines/Qty"

    private val base = DocumentModelDraft.named("cart").locale("en_US")
        .rootGroup("Cart", Consumer { g: GroupBuilder ->
            g.dateField("Cutoff", Consumer { it.format("yyyy-MM-dd") })
                .stringField("Label", Consumer { })
                .dateField("FirstPromise", Consumer { it.format("yyyy-MM-dd") })
                .stringField("FirstNote", Consumer { })
                .numberField("FirstQty", Consumer { it.fractionDigits(0) })
                .group("Lines", Consumer { l ->
                    l.repeatable(99).indexField("ItemId")
                        .stringField("ItemId", Consumer { })
                        .dateField("PromiseDate", Consumer { it.format("yyyy-MM-dd") })
                        .stringField("Note", Consumer { })
                        .numberField("Qty", Consumer { it.fractionDigits(0) })
                })
        }).build().toJson()
    private val m = BoundModel.ofJson(base)
    private fun render(c: Condition): String = DslRenderer().render(c)

    /** Place [rows] Lines (ItemId always filled; the named cell placed when non-null) on both engines' docs. */
    private class Rows(val promise: String? = null, val note: String? = null, val qty: String? = null)

    private fun kdoc(rows: List<Rows>): RuntimeLaws.Doc {
        val d = RuntimeLaws.doc().group("/Cart", 1)
        rows.forEachIndexed { i, r ->
            val row = i + 1
            d.group("/Cart/Lines", 1, row).field(ITEM, "K$row", 1, row, 1)
            r.promise?.let { d.field(PROMISE, it, 1, row, 1) }
            r.note?.let { d.field(NOTE, it, 1, row, 1) }
            r.qty?.let { d.field(QTY, it, 1, row, 1) }
        }
        return d
    }

    private fun idoc(def: io.github.mbackschat.a12.dm.interpreter.model.ModelDef, rows: List<Rows>) =
        def.newDocument().group("/Cart", 1).also { doc ->
            rows.forEachIndexed { i, r ->
                val row = i + 1
                doc.group("/Cart/Lines", 1, row).field(ITEM, "K$row", 1, row, 1)
                r.promise?.let { doc.field(PROMISE, it, 1, row, 1) }
                r.note?.let { doc.field(NOTE, it, 1, row, 1) }
                r.qty?.let { doc.field(QTY, it, 1, row, 1) }
            }
        }

    // ── RULE (the crash path): a date/string ref compared to a FirstFilledValue over an empty star ────────────
    private fun agreeRule(cond: String, errRel: String, code: String, rows: List<Rows>, fv: Map<String, String>, msg: String) {
        val kModel = RuntimeLaws.withRule(base, "/Cart", code, errRel, code, cond)
        val kDoc = kdoc(rows); fv.forEach { (p, v) -> kDoc.field(p, v, 1, 1) }
        val kFires = RuntimeLaws.typeOf(kModel, code, kDoc).isPresent
        val def = ModelLoader.load(kModel)
        val iDoc = idoc(def, rows); fv.forEach { (p, v) -> iDoc.field(p, v, 1, 1) }
        val iFires = DmInterpreter(def).validateFull(iDoc).any { it.code == code }
        assertEquals(kFires, iFires, "$msg  (kernel fires=$kFires interp fires=$iFires)")
    }

    // ── COMPUTE (the outcome): FirstFilledValue-empty into a same-kind target ────────────────────────────────
    private fun agreeCompute(op: String, targetRel: String, target: String, rows: List<Rows>, msg: String) {
        val kModel = RuntimeLaws.withComputation(base, "/Cart", "C", targetRel, op)
        val kSig = kernelSig(RuntimeLaws.compute(kModel, kdoc(rows).also { it.field(target, sentinel(target), 1, 1) })
            .firstOrNull { it.path() == target })
        val def = ModelLoader.load(kModel)
        val iDoc = idoc(def, rows).also { it.field(target, sentinel(target), 1, 1) }
        val c = DmInterpreter(def).compute(iDoc).firstOrNull { it.path == target }
        val iSig = when (c?.outcome) {
            null -> "NONE"; ComputeOutcome.CLEARED -> "CLEARED"; ComputeOutcome.ERRORED -> "ERRORED"
            ComputeOutcome.VALUE -> "VALUE|${c.value}"
        }
        assertEquals(kSig, iSig, "$msg  (kernel=$kSig interp=$iSig)")
    }

    /** A same-kind sentinel seed so a clear is a reported CHANGE. */
    private fun sentinel(target: String) = when (target) {
        FIRST_D -> "2000-01-01"; FIRST_Q -> "999"; else -> "SEED"
    }

    private fun kernelSig(c: RuntimeLaws.Computed?): String = when {
        c == null -> "NONE"; c.cleared() -> "CLEARED"; c.errored() -> "ERRORED"
        else -> "VALUE|${c.value().orElse("")}"
    }

    // Conditions authored typed (the crash was a comparison to the aggregate).
    private val dateCmp = render(m.date(CUTOFF).onOrBefore(Dsl.firstFilledDate(m.flatten("/Cart/Lines*/PromiseDate"))))
    private val strCmp = render(m.str(LABEL).eq(Dsl.firstFilledString(m.flatten("/Cart/Lines*/Note"))))
    private val numCmp = render(Dsl.firstFilledNumber(m.flatten("/Cart/Lines*/Qty")).lt(5))

    // ── the crash: a DATE/STRING FirstFilledValue over an all-empty star, in a comparison ─────────────────────
    @Test fun dateFirstFilledEmptyComparisonAgrees() =
        agreeRule(dateCmp, "../Cutoff", "DC", listOf(Rows(), Rows()), mapOf(CUTOFF to "2024-06-01"),
            "Cutoff <= FirstFilledValue(empty dates) → kernel/interp must agree (was interp CRASH)")
    @Test fun stringFirstFilledEmptyComparisonAgrees() =
        agreeRule(strCmp, "../Label", "SC", listOf(Rows(), Rows()), mapOf(LABEL to "X"),
            "Label == FirstFilledValue(empty strings) → kernel/interp must agree (was interp CRASH)")
    @Test fun numberFirstFilledEmptyComparisonFires() =
        agreeRule(numCmp, "../Lines/Qty", "NC", listOf(Rows(), Rows()), emptyMap(),
            "FirstFilledValue(empty numbers) = 0 < 5 → FIRES (IF80 control)")

    // ── the compute outcome per kind over an empty star ──────────────────────────────────────────────────────
    @Test fun dateFirstFilledEmptyComputeClears() =
        agreeCompute(render2(Dsl.firstFilledDate(m.flatten("/Cart/Lines*/PromiseDate"))), "../FirstPromise", FIRST_D,
            listOf(Rows(), Rows()), "FirstFilledValue(empty dates) → compute CLEARS")
    @Test fun stringFirstFilledEmptyCompute() =
        agreeCompute(render2(Dsl.firstFilledString(m.flatten("/Cart/Lines*/Note"))), "../FirstNote", FIRST_T,
            listOf(Rows(), Rows()), "FirstFilledValue(empty strings) → compute outcome must agree")
    @Test fun numberFirstFilledEmptyComputesZero() =
        agreeCompute(render2(Dsl.firstFilledNumber(m.flatten("/Cart/Lines*/Qty"))), "../FirstQty", FIRST_Q,
            listOf(Rows(), Rows()), "FirstFilledValue(empty numbers) → compute 0 (IF80 control)")

    // ── controls: a FILLED first value computes/compares the real value (per kind) ────────────────────────────
    @Test fun dateFirstFilledComputesTheValue() =
        agreeCompute(render2(Dsl.firstFilledDate(m.flatten("/Cart/Lines*/PromiseDate"))), "../FirstPromise", FIRST_D,
            listOf(Rows(promise = "2024-03-20"), Rows()), "first PromiseDate filled → computes it")
    @Test fun stringFirstFilledComputesTheValue() =
        agreeCompute(render2(Dsl.firstFilledString(m.flatten("/Cart/Lines*/Note"))), "../FirstNote", FIRST_T,
            listOf(Rows(note = "hello"), Rows()), "first Note filled → computes it")

    private fun render2(e: io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr) =
        DslRenderer(io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage.EN, "/Cart").render(e.expression())
    private fun render2(e: io.github.mbackschat.a12.dm.rulekit.dsl.DateExpr) =
        DslRenderer(io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage.EN, "/Cart").render(e.expression())
    private fun render2(e: io.github.mbackschat.a12.dm.rulekit.dsl.StringExpr) =
        DslRenderer(io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage.EN, "/Cart").render(e.expression())
}
