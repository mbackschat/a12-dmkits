package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential lock (IG98) — a **MULTI-star** `$`-equality-correlated FILL QUANTIFIER
 * (`MoreThanOneFieldFilled(/Perf/Lines* then Subs* then Cost Having [Cost] == [$Cost])`, a per-Sub rule whose
 * star spans EVERY Line's Subs). This is the shape `Interpreter.starQuantTally` routes to the O(N²)
 * `naiveStarQuantTally` because `isMultiStar` is true (the single-star sibling gets the O(1) hash join). IG98's
 * legality probe used THIS test: the kernel builds `messages(...)` (so it MVK-accepts the shape — LEGAL and
 * structurally reachable), and the interpreter's naive fallback MATCHES the kernel on a **cross-Line** correlation
 * (a Cost shared across Lines is counted across Lines — the multi-star scope, not the current Line's Subs). That
 * settled legality; a scaling measurement then found the interpreter's naive scan is WORSE than the kernel's
 * complexity class (interp ×6.41 vs kernel ×2.69 over 4× Lines, ratio 0.51→1.21), so IG98's remaining build is
 * a multi-star hash join. This test is the standing CORRECTNESS lock the optimization must keep green.
 */
class MultiStarOuterCorrelationDiffTest {

    private val perf = RuntimeLaws.model("models/perf.dm.json")
    private val m = BoundModel.ofJson(perf)
    private fun render(c: Condition): String = DslRenderer().render(c)

    private val cost = m.num(COST)

    /** A per-Sub rule: "more than one (Line, Sub) across ALL Lines shares this Sub's Cost" — a MULTI-star
     *  (`Lines* / Subs*`) equality `$`-self-correlation, no self-exclusion (each row self-matches, so >1 needs a
     *  genuine duplicate). Fires on a Cost that repeats across the whole document. */
    private val cond = render(cost.filled().and(
        Dsl.moreThanOneFieldFilled(m.flatten("/Perf/Lines*/Subs*/Cost").having(cost.eq(cost.outer())))))

    private val fields = listOf(
        FieldDef(LINE_KEY, Kind.STRING), FieldDef(SUB_KEY, Kind.STRING), FieldDef(COST, Kind.NUMBER, scale = 0))

    /** (Line, Sub, Cost) rows across two Lines. */
    private fun rows() = listOf(
        Triple(1, 1, "5"), Triple(1, 2, "7"),   // Line 1
        Triple(2, 1, "5"), Triple(2, 2, "9"),   // Line 2 — L2/S1 shares Cost 5 with L1/S1 (cross-Line!)
    )

    private fun kFires(): Int {
        val doc = RuntimeLaws.doc().group("/Perf", 1)
        for ((l, s, c) in rows()) {
            doc.group("/Perf/Lines", 1, l).field(LINE_KEY, "L$l", 1, l, 1)
            doc.group("/Perf/Lines/Subs", 1, l, s).field(SUB_KEY, "S${l}_$s", 1, l, s, 1).field(COST, c, 1, l, s, 1)
        }
        val model = RuntimeLaws.withRule(perf, "/Perf", "MSC", "../Lines/Subs/Cost", "MSC", cond)
        return RuntimeLaws.messages(model, doc).count { it.code() == "MSC" }   // throws if the kernel REJECTS the shape (MVK/codegen)
    }

    private fun iFires(): Int {
        val def = ModelDef(fields, listOf(RuleDef("/Perf/MSC", "/Perf", cond, "MSC", "../Lines/Subs/Cost")),
            repeatableGroups = setOf("/Perf/Lines", "/Perf/Lines/Subs"),
            declaredReps = mapOf("/Perf/Lines" to 1000, "/Perf/Lines/Subs" to 1000),
            indexFields = setOf(LINE_KEY, SUB_KEY))
        val doc = def.newDocument()
        for ((l, s, c) in rows()) {
            doc.field(LINE_KEY, "L$l", 1, l, 1)
            doc.field(SUB_KEY, "S${l}_$s", 1, l, s, 1).field(COST, c, 1, l, s, 1)
        }
        return DmInterpreter(def).validateFull(doc).count { it.code == "MSC" }
    }

    @Test fun multiStarEqualityCorrelationFillQuantifierMatchesKernel() {
        println("[IG98] rendered condition: $cond")
        val k = kFires()
        println("[IG98] kernel MVK-ACCEPTED the multi-star \$-correlated fill quantifier; kernel fires=$k")
        assertEquals(k, iFires(), "interp (naive multi-star fallback) must match the kernel on the cross-Line correlation")
        // Cross-Line duplicate Cost 5 → L1/S1 and L2/S1 each fire (count 2); L1/S2, L2/S2 unique → 2 total.
        assertEquals(2, k, "the kernel must count the Cost-5 duplicate ACROSS Lines (multi-star scope)")
    }

    private companion object {
        const val LINE_KEY = "/Perf/Lines/LineKey"
        const val SUB_KEY = "/Perf/Lines/Subs/SubKey"
        const val COST = "/Perf/Lines/Subs/Cost"
    }
}
