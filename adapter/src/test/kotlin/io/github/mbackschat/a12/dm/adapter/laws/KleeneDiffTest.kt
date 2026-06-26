package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.ast.Tri
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.Kleene
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — the **three-valued (Kleene) boolean logic** ([Kleene]) is bit-for-bit the kernel's
 * `ValidierungsErgebnis.combineUND`/`combineODER`. The kernel collapses FALSE+UNKNOWN into one state
 * (`FALSE_OR_UNKNOWN`) and folds polarity into the TRUE states; the interpreter splits truth ([Tri]) from
 * polarity. This test proves the split is observably equivalent for the firing decision by sweeping **every
 * cell** of the AND and OR truth tables: each branch `[field] > 0` is forced to TRUE (value `5`), FALSE
 * (value `0`), or UNKNOWN (a malformed value `x` → formal error → un-evaluable), and we assert
 * **kernel-fires == interpreter-fires == the pure [Kleene] prediction** for all 3×3 combinations of each
 * connective. (The FALSE/UNKNOWN distinction the interpreter keeps is unobservable here: the kernel has no
 * `negate` on `ValidierungsErgebnis`, and the DSL has no general boolean `Not`, so the one operation where
 * FALSE≠UNKNOWN is never applied to a combined result — KERNEL-FINDINGS / INTERPRETER-FINDINGS IF17.)
 */
class KleeneDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val fields = listOf(FieldDef(A, Kind.NUMBER, scale = 0), FieldDef(B, Kind.NUMBER, scale = 0))

    // Conditions are authored with the rulekit typed DSL ([m]) and rendered to the canonical string fed to BOTH
    // engines — no free-text condition literals. `conn` selects And vs Or over the two `[field] > 0` branches.
    private val m = BoundModel.ofJson(order)
    private fun render(c: Condition): String = DslRenderer().render(c)

    /** The value that forces `[field] > 0` to the wanted truth: 5 → TRUE, 0 → FALSE, `x` (malformed) → UNKNOWN. */
    private fun valueFor(t: Tri): String = when (t) {
        Tri.TRUE -> "5"
        Tri.FALSE -> "0"
        Tri.UNKNOWN -> "x"
    }

    /** For one connective and one (left, right) truth pair: kernel-fires == interp-fires == Kleene prediction. */
    private fun agree(conn: String, left: Tri, right: Tri, predicted: Boolean) {
        val q = m.num(A).gt(0)
        val bq = m.num(B).gt(0)
        val cond = render(if (conn == "And") q.and(bq) else q.or(bq))
        val kModel = RuntimeLaws.withRule(order, "/Order", "R", "../Quantity", "K", cond)
        val def = ModelDef(fields, listOf(RuleDef("/Order/R", "/Order", cond, "K", "../Quantity")))
        val kDoc = RuntimeLaws.doc().group("/Order", 1)
            .field(A, valueFor(left), 1, 1).field(B, valueFor(right), 1, 1)
        val iDoc = Document(def.kinds, def.declaredReps, def.unsignedFields, def.rangeConfig, def.dateFormats, def.scales)
            .group("/Order", 1).field(A, valueFor(left), 1, 1).field(B, valueFor(right), 1, 1)
        val kernel = RuntimeLaws.fires(kModel, "K", kDoc)
        val interp = DmInterpreter(def).validateFull(iDoc).any { it.code == "K" }
        val label = "$left $conn $right"
        assertEquals(predicted, kernel, "KERNEL disagrees with Kleene on  $label")
        assertEquals(predicted, interp, "INTERPRETER disagrees with Kleene on  $label")
    }

    @Test fun andTruthTableMatchesKernel() {
        for (l in Tri.entries) for (r in Tri.entries) {
            agree("And", l, r, predicted = Kleene.and(l, r) == Tri.TRUE)
        }
    }

    @Test fun orTruthTableMatchesKernel() {
        for (l in Tri.entries) for (r in Tri.entries) {
            agree("Or", l, r, predicted = Kleene.or(l, r) == Tri.TRUE)
        }
    }

    private companion object {
        const val A = "/Order/Quantity"
        const val B = "/Order/BackorderQuantity"
    }
}
