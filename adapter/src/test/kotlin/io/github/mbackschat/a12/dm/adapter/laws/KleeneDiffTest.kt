package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.ast.Tri
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.eval.Kleene
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
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
    private fun render(c: Condition): String = RuntimeLaws.render(c)

    /** The value that forces `[field] > 0` to the wanted truth: 5 → TRUE, 0 → FALSE, `x` (malformed) → UNKNOWN. */
    private fun valueFor(t: Tri): String = when (t) {
        Tri.TRUE -> "5"
        Tri.FALSE -> "0"
        Tri.UNKNOWN -> "x"
    }

    private fun fixture(conn: String): Fixture {
        val q = m.num(A).gt(0)
        val bq = m.num(B).gt(0)
        val cond = render(if (conn == "And") q.and(bq) else q.or(bq))
        val model = RuntimeLaws.withRule(order, "/Order", "R", "../Quantity", "K", cond)
        val def = ModelDef(fields, listOf(RuleDef("/Order/R", "/Order", cond, "K", "../Quantity")))
        return Fixture(conn, model, def, RuntimeLaws.kernelJavaEngine(model))
    }

    /** For one connective and one (left, right) truth pair: both kernel strategies, interpreter, and the law agree. */
    private fun agree(fixture: Fixture, left: Tri, right: Tri, predicted: Boolean) {
        val kDoc = RuntimeLaws.doc().group("/Order", 1)
            .field(A, valueFor(left), 1, 1).field(B, valueFor(right), 1, 1)
        val iDoc = fixture.definition.newDocument()
            .group("/Order", 1).field(A, valueFor(left), 1, 1).field(B, valueFor(right), 1, 1)
        val kernel = RuntimeLaws.fires(fixture.model, "K", kDoc)
        val javaStatic = fixture.java.messages(fixture.java.instance(kDoc)).any { it.code() == "K" }
        val interp = DmInterpreter(fixture.definition).validateFull(iDoc).any { it.code == "K" }
        val label = "$left ${fixture.connective} $right"
        assertEquals(predicted, kernel, "dynamic Groovy disagrees with Kleene on $label")
        assertEquals(kernel, javaStatic, "generated Java disagrees with dynamic Groovy on $label")
        assertEquals(predicted, interp, "INTERPRETER disagrees with Kleene on  $label")
    }

    @Test fun andTruthTableMatchesKernel() {
        val fixture = fixture("And")
        for (l in Tri.entries) for (r in Tri.entries) {
            agree(fixture, l, r, predicted = Kleene.and(l, r) == Tri.TRUE)
        }
    }

    @Test fun orTruthTableMatchesKernel() {
        val fixture = fixture("Or")
        for (l in Tri.entries) for (r in Tri.entries) {
            agree(fixture, l, r, predicted = Kleene.or(l, r) == Tri.TRUE)
        }
    }

    private data class Fixture(
        val connective: String,
        val model: String,
        val definition: ModelDef,
        val java: RuntimeLaws.JavaKernelEngine,
    )

    private companion object {
        const val A = "/Order/Quantity"
        const val B = "/Order/BackorderQuantity"
    }
}
