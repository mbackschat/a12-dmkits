package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The single-computation library primitive [DmInterpreter.computeField] — the compute twin of
 * [DmInterpreter.testRule] (IF129, graduated from IG74). The GOVERNING invariant: `computeField(t, doc)` runs
 * only t's transitive computed-dependency CLOSURE (never every sibling computation), so it must return EXACTLY
 * what the whole-document [DmInterpreter.compute] projects to t — same instances, same order, same indices, same
 * outcome bucket. This is the cheap, kernel-free net that proves closure-pruning is behaviour-preserving; the
 * kernel agreement itself rides on `adapter.laws.ComputeFieldDiffTest` (compute() is kernel-locked, and this pins
 * computeField == compute-projected).
 *
 * Model (own-domain, e-commerce): a per-row `Amount = [Qty] * [Price]` (a REPEATED target), a root
 * `Total = Sum(Lines* / Amount)` (cascade depth 1, over a computed dependency), and `Doubled = [Total] + [Total]`
 * (cascade depth 2). The computations are declared TARGET-before-dependency on purpose, so only the dependency
 * ordering + closure — not declaration order — runs Amount before Total before Doubled.
 */
class ComputeFieldInvariantTest {

    private val QTY = "/Order/Lines/Qty"
    private val PRICE = "/Order/Lines/Price"
    private val AMOUNT = "/Order/Lines/Amount"     // per-row computed — a repeated target
    private val TOTAL = "/Order/Total"             // cascade over the computed Amount
    private val DOUBLED = "/Order/Doubled"         // cascade depth 2 (reads Total)

    private val def = ModelDef(
        fields = listOf(
            FieldDef(QTY, Kind.NUMBER, scale = 0),
            FieldDef(PRICE, Kind.NUMBER, scale = 2),
            FieldDef(AMOUNT, Kind.NUMBER, scale = 2),
            FieldDef(TOTAL, Kind.NUMBER, scale = 2),
            FieldDef(DOUBLED, Kind.NUMBER, scale = 2),
        ),
        rules = emptyList(),
        computations = listOf(
            ComputationDef(DOUBLED, "/Order", "[Total] + [Total]"),
            ComputationDef(TOTAL, "/Order", "Sum(/Order/Lines*/Amount)"),
            ComputationDef(AMOUNT, "/Order/Lines", "[Qty] * [Price]"),
        ),
        repeatableGroups = setOf("/Order/Lines"),
    )

    private val targets = listOf(AMOUNT, TOTAL, DOUBLED)

    /** For EVERY computation target: `computeField(t)` must equal `compute()` projected to t — order-sensitive. */
    private fun assertClosureMatchesWhole(build: (Document) -> Unit) {
        val doc = def.newDocument().group("/Order", 1)
        build(doc)
        val interp = DmInterpreter(def)
        val whole = interp.compute(doc)
        for (t in targets) {
            assertEquals(whole.filter { it.path == t }, interp.computeField(t, doc),
                "computeField($t) must equal compute() projected to $t")
        }
    }

    private fun line(doc: Document, r: Int, qty: String, price: String) {
        doc.group("/Order/Lines", 1, r).field(QTY, qty, 1, r, 1).field(PRICE, price, 1, r, 1)
    }

    @Test fun cleanCascadeAndRepeatedTarget() = assertClosureMatchesWhole { doc ->
        line(doc, 1, "2", "10.00"); line(doc, 2, "3", "20.00")
    }

    @Test fun poisonPropagatesThroughTheClosure() = assertClosureMatchesWhole { doc ->
        // row 1's Price has 3 fractional digits into a scale-2 field → invalid operand → Amount[1,1] poisoned →
        // Total's Sum reads it → poison → Doubled reads Total → poison (IF85 cascade), each level projected identically.
        line(doc, 1, "2", "5.123"); line(doc, 2, "3", "20.00")
    }

    @Test fun emptyOperandsClear() = assertClosureMatchesWhole { doc ->
        doc.group("/Order/Lines", 1, 1)   // no operands seeded — empty-as-0 / cleared through the closure
    }

    @Test fun repeatedTargetReturnsEveryInstance() {
        val doc = def.newDocument().group("/Order", 1)
        line(doc, 1, "2", "10.00"); line(doc, 2, "3", "20.00")
        val amounts = DmInterpreter(def).computeField(AMOUNT, doc)
        assertEquals(2, amounts.size, "a per-row computed field returns one Computed per row")
        assertEquals(listOf(listOf(1, 1, 1), listOf(1, 2, 1)), amounts.map { it.indices },
            "indices are per-segment [Order-rep, Lines-rep, field] — one distinct tuple per row")
    }

    @Test fun deepCascadeTargetResolves() {
        // computeField(Doubled) must pull Total AND Amount into the closure (a depth-2 dependency) and land Doubled's value.
        val doc = def.newDocument().group("/Order", 1)
        line(doc, 1, "2", "10.00"); line(doc, 2, "3", "20.00")
        val doubled = DmInterpreter(def).computeField(DOUBLED, doc).single()
        assertEquals(DOUBLED, doubled.path)
    }

    @Test fun nonComputedFieldThrows() {
        val doc = def.newDocument().group("/Order", 1)
        assertFailsWith<IllegalArgumentException> { DmInterpreter(def).computeField(QTY, doc) }
    }
}
