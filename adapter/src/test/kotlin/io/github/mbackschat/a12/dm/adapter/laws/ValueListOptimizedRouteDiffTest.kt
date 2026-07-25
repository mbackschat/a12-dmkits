package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The field-list value-list quantifiers are emitted by **two runtime routes**, and until this class nothing in
 * either project exercised the optimized one. `EntityListAndValueConditionFieldList.shouldBeOptimized()` selects
 * the `OL` suffix over `FL` only when ALL hold: the condition is `No`/`AtLeastOne`/`NotAll` (`AtLeastOne`-date-range
 * is excluded outright); the **fields** side has a reference whose ITERATION levels' max-repetition product is
 * `>= OPTIMIZATION_BOUND_FOR_ITERATION` (**10**); the **values** side has no filter and no iteration; and the values
 * side has a reference whose WILDCARD levels' product is `>= OPTIMIZATION_BOUND_FOR_VALUE_LIST_LENGTH` (**20**).
 *
 * The two routes differ in shape, not outcome. `…FL` opens with a fields-presence prepass (`noEntityFilled`) and
 * then builds the values set; `…OL` has no prepass because its values set, `foundNonFilledField`, and
 * `foundNonRelevantFieldInValueList` are hoisted ahead of the whole condition by
 * `InitEntityListAndValueListConditionParam.st`. They agree because the membership test counts only given cells,
 * so with no present fields cell the un-prepassed route collapses to the same non-firing result — which is why
 * the prepass is an optimization detail and must never be restated as the observable contract.
 *
 * Note the fields side must be **non-starred** to have ITERATION levels at all (a star is a WILDCARD, so it
 * contributes to `getLengthAsList` and not to `getNumberOfIterations`) — which makes the rule an ordinary
 * iterating rule, so the negative quantifier additionally needs a positive per-row guard or it is rejected
 * `MVK_NEG_CONDITION_IN_ITERATION`. That interaction is why the shape is awkward to reach by accident.
 *
 * Kernel truth: KERNEL-FINDINGS §5 supplement "the value-list quantifiers have TWO emission routes"
 * (a12-kernel-lean SPEC-2026-07-25-01).
 */
class ValueListOptimizedRouteDiffTest {

    private companion object {
        const val ROOT = "/M"
        const val CODE = "/M/Items/Code"
        const val ALLOWED = "/M/Cat/Allowed"
        const val Q = "Q"
    }

    /** Below the thresholds → the ordinary `FL` route; at/above them → the optimized `OL` route. */
    private fun model(itemReps: Int, catReps: Int) = DocumentModelDraft.named("value-list-route").locale("en_US")
        .rootGroup("M") { m ->
            m.group("Items") { i -> i.repeatable(itemReps).stringField("Code") { } }
            m.group("Cat") { c -> c.repeatable(catReps).stringField("Allowed") { } }
        }.build().toJson()

    private fun ruleModel(itemReps: Int, catReps: Int, quantifier: String): String = RuntimeLaws.withRule(
        model(itemReps, catReps), ROOT, "R", "../Items/Code", Q,
        "FieldFilled(Items/Code) And $quantifier(Items/Code In Cat*/Allowed)",
    )

    // Codegen + in-memory javac is the expensive part, so build each model and its static-Java engine ONCE for
    // the whole class rather than per case (the estate shares a test task with timing-sensitive growth floors).
    private val flModel by lazy { ruleModel(5, 5, "NoFieldValueIncludedInValueList") }
    private val olModel by lazy { ruleModel(10, 20, "NoFieldValueIncludedInValueList") }
    private val flEngine by lazy { RuntimeLaws.kernelJavaEngine(flModel) }
    private val olEngine by lazy { RuntimeLaws.kernelJavaEngine(olModel) }
    private val flDef by lazy { ModelLoader.load(flModel) }
    private val olDef by lazy { ModelLoader.load(olModel) }

