package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.ast.Tri
import io.github.mbackschat.a12.dm.interpreter.eval.EvalConfig
import io.github.mbackschat.a12.dm.interpreter.eval.EvalContext
import io.github.mbackschat.a12.dm.interpreter.eval.Interpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.LegalCharset
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.Requiredness
import io.github.mbackschat.a12.dm.interpreter.regex.PatternRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **The precondition that lets a read of an EMPTY cell skip its formal check** ([AutoChecks.emptyMayFail]).
 *
 * KF552 makes a required-empty cell unavailable to every authored read, so a presence test asks the formal-result
 * cache about every empty cell it meets. On an ordinary document almost none of them can fail, and asking costs a
 * keyed memo lookup per cell: `FieldFilled` over a non-required column cost five times its 0.13.0 price on the
 * browser first answer (2026-11-06, interleaved jitless profile). The precondition answers for a whole path, once.
 *
 * Two groups, because they fail for different reasons (the pattern of [OverLimitAddressPresenceMultiplatformTest]):
 * the predicate must admit every empty cell the formal check can fail, which only a direct comparison against
 * [AutoChecks.computeFormalResultAt] can falsify; and the wiring must actually skip, which no behavioural test can
 * see, since a deleted skip restores the correct, slower answer.
 */
class EmptyCellFailurePreconditionTest {

    // ── the predicate ────────────────────────────────────────────────────────────────────────────────

    /**
     * Soundness: on every empty cell of both documents, a formal failure implies the precondition. The over-limit row
     * is what a requiredness-only predicate gets wrong: its NOT-required cell fails over-repetition, which the empty
     * branch checks before requiredness.
     */
    @Test
    fun everyEmptyCellThatCanFailIsAdmitted() {
        val checks = checksFor(model)
        var failures = 0
        for ((doc, rows) in listOf(ordinary to 2, overLimit to 3)) {
            val label = if (rows == 2) "ordinary" else "over-limit"
            val gateNeeded = checks.overRepetitionGateNeeded(doc)
            val admissions = ValueAdmissionCache(checks, LOCALE, doc, gateNeeded)
            for (row in 1..rows) {
                val ctx = doc.contextFor(ITEMS, listOf(1, row))
                for (path in FIELDS) {
                    if (ctx.filled(path)) continue
                    val result = checks.computeFormalResultAt(path, listOf(1, row, 1), null, ctx, emptySet(), admissions)
                    if (result !is FormalResult.Invalid) continue
                    failures++
                    assertTrue(checks.emptyMayFail(path, gateNeeded), "$label row $row: $path fails but is not admitted")
                }
            }
        }
        assertEquals(4, failures, "row 1's quantity on both documents, the over-limit SKU and NOTE: else the property is vacuous")
    }

    /** The fast-path control: on an ordinary document a non-required path is NOT admitted, or nothing is skipped. */
    @Test
    fun aNonRequiredPathOnAnOrdinaryDocumentIsNotAdmitted() {
        val checks = checksFor(model)
        assertFalse(checks.overRepetitionGateNeeded(ordinary), "the fixture's premise: an in-cap document")
        assertFalse(checks.emptyMayFail(NOTE, gateNeeded = false))
        assertTrue(checks.emptyMayFail(SKU, gateNeeded = false), "an index field is auto-required")
        assertTrue(checks.emptyMayFail(QUANTITY, gateNeeded = false))
    }

    // ── the wiring ───────────────────────────────────────────────────────────────────────────────────

    /**
     * The shared root: every reader that asks the formal gate about an empty cell — a presence test, a value read, a
     * value function, a first-filled walk — reaches [FormalResultCache]'s empty branch. On an inadmissible path it must
     * answer without the per-cell memo and the requiredness probe, which read the row context ([EvalContext.filled]).
     */
    @Test
    fun theFormalCacheAnswersAnInadmissibleEmptyCellWithoutProbingIt() {
        val checks = checksFor(model)
        val cache = FormalResultCache(checks, LOCALE, emptySet(),
            valueAdmissions = ValueAdmissionCache(checks, LOCALE, ordinary, overRepetitionGateNeeded = false))
        val row = ordinary.contextFor(ITEMS, listOf(1, 1))
        var probes = 0
        val counting = object : EvalContext by row {
            override fun filled(path: String): Boolean {
                probes++
                return row.filled(path)
            }
        }

        assertEquals(null, cache.failureAt(NOTE, listOf(1, 1, 1), counting))
        assertEquals(0, probes, "an empty NOTE cannot fail, so nothing about its row may be read")
        assertTrue(cache.failureAt(QUANTITY, listOf(1, 1, 1), counting) != null, "the control: a required empty fails")
        assertTrue(probes > 0, "and reaching that answer does probe the row, so the zero above is not vacuous")
    }

    /** `FieldFilled` on an empty cell consults the formal gate only where the call's precondition admits the path. */
    @Test
    fun anEmptyPresenceTestSkipsTheGateOnAnInadmissiblePath() {
        val ctx = ordinary.contextFor(ITEMS, listOf(1, 1))
        var consulted = 0
        val gate: (EvalContext, String) -> Boolean = { _, _ -> consulted++; true }

        val skipped = Interpreter(ctx, EvalConfig(emptyMayBeUnavailable = { false }), invalid = gate).filledTri(ctx, NOTE)
        assertEquals(Tri.FALSE, skipped)
        assertEquals(0, consulted, "an inadmissible empty cell must not reach the gate")

        val asked = Interpreter(ctx, EvalConfig(emptyMayBeUnavailable = { true }), invalid = gate).filledTri(ctx, NOTE)
        assertEquals(Tri.UNKNOWN, asked, "and an admissible one still reads the gate's answer")
        assertEquals(1, consulted)
    }

    private val model = ModelDef(
        listOf(
            FieldDef(SKU, Kind.STRING),
            FieldDef(QUANTITY, Kind.NUMBER, scale = 0, required = Requiredness.RELATIVE_TO_PARENT),
            FieldDef(NOTE, Kind.STRING),
        ),
        emptyList(),
        repeatableGroups = setOf(ITEMS),
        declaredReps = mapOf(ITEMS to 2),
        indexFields = setOf(SKU),
        groupPaths = setOf(ORDER, ITEMS),
    )

    /** Row 1 lacks its quantity (required); row 2 is complete. NOTE is empty on both and never required. A
     *  [Document] builds in place, so each fixture is a fresh one. */
    private fun twoRows() = model.newDocument().group(ORDER, 1)
        .group(ITEMS, 1, 1).field(SKU, "A-1", 1, 1, 1)
        .group(ITEMS, 1, 2).field(SKU, "A-2", 1, 2, 1).field(QUANTITY, "3", 1, 2, 1)

    private val ordinary = twoRows()

    /** Row 3 is over the cap of 2 and lacks its index: its SKU fails as required-empty AND over-limit, its NOTE as
     *  over-limit only. */
    private val overLimit = twoRows().group(ITEMS, 1, 3).field(QUANTITY, "1", 1, 3, 1)

    private fun checksFor(def: ModelDef) = AutoChecks(
        def, LegalCharset.BMP, emptyMap(), emptyMap(), PatternRegistry(), ModelPathIndex(def),
    ) { _, _, _ -> "" }

    private companion object {
        const val LOCALE = "en_US"
        const val ORDER = "/Order"
        const val ITEMS = "/Order/Items"
        const val SKU = "/Order/Items/Sku"
        const val QUANTITY = "/Order/Items/Quantity"
        const val NOTE = "/Order/Items/Note"
        val FIELDS = listOf(SKU, QUANTITY, NOTE)
    }
}
