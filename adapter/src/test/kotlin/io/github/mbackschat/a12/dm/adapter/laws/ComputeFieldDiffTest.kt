package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.formatPointer
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential for the single-computation library primitive [DmInterpreter.computeField] (IF129, graduated from
 * IG74) — the compute twin of [TestRuleDiffTest]. The kernel has NO single-computation entry
 * (`IDocumentRtService.compute` is whole-document), so the oracle is the kernel's whole-document `compute()`
 * **projected to the target field**: for every computation target, `computeField(target)` must equal the kernel's
 * compute outcome for that field's instances.
 *
 * Two locks per scenario: (1) `computeField(t) == compute().filter { path == t }` — the interpreter invariant that
 * closure-pruning is behaviour-preserving; (2) `computeField(t)` == the kernel's compute projected to t. computeField
 * adds NO new semantics — it is a pruned subset of the already-both-strategies-locked `compute()` (the codegen tri-check
 * lives in `MultiComputationFieldDiffTest`/`HavingOverComputedDependencyDiffTest`), so this test pins the projection
 * and the kernel-runtime agreement, not compute()'s codegen parity again.
 *
 * Model (own-domain, e-commerce): per-row `Amount = [Qty] * [Price]` (a REPEATED target), root
 * `Total = Sum(Lines* / Amount)` (cascade over the computed Amount), `Doubled = [Total] + [Total]` (cascade depth 2).
 */
class ComputeFieldDiffTest {

    private companion object {
        const val QTY = "/Order/Lines/Qty"
        const val PRICE = "/Order/Lines/Price"
        const val AMOUNT = "/Order/Lines/Amount"
        const val TOTAL = "/Order/Total"
        const val DOUBLED = "/Order/Doubled"
        const val LINES = "/Order/Lines"
    }

    private val targets = listOf(AMOUNT, TOTAL, DOUBLED)

    private val base = DocumentModelDraft.named("order").locale("en_US")
        .rootGroup("Order") { g ->
            g.numberField("Total") { it.fractionDigits(0, 2) }
            g.numberField("Doubled") { it.fractionDigits(0, 2) }
            g.group("Lines") { l ->
                l.repeatable(10)
                    .numberField("Qty") { it.fractionDigits(0, 0) }
                    .numberField("Price") { it.fractionDigits(0, 2) }
                    .numberField("Amount") { it.fractionDigits(0, 2) }
            }
        }.build().toJson()

    private val model: String = run {
        var m = RuntimeLaws.withComputation(base, LINES, "AmountComp", "../Amount", "[Qty] * [Price]")
        m = RuntimeLaws.withComputation(m, "/Order", "TotalComp", "../Total", "Sum(/Order/Lines*/Amount)")
        RuntimeLaws.withComputation(m, "/Order", "DoubledComp", "../Doubled", "[Total] + [Total]")
    }

    private class Both(val kDoc: RuntimeLaws.Doc, val iDoc: Document) {
        fun group(path: String, vararg reps: Int): Both { kDoc.group(path, *reps); iDoc.group(path, *reps); return this }
        fun field(path: String, v: String, vararg reps: Int): Both { kDoc.field(path, v, *reps); iDoc.field(path, v, *reps); return this }
    }

    private fun Both.line(r: Int, qty: String, price: String): Both {
        group(LINES, 1, r).field(QTY, qty, 1, r, 1).field(PRICE, price, 1, r, 1)
        return this
    }

    private fun kSig(c: RuntimeLaws.Computed): String =
        if (c.cleared()) "CLEARED" else if (c.errored()) "ERRORED" else "VALUE|${c.value().orElse("")}"

    private fun iSig(c: io.github.mbackschat.a12.dm.interpreter.model.Computed): String = when (c.outcome) {
        ComputeOutcome.CLEARED -> "CLEARED"
        ComputeOutcome.ERRORED -> "ERRORED"
        ComputeOutcome.VALUE -> "VALUE|${c.value}"
    }

    /** For each target: computeField == compute().filter (interp invariant) AND == the kernel compute projected. */
    private fun agree(msg: String, build: (Both) -> Unit) {
        val def = ModelLoader.load(model)
        val b = Both(RuntimeLaws.doc(), def.newDocument())
        build(b)
        val kernelAll = RuntimeLaws.compute(model, b.kDoc)
        val interp = DmInterpreter(def)
        val whole = interp.compute(b.iDoc)
        for (t in targets) {
            val field = interp.computeField(t, b.iDoc)
            assertEquals(whole.filter { it.path == t }, field, "$msg — computeField($t) == compute().filter (interp invariant)")

            val kProj = kernelAll.filter { it.path() == t }.associate { it.pointer() to kSig(it) }
            val fieldProj = field.associate { formatPointer(it.path, it.indices) to iSig(it) }
            assertEquals(kProj, fieldProj.filterKeys { it in kProj.keys },
                "$msg — computeField($t) == kernel compute projected to $t (kernel-reported cells)")
        }
    }

    @Test fun cleanCascadeMatchesKernelPerField() = agree("two clean lines → Amount per row, Total sum, Doubled twice") { b ->
        b.group("/Order", 1); b.line(1, "2", "10.00"); b.line(2, "3", "20.00")
    }

    @Test fun poisonCascadeMatchesKernelPerField() = agree("row-1 Price 3-frac → Amount poisoned → Total/Doubled poison") { b ->
        b.group("/Order", 1); b.line(1, "2", "5.123"); b.line(2, "3", "20.00")
    }

    @Test fun emptyOperandsMatchKernelPerField() = agree("no operands → cascade clears / sums 0") { b ->
        b.group("/Order", 1).group(LINES, 1, 1)
    }
}
