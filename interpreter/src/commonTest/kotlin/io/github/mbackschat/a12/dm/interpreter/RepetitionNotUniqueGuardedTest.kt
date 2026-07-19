package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.interpreter.model.Verdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Multiplatform (JVM + JS) lock for the combined `<guard> And RepetitionNotUnique(key)` form (IF62). The kernel
 * evaluates RepetitionNotUnique as a per-row 3VL predicate whose duplicate cache is built over the full scope
 * **independent of the guard**; the guard is a separate conjunct combined by the ordinary 3VL And. The
 * kernel-differential lives in `adapter.laws.RepetitionNotUniqueGuardedDiffTest` (JVM only — it needs the
 * kernel); this test runs the SAME evaluator on both targets, and adds the **degrade-loud** guard the
 * differential can't express (RNU in a position the recognizer keeps strict → reported unsupported, not wrong).
 */
class RepetitionNotUniqueGuardedTest {

    private val cn = "/Order/CustomerName"
    private val sku = "/Order/Items/Sku"
    private val count = "/Order/Items/Count"
    private val weight = "/Order/Items/UnitWeight"

    private fun model(cond: String) = ModelDef(
        fields = listOf(FieldDef(cn, Kind.STRING), FieldDef(sku, Kind.STRING),
            FieldDef(count, Kind.NUMBER), FieldDef(weight, Kind.NUMBER)),
        rules = listOf(RuleDef("/Order/RU", "/Order", cond, "RU", "../Items/Count")),
        repeatableGroups = setOf("/Order/Items"),
        declaredReps = mapOf("/Order/Items" to 50),
        indexFields = setOf(sku),
    )

    /** Two Items rows, both Count = 5 (a duplicate pair); [weights] sets each row's UnitWeight (null = empty). */
    private fun twoDuplicateRows(m: ModelDef, weights: List<String?>, customer: String?) =
        m.newDocument().group("/Order", 1).also { doc ->
            if (customer != null) doc.field(cn, customer, 1, 1)
            weights.forEachIndexed { i, w ->
                val r = i + 1
                doc.group("/Order/Items", 1, r).field(sku, "K$r", 1, r, 1).field(count, "5", 1, r, 1)
                if (w != null) doc.field(weight, w, 1, r, 1)
            }
        }

    private fun fired(m: ModelDef, doc: io.github.mbackschat.a12.dm.interpreter.eval.Document) =
        DmInterpreter(m).validateFull(doc).filter { it.code == "RU" }
            .map { "${it.errorPath}|${it.type}" }.sorted()

    @Test
    fun guardGatesFiringButNotTheCache() {
        // FieldFilled(Items/UnitWeight) And RepetitionNotUnique(Items/Count): both rows are Count=5 duplicates
        // (the cache is guard-independent), but only the weight-filled row satisfies the guard, so only it fires.
        val m = model("FieldFilled(Items/UnitWeight) And RepetitionNotUnique(Items/Count)")
        assertEquals(listOf("/Order[1]/Items[1]/Count|VALUE"),
            fired(m, twoDuplicateRows(m, listOf("1", null), customer = null)),
            "only the guard-satisfying duplicate fires")
    }

    @Test
    fun omissionGuardMakesValueDuplicateAnOmission() {
        // FieldNotFilled(Items/UnitWeight)=OMISSION And RepetitionNotUnique(Items/Count)=VALUE → OMISSION dominates.
        val m = model("FieldNotFilled(Items/UnitWeight) And RepetitionNotUnique(Items/Count)")
        assertEquals(listOf("/Order[1]/Items[1]/Count|OMISSION", "/Order[1]/Items[2]/Count|OMISSION"),
            fired(m, twoDuplicateRows(m, listOf(null, null), customer = null)),
            "empty-weight omission guard turns both value-duplicates into OMISSION")
    }

    @Test
    fun ancestorGuardGatesEveryRow() {
        // The guard field sits ABOVE the keys' group (`/Order`) — one order-level value gates every Items row.
        val m = model("FieldFilled(CustomerName) And RepetitionNotUnique(Items/Count)")
        assertEquals(listOf("/Order[1]/Items[1]/Count|VALUE", "/Order[1]/Items[2]/Count|VALUE"),
            fired(m, twoDuplicateRows(m, listOf(null, null), customer = "Ada")), "order-level guard filled → both fire")
        assertEquals(emptyList(),
            fired(m, twoDuplicateRows(m, listOf(null, null), customer = null)), "order-level guard empty → none fire")
    }

    @Test
    fun repetitionNotUniqueUnderOrIsSupportedAndFires() {
        // RNU is an ordinary 3VL leaf (IF149), so it composes under `Or` like any other leaf — no longer
        // "unsupported". Both rows are Count=5 duplicates, so RNU fires VALUE on each regardless of the Or's other
        // branch (a positive-presence branch — the kernel forbids a NEGATIVE-presence one here, MVK_NEG_CONDITION_IN_ITERATION).
        val m = model("RepetitionNotUnique(Items/Count) Or FieldFilled(Items/UnitWeight)")
        val outcome = DmInterpreter(m).validate(twoDuplicateRows(m, listOf(null, null), customer = null))
        assertTrue(outcome.unsupported.isEmpty(), "RNU under Or is supported: ${outcome.unsupported}")
        assertEquals(listOf("/Order[1]/Items[1]/Count|VALUE", "/Order[1]/Items[2]/Count|VALUE"),
            outcome.messages.filter { it.code == "RU" }.map { "${it.errorPath}|${it.type}" }.sorted(),
            "both duplicate rows fire VALUE through the Or")
    }

    @Test
    fun testRuleEvaluatesRepetitionNotUnique() {
        // As a 3VL leaf (IF149) RNU now flows through the single-rule path too — testRule/evalCandidate used to
        // THROW on a RepetitionNotUnique rule (it was never a PredicateOp; only validate's recognizer handled it).
        val m = model("RepetitionNotUnique(Items/Count)")
        assertEquals(Verdict.FIRED, DmInterpreter(m).testRule("/Order/RU", twoDuplicateRows(m, listOf(null, null), customer = null)).verdict,
            "a duplicate makes the single-rule verdict FIRED")
        val distinct = m.newDocument().group("/Order", 1)
            .also { it.group("/Order/Items", 1, 1).field(sku, "A", 1, 1, 1).field(count, "5", 1, 1, 1) }
            .also { it.group("/Order/Items", 1, 2).field(sku, "B", 1, 2, 1).field(count, "7", 1, 2, 1) }
        assertEquals(Verdict.PASSED, DmInterpreter(m).testRule("/Order/RU", distinct).verdict,
            "distinct counts → PASSED")
    }
}
