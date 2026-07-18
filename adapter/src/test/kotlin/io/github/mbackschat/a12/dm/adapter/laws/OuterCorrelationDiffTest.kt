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
 * Differential — the `$` OUTER-ITERATION CORRELATION (IF47) through [DmInterpreter], the interpreter twin of
 * the kernel-only `CorrelationLawsTest`. A `Having` filter over a starred field correlates each inner row to the
 * OUTER row (`[$Count]`, `CurrentRepetition($Items)`); the quantifier then fires on the rows a cross-row
 * predicate selects. The interpreter must fire on EXACTLY the rows the kernel does — the reported bug was
 * `model eval` crashing at parse on `$`. Covers BOTH evaluation paths: the O(1) hash-join fast path (an equality
 * self-correlation `[X] == [$X]`, with/without the `CurrentRepetition` self-exclusion, incl. empty→0 join keys)
 * AND the O(N²) fallback (a non-equality `<` correlation the join can't index). Each also exercises the filtered
 * quantifier over a star (IG25's starred-field form), which the reported condition needs alongside `$`.
 */
class OuterCorrelationDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val m = BoundModel.ofJson(order)
    private fun render(c: Condition): String = DslRenderer().render(c)

    private val items = m.group("/Order/Items")
    private fun selfExcl() = Dsl.currentRepetition(items).ne(Dsl.currentRepetition(items, true))

    /** self-excluded equality — "another row shares this row's Count" (HASH-JOIN fast path). */
    private val corr = render(m.field(COUNT).filled().and(
        Dsl.atLeastOneFieldFilled(m.flatten("/Order/Items*/Count").having(
            selfExcl().and(m.num(COUNT).eq(m.num(COUNT).outer()))))))

    /** equality WITHOUT the self-exclusion — every filled row matches itself (HASH-JOIN, no self-subtraction). */
    private val corrNoExcl = render(m.field(COUNT).filled().and(
        Dsl.atLeastOneFieldFilled(m.flatten("/Order/Items*/Count").having(
            m.num(COUNT).eq(m.num(COUNT).outer())))))

    /** self-excluded `<` — "another row has a SMALLER Count" (non-equality → O(N²) FALLBACK, still `$`). */
    private val corrLess = render(m.field(COUNT).filled().and(
        Dsl.atLeastOneFieldFilled(m.flatten("/Order/Items*/Count").having(
            selfExcl().and(m.num(COUNT).lt(m.num(COUNT).outer()))))))

    private val fields = listOf(
        FieldDef(CN, Kind.STRING), FieldDef(SKU, Kind.STRING), FieldDef(COUNT, Kind.NUMBER, scale = 0))

    private fun kFires(cond: String, counts: List<String?>): Int {
        val doc = RuntimeLaws.doc().group("/Order", 1).field(CN, "Acme", 1, 1)
        counts.forEachIndexed { i, c ->
            val r = i + 1
            doc.group("/Order/Items", 1, r).field(SKU, ('A' + i).toString(), 1, r, 1)
            if (c != null) doc.field(COUNT, c, 1, r, 1)
        }
        val model = RuntimeLaws.withRule(order, "/Order", "Corr", "../Items/Count", "CORR", cond)
        return RuntimeLaws.messages(model, doc).count { it.code() == "CORR" }
    }

    private fun iFires(cond: String, counts: List<String?>): Int {
        val def = ModelDef(fields, listOf(RuleDef("/Order/Corr", "/Order", cond, "CORR", "../Items/Count")),
            repeatableGroups = setOf("/Order/Items"), declaredReps = mapOf("/Order/Items" to 1000), indexFields = setOf(SKU))
        val doc = def.newDocument()
        counts.forEachIndexed { i, c ->
            val r = i + 1
            doc.field(SKU, ('A' + i).toString(), 1, r, 1)
            if (c != null) doc.field(COUNT, c, 1, r, 1)
        }
        return DmInterpreter(def).validateFull(doc).count { it.code == "CORR" }
    }

    private fun agree(cond: String, vararg counts: String?) =
        assertEquals(kFires(cond, counts.toList()), iFires(cond, counts.toList()), "counts=${counts.toList()}")

    @Test fun equalitySelfExcluded() {
        agree(corr, "5", "5", "9")   // rows 1,2 share 5 → fire on both
        agree(corr, "5", "6", "9")   // all distinct → none
        agree(corr, "5", "5", "5")   // every row has a duplicate
        agree(corr, "5")             // a single row → never fires
    }

    @Test fun equalityNoSelfExclusion() {
        agree(corrNoExcl, "5", "6", "9")   // each self-matches → all fire
        agree(corrNoExcl, "5", "5", "9")
    }

    @Test fun emptyAndZeroJoinKeys() {
        agree(corr, "0", null, "0")        // empty Count buckets with 0 — kernel decides; must agree
        agree(corr, null, "5", "5")        // an empty outer row is FieldFilled-gated out
        agree(corrNoExcl, "0", null)
        agree(corr, "0", "0", null)
    }

    @Test fun nonEqualityCorrelationUsesFallback() {
        agree(corrLess, "5", "6", "9")     // 6 has a smaller (5); 9 has smaller → fire on 6,9 not 5
        agree(corrLess, "9", "9", "9")     // none smaller → none
        agree(corrLess, "5", "5", "5")
        agree(corrLess, "9", "5", "7")
    }

    private companion object {
        const val CN = "/Order/CustomerName"
        const val SKU = "/Order/Items/Sku"
        const val COUNT = "/Order/Items/Count"
    }
}
