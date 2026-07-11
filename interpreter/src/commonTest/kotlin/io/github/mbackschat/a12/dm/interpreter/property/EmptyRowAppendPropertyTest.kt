package io.github.mbackschat.a12.dm.interpreter.property

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.ast.Val
import io.github.mbackschat.a12.dm.interpreter.dec
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.EvalConfig
import io.github.mbackschat.a12.dm.interpreter.eval.Interpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.interpreter.parse.parseExpression
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * **Property 4 — Empty-row append laws** (proposal §8), stated against the settled IF53/IF54 semantics:
 *
 * - **IF53** — an INSTANTIATED-but-empty repeatable row is *content* in full validation: an empty-firing per-row
 *   rule (`[Qty] < 5`, empty Qty = 0 < 5) fires exactly once per instantiated row, so appending one empty row
 *   adds exactly one firing at the new row's pointer;
 * - **IF46** (the instantiated-range side of the "quantifier deltas") — `NumberOfFilledGroups(Lines*)` counts
 *   instantiated repetitions, so it increases by exactly 1 per appended empty row;
 * - **IF54** — in PARTIAL validation a relevant instance is evaluated regardless of content: an aggregate rule
 *   (`MinValue` over `Lines*` then `Qty`, `< 5` — empty-identity 0 < 5) FIRES on a **phantom** row of an empty
 *   document when its ancestor is relevant, though full validation over zero rows does not.
 *
 * (The *declared-range* clamp side — `AllFieldsFilled` over the full declared range, IF49 — is a separate finding
 * locked by its own `:adapter` differential, not restated here.) Kernel-free; runs on JVM + JS.
 */
class EmptyRowAppendPropertyTest {

    private val model = ModelDef(
        fields = listOf(
            FieldDef("/Cart/Lines/Id", Kind.STRING),
            FieldDef("/Cart/Lines/Qty", Kind.NUMBER, scale = 0),
        ),
        rules = listOf(
            RuleDef("/Cart/Lines/R_LOW", "/Cart/Lines", "[Qty] < 5", "LOW", "../Qty"),
            RuleDef("/Cart/R_AGG", "/Cart", "MinValue(Lines*/Qty) < 5", "AGG", "../Lines/Qty"),
        ),
        repeatableGroups = setOf("/Cart/Lines"),
        declaredReps = mapOf("/Cart/Lines" to 1000),
        indexFields = setOf("/Cart/Lines/Id"),
    )
    private val engine = DmInterpreter(model)

    /** A `/Cart` document with [k] instantiated-but-empty `Lines` rows. */
    private fun docWith(k: Int): Document = model.newDocument().also { doc ->
        doc.group("/Cart", 1)
        for (r in 1..k) doc.group("/Cart/Lines", 1, r)
    }

    @Test
    fun if53_instantiatedEmptyRowIsContent_perRowFiresOncePerRow() {
        for (k in 0..6) {
            val fired = engine.validateFull(docWith(k)).filter { it.code == "LOW" }.map { it.errorPath }
            assertEquals((1..k).map { "/Cart[1]/Lines[$it]/Qty" }, fired,
                "LOW must fire once per instantiated empty row (k=$k)")
        }
        // the append delta: k → k+1 adds exactly one firing, at the new row.
        for (k in 0..5) {
            val before = engine.validateFull(docWith(k)).count { it.code == "LOW" }
            val after = engine.validateFull(docWith(k + 1)).count { it.code == "LOW" }
            assertEquals(before + 1, after, "appending one empty row must add exactly one LOW firing (k=$k)")
        }
    }

    @Test
    fun if46_numberOfFilledGroupsCountsInstantiatedRows() {
        val expr = parseExpression("NumberOfFilledGroups(Lines*)", "/Cart")
        for (k in 0..6) {
            val ctx = docWith(k).contextsFor("/Cart").single()
            assertEquals(Val.Num(dec(k)), Interpreter(ctx, EvalConfig(indexFields = model.indexFields)).evalExpression(expr),
                "NumberOfFilledGroups(Lines*) must equal the instantiated row count (k=$k)")
        }
    }

    @Test
    fun if54_partialRelevantPhantomRowFiresThoughFullDoesNot() {
        val empty = docWith(0)   // /Cart instantiated, zero Lines rows
        assertEquals(0, engine.validateFull(empty).count { it.code == "AGG" },
            "full validation over zero rows must not fire the aggregate")
        val partial = engine.validatePart(empty, setOf(RelevantEntity("/Cart", listOf(1))))
            .messages.filter { it.code == "AGG" }
        assertEquals(listOf("/Cart[1]/Lines[1]/Qty"), partial.map { it.errorPath },
            "partial validation must fire the aggregate on the relevant phantom row (IF54)")
    }
}
