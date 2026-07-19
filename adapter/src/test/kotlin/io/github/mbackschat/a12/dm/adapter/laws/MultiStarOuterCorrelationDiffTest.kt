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
 * star spans EVERY Line's Subs). This is the shape `Interpreter.starQuantTally` used to route unconditionally to
 * the O(N²) `naiveStarQuantTally` (because `isMultiStar` is true); IF… now sends the recognized equality join
 * through the O(1) hash join with the FIRST-star bind-depth scope — EXCEPT a multi-star `$`-correlation carrying
 * a `CurrentRepetition` self-exclusion (which drops more than one row: every row sharing the outer's leaf index
 * across the wider scope), which the hash join's single-row self-subtraction cannot express, so it stays naive.
 *
 * This test pins interp == kernel across both paths: the no-self-exclusion cases exercise the NEW multi-star hash
 * join (a Cost shared ACROSS Lines is counted across Lines — the multi-star scope, not the current Line's Subs;
 * empty keys and unevaluated rows drop); the self-exclusion case exercises the naive fallback. IG98's legality
 * was settled here (kernel MVK-accepts + interp matches); the scaling win is `MultiStarCorrelationPerfTest`.
 */
class MultiStarOuterCorrelationDiffTest {

    private val perf = RuntimeLaws.model("models/perf.dm.json")
    private val m = BoundModel.ofJson(perf)
    private val cost = m.num(COST)
    private val subs = m.group("/Perf/Lines/Subs")
    private fun render(c: Condition): String = DslRenderer().render(c)

    /** "more than one (Line, Sub) across ALL Lines shares this Sub's Cost" — the multi-star hash-join path. */
    private val corr = render(cost.filled().and(
        Dsl.moreThanOneFieldFilled(m.flatten("/Perf/Lines*/Subs*/Cost").having(cost.eq(cost.outer())))))

    /** WITH a `CurrentRepetition(Subs)` self-exclusion — "another (Line, Sub) with a DIFFERENT Sub index shares
     *  this Cost" — the naive-fallback path (the exclusion drops every same-Sub-index row, not just the own row). */
    private val corrExcl = render(cost.filled().and(
        Dsl.atLeastOneFieldFilled(m.flatten("/Perf/Lines*/Subs*/Cost").having(
            Dsl.currentRepetition(subs).ne(Dsl.currentRepetition(subs, true)).and(cost.eq(cost.outer()))))))

    private val fields = listOf(
        FieldDef(LINE_KEY, Kind.STRING), FieldDef(SUB_KEY, Kind.STRING), FieldDef(COST, Kind.NUMBER, scale = 0))

    /** (Line, Sub, Cost-or-null) rows → kernel fired count. Throws if the kernel REJECTS the shape (MVK/codegen). */
    private fun kFires(cond: String, rows: List<Triple<Int, Int, String?>>): Int {
        val doc = RuntimeLaws.doc().group("/Perf", 1)
        for ((l, s, c) in rows) {
            doc.group("/Perf/Lines", 1, l).field(LINE_KEY, "L$l", 1, l, 1)
            doc.group("/Perf/Lines/Subs", 1, l, s).field(SUB_KEY, "S${l}_$s", 1, l, s, 1)
            if (c != null) doc.field(COST, c, 1, l, s, 1)
        }
        val model = RuntimeLaws.withRule(perf, "/Perf", "MSC", "../Lines/Subs/Cost", "MSC", cond)
        return RuntimeLaws.messages(model, doc).count { it.code() == "MSC" }
    }

    private fun iFires(cond: String, rows: List<Triple<Int, Int, String?>>): Int {
        val def = ModelDef(fields, listOf(RuleDef("/Perf/MSC", "/Perf", cond, "MSC", "../Lines/Subs/Cost")),
            repeatableGroups = setOf("/Perf/Lines", "/Perf/Lines/Subs"),
            declaredReps = mapOf("/Perf/Lines" to 1000, "/Perf/Lines/Subs" to 1000),
            indexFields = setOf(LINE_KEY, SUB_KEY))
        val doc = def.newDocument()
        for ((l, s, c) in rows) {
            doc.field(LINE_KEY, "L$l", 1, l, 1).field(SUB_KEY, "S${l}_$s", 1, l, s, 1)
            if (c != null) doc.field(COST, c, 1, l, s, 1)
        }
        return DmInterpreter(def).validateFull(doc).count { it.code == "MSC" }
    }

    private fun agree(cond: String, vararg rows: Triple<Int, Int, String?>) =
        assertEquals(kFires(cond, rows.toList()), iFires(cond, rows.toList()), "rows=${rows.toList()}")

    /** The NEW multi-star hash-join path — no self-exclusion. */
    @Test fun crossLineDuplicateCountsAcrossTheWholeMultiStarScope() {
        // Cost 5 at L1/S1 and L2/S1 (cross-Line) → each fires; L1/S2, L2/S2 unique → 2 total.
        agree(corr, t(1, 1, "5"), t(1, 2, "7"), t(2, 1, "5"), t(2, 2, "9"))
        agree(corr, t(1, 1, "5"), t(1, 2, "7"), t(2, 1, "8"), t(2, 2, "9"))   // all distinct → none
        agree(corr, t(1, 1, "5"), t(2, 1, "5"), t(2, 2, "5"))                  // triple duplicate across Lines
    }

    @Test fun emptyKeysAndUnevaluatedRowsDrop() {
        agree(corr, t(1, 1, "5"), t(1, 2, null), t(2, 1, "5"), t(2, 2, null))  // empty Cost rows drop from the buckets
        agree(corr, t(1, 1, null), t(2, 1, "5"), t(2, 2, "5"))                 // the empty outer row is FieldFilled-gated out
        agree(corr, t(1, 1, "0"), t(2, 1, "0"))                                // zero keys — kernel decides; must agree
    }

    /** The naive-fallback path — a multi-star `$`-correlation WITH a leaf-level self-exclusion. */
    @Test fun selfExcludedMultiStarStaysNaiveAndMatchesKernel() {
        agree(corrExcl, t(1, 1, "5"), t(1, 2, "7"), t(2, 1, "5"), t(2, 2, "9"))
        agree(corrExcl, t(1, 1, "5"), t(1, 2, "5"), t(2, 1, "5"), t(2, 2, "9"))
        agree(corrExcl, t(1, 1, "5"), t(2, 1, "5"))
    }

    private fun t(l: Int, s: Int, c: String?) = Triple(l, s, c)

    private companion object {
        const val LINE_KEY = "/Perf/Lines/LineKey"
        const val SUB_KEY = "/Perf/Lines/Subs/SubKey"
        const val COST = "/Perf/Lines/Subs/Cost"
    }
}
