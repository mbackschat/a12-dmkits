package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Deciding one sweep's cells costs one comparison per distinct FACT, not one per cell.
 *
 * **The defect this locks was shipped and measured, and a ratio at one model size could not see it.** Every
 * cell recorded its own copy of the facts it read, so a model-wide fact — [FactRead.DeclarationSetShape]
 * above all, whose value is the whole declaration set — was deep-compared once per cell. Cells grow with the
 * model and so does that value, which is O(cells x declarations): measured 2026-09-18 on the JVM at 100/200/
 * 400 declarations, a store whose cells each carry the shape validates in 25.8 / 96.6 / 373.3 ms, ~4x per
 * doubling, while the same store carrying one [FactRead.DeclarationRow] costs 1.3 / 1.6 / 2.7 ms. The
 * after-edit keystroke ratio therefore MOVED with model size — 0.346x at 20 programs, 0.906x at 200, and
 * 1.279-1.529x at 400, breaching a ceiling the lane asserted at 200 and so never saw.
 *
 * **Why a count and not a clock.** The invariant is arithmetic — each distinct fact is compared once — so a
 * counter falsifies it deterministically on every carrier and in every lane, where a timing bar has to be
 * calibrated and can only fail once the constant is already large enough to hurt. [Sweep.comparisons] is the
 * enforcement locus; [aSweepIsTheSameDecisionAsAPerKeyValidation] is what stops the memo from buying its
 * speed with a wrong answer, which is the only way this optimization could be worse than the defect.
 */
class CellValidationSweepTest {

    @Test
    fun aModelWideFactIsComparedOncePerSweepHoweverManyCellsRecordedIt() {
        val model = prepared(modelJson(60))
        val keys = schedule(TrackedFactBase(model, ReadTranscript())).map { it.key }
        val cells = CheckCells(
            keys.associateWith {
                CheckCell(emptyList(), emptyList(), mapOf(FactRead.DeclarationSetShape to model.declarationSetShape))
            },
        )
        // The positive control: without many cells recording it, comparing it once would be no achievement.
        assertTrue(keys.size > 100, "the fixture schedules only ${keys.size} cells, so this proves nothing")

        val sweep = cells.sweepOver(model)
        keys.forEach { sweep.validIn(it) }

        assertEquals(1, sweep.comparisons, "the sweep compared one distinct fact more than once")
    }

    @Test
    fun aRealSweepComparesFarFewerFactsThanItsCellsRecorded() {
        val model = prepared(modelJson(60))
        val built = checkRun(model, "sweep", emptyList(), CheckCells.NONE, CellRetention.KEPT)
        val keys = schedule(TrackedFactBase(model, ReadTranscript())).map { it.key }

        val sweep = built.cells.sweepOver(model)
        keys.forEach { sweep.validIn(it) }

        assertTrue(sweep.examined > 0, "no cell recorded a fact, so there is nothing for a sweep to collapse")
        assertTrue(
            sweep.comparisons < sweep.examined,
            "the sweep compared ${sweep.comparisons} of ${sweep.examined} examined reads, so it collapsed none",
        )
    }

    @Test
    fun aSweepIsTheSameDecisionAsAPerKeyValidation() {
        val model = prepared(modelJson(12))
        val built = checkRun(model, "sweep", emptyList(), CheckCells.NONE, CellRetention.KEPT)
        val keys = schedule(TrackedFactBase(model, ReadTranscript())).map { it.key }
        val drafted = model.draftedWith("/Order/Require1", "errorCondition", "FieldFilled(Input2)")
        assertIs<Drafted.Applied>(drafted, "the fixture's draft must apply")

        for (against in listOf(model, drafted.prepared)) {
            val sweep = built.cells.sweepOver(against)
            assertEquals(
                keys.map { built.cells.validIn(against, it) },
                keys.map { sweep.validIn(it) },
                "a sweep decided a cell differently than a per-key validation did",
            )
        }
    }

    private fun prepared(json: String): PreparedModel {
        val admitted = admit(ModelSource(json))
        assertIs<Admission.Admitted>(admitted, "the fixture must be admissible")
        return prepare(admitted)
    }

    /** A flat model of [n] rule-and-field pairs — enough cells that a per-cell cost is visible as a count. */
    private fun modelJson(n: Int): String {
        val elements = (0 until n).joinToString(",") { i ->
            """{"type":"Field","id":"f$i","name":"Input$i","Field":{"fieldType":{"type":"NumberType"}}},""" +
                """{"type":"Rule","id":"r$i","name":"Require$i","Rule":{"errorEntityRelPath":"Input$i",""" +
                """"errorCondition":"FieldFilled(Input$i)","errorCode":"c$i",""" +
                """"errorMessage":[{"locale":"en_US","text":"x"}]}}"""
        }
        return """{"header":{"id":"sweep","locales":[{"code":"en_US"}]},"content":{"modelRoot":""" +
            """{"rootGroups":[{"type":"Group","id":"g0","name":"Order","Group":{"elements":[$elements]}}]}}}"""
    }
}