    @Test
    fun `the route flips at the thresholds, for both quantifier names`() {
        for (q in listOf("NoFieldValueIncludedInValueList", "NotAllFieldValuesIncludedInValueList")) {
            val below = RuntimeLaws.generatedGroovySource(ruleModel(5, 5, q))
            val above = RuntimeLaws.generatedGroovySource(ruleModel(10, 20, q))
            assertTrue(Regex("""WerteListeFL\(""").containsMatchIn(below)) {
                "$q below the thresholds must take the ordinary FL route"
            }
            assertTrue(Regex("""WerteListeOL\(""").containsMatchIn(above)) {
                "$q at the thresholds must take the optimized OL route — the whole point of this class"
            }
            assertTrue(!Regex("""WerteListeFL\(""").containsMatchIn(above)) {
                "$q above the thresholds must NOT also emit the FL route"
            }
        }
    }

    @Test
    fun `both routes agree when no fields cell is present — the prepass case`() =
        agree(rows = listOf(null), cat = listOf("A", "B"), expected = false,
            label = "an instantiated row with an EMPTY code: FL short-circuits on its prepass, OL has none")

    @Test
    fun `both routes agree when no fields cell exists at all`() =
        agree(rows = emptyList(), cat = listOf("A", "B"), expected = false,
            label = "no Items row at all — an iterating rule over zero rows evaluates zero times")

    @Test
    fun `both routes agree on a firing row`() =
        agree(rows = listOf("X"), cat = listOf("A", "B"), expected = true,
            label = "code X is in no allowed cell, so No… fires")

    @Test
    fun `both routes agree on a matching row`() =
        agree(rows = listOf("A"), cat = listOf("A", "B"), expected = false,
            label = "code A matches an allowed cell, so No… does not fire")

    @Test
    fun `both routes agree when the values side is wholly empty`() =
        agree(rows = listOf("X"), cat = listOf(null, null), expected = true,
            label = "every allowed cell empty — the membership test counts only given cells")

    /** Runs the same document through the FL model and the OL model, on all three engines. */
    private fun agree(rows: List<String?>, cat: List<String?>, expected: Boolean, label: String) {
        val fl = fired(flModel, flEngine, flDef, rows, cat)
        val ol = fired(olModel, olEngine, olDef, rows, cat)
        assertEquals(fl.first, ol.first, "$label — ordinary FL vs optimized OL (dynamic-Groovy)")
        assertEquals(fl.second, ol.second, "$label — FL vs OL (static-Java)")
        assertEquals(fl.third, ol.third, "$label — FL vs OL (interpreter)")
        assertEquals(expected, ol.first, "$label — the OL route's dynamic-Groovy anchor")
        assertEquals(ol.first, ol.second, "$label — OL: dynamic-Groovy vs static-Java")
        assertEquals(ol.first, ol.third, "$label — OL: kernel vs interpreter")
    }

    /** `(groovy, javaStatic, interpreter)` — did code [Q] fire? */
    private fun fired(
        kModel: String,
        engine: RuntimeLaws.JavaKernelEngine,
        def: io.github.mbackschat.a12.dm.interpreter.model.ModelDef,
        rows: List<String?>,
        cat: List<String?>,
    ): Triple<Boolean, Boolean, Boolean> {
        fun seedKernel(): RuntimeLaws.Doc {
            val d = RuntimeLaws.doc().group(ROOT, 1)
            rows.forEachIndexed { i, code ->
                d.group("/M/Items", 1, i + 1)
                code?.let { d.field(CODE, it, 1, i + 1, 1) }
            }
            cat.forEachIndexed { i, allowed ->
                d.group("/M/Cat", 1, i + 1)
                allowed?.let { d.field(ALLOWED, it, 1, i + 1, 1) }
            }
            return d
        }
        val groovy = RuntimeLaws.messages(kModel, seedKernel()).any { it.code() == Q }
        val javaStatic = engine.firedCodesSorted(engine.instance(seedKernel())).contains(Q)

        val iDoc = def.newDocument()
        iDoc.group(ROOT, 1)
        rows.forEachIndexed { i, code ->
            iDoc.group("/M/Items", 1, i + 1)
            code?.let { iDoc.field(CODE, it, 1, i + 1, 1) }
        }
        cat.forEachIndexed { i, allowed ->
            iDoc.group("/M/Cat", 1, i + 1)
            allowed?.let { iDoc.field(ALLOWED, it, 1, i + 1, 1) }
        }
        val interp = DmInterpreter(def).validateFull(iDoc).any { it.code == Q }
        return Triple(groovy, javaStatic, interp)
    }
}
