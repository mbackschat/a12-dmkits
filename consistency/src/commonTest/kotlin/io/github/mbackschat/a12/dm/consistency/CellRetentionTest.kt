package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A check that will not be asked again does not pay for a store nobody keeps.
 *
 * **The cost is measured, not supposed.** Recording every fact a family reads and then capturing each read's
 * value is ~180 ms of a 509 ms 200-program check, and `check` discards the store the instant it has the
 * report. **The rule is that a call keeps a store exactly when it keeps the store** — `check` and
 * `checkModelJson` discard, `snapshotOf` and `snapshotOfJson` keep, and [DRAFT_RETENTION] discards because a
 * draft answers a hypothetical and leaves its snapshot unchanged. *(Corrected 2026-09-18: this read "tracks
 * the stateless/stateful split exactly, on every carrier", which put `draft` on the keeping side by entry
 * point while its caller collected nothing — the rule's letter agreeing with its reason on every call that
 * had been examined. An earlier clause read "a Node consumer cannot hold one at all", which the shipped
 * `snapshotOfJson` falsified the same day; the optimization survived both, because what decides it is the
 * call's own retention and not the carrier's surface.)*
 *
 * **The row that matters is the EQUALITY one.** An optimization on the check path is only safe if the report
 * is the same report, so the discarding run is compared against the keeping run over the same model rather
 * than against a recorded expectation: a spelling nobody notices cannot then pass by matching itself. The
 * store rows are the positive and negative control for the optimization actually happening — under `KEPT` a
 * scheduled cell answers, under `DISCARDED` none does, and without both halves the test would pass on a
 * build that retained everything.
 */
class CellRetentionTest {

    @Test
    fun discardingTheStoreChangesNothingAboutTheReport() {
        val prepared = prepared(MODEL)

        val kept = checkRun(prepared, "retention", emptyList(), CheckCells.NONE, CellRetention.KEPT)
        val discarded = checkRun(prepared, "retention", emptyList(), CheckCells.NONE, CellRetention.DISCARDED)

        assertEquals(kept.report, discarded.report, "discarding the store changed the report")
    }

    @Test
    fun aKeptStoreAnswersForItsOwnModelAndADiscardedOneHasNothingToAnswerWith() {
        val prepared = prepared(MODEL)
        val keys = schedule(TrackedFactBase(prepared, ReadTranscript())).map { it.key }
        // The fixture's own claim, checked rather than asserted in prose: all producer shapes run, so
        // the rows below are about retention and not about one dispatch shape happening to be scheduled.
        assertEquals(
            setOf(
                CheckProducer.ModelWide::class.simpleName,
                CheckProducer.PerDeclaration::class.simpleName,
                CheckProducer.PerDeclarationWalk::class.simpleName,
                CheckProducer.Condition::class.simpleName,
                CheckProducer.Declaration::class.simpleName,
                CheckProducer.Messages::class.simpleName,
            ),
            keys.map { it.producer::class.simpleName }.toSet(),
            "the fixture does not reach all producer shapes",
        )

        val kept = checkRun(prepared, "retention", emptyList(), CheckCells.NONE, CellRetention.KEPT)
        val discarded = checkRun(prepared, "retention", emptyList(), CheckCells.NONE, CellRetention.DISCARDED)

        assertTrue(
            keys.any { kept.cells.validIn(prepared, it) != null },
            "a kept store answered for the very model it was built on for no key, so the DISCARDED row " +
                "below would pass on a build that never stored anything",
        )
        assertEquals(
            emptyList(),
            keys.filter { discarded.cells.validIn(prepared, it) != null },
            "a discarded store answered for a key, so the check paid for cells nobody keeps",
        )
    }

    /**
     * The saving is free: what a check reuses is decided against the store it is HANDED, not the one it
     * builds, so retention cannot cost a cell.
     *
     * This is the argument [DRAFT_RETENTION] rests on, made executable rather than reasoned — the two runs
     * differ in exactly one builder argument, over one carried store, so nothing else can explain a
     * difference. Without the positive control the row would pass on a build that reused nothing at all,
     * which is the shape it is guarding against.
     */
    @Test
    fun discardingTheOutgoingStoreCostsNoneOfTheIncomingReuse() {
        val prepared = prepared(MODEL)
        val carried = checkRun(prepared, "retention", emptyList(), CheckCells.NONE, CellRetention.KEPT).cells

        val kept = checkRun(prepared, "retention", emptyList(), carried, CellRetention.KEPT)
        val discarded = checkRun(prepared, "retention", emptyList(), carried, CellRetention.DISCARDED)

        assertTrue(
            kept.families.reused > 0,
            "the carried store answered for no cell, so the equality below would hold for want of reuse",
        )
        assertEquals(kept.families, discarded.families, "discarding the outgoing store cost incoming reuse")
        assertEquals(kept.report, discarded.report, "discarding the outgoing store changed the report")
    }

    /**
     * A draft pays for no store, because it keeps none.
     *
     * A wiring pin rather than a behavioural one, and it says so: `draft` never exposes the run it makes, so
     * there is no output through which the choice is observable, and the enforcement locus is the named
     * constant itself. What makes the choice SOUND is the row above, which is behavioural.
     */
    @Test
    fun aDraftDiscardsTheStoreItWouldOtherwiseThrowAway() {
        assertEquals(
            CellRetention.DISCARDED,
            DRAFT_RETENTION,
            "a draft reads only the run's report and reuse counts, so recording its transcript is work " +
                "nobody collects — see DRAFT_RETENTION for the measurement",
        )
    }

    private fun prepared(json: String): PreparedModel {
        val admitted = admit(ModelSource(json))
        assertIs<Admission.Admitted>(admitted, "the fixture must be admissible")
        return prepare(admitted)
    }

    private companion object {
        /** A rule and a repeating group, which is what reaches all producer shapes — checked above. */
        private val MODEL = """
            {"header":{"id":"retention","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Total","Field":{"fieldType":{"type":"NumberType"}}},
                {"type":"Group","id":"g1","name":"Lines","Group":{"repeatability":5,"elements":[
                  {"type":"Field","id":"f1","name":"Amount","Field":{"fieldType":{"type":"NumberType"}}},
                  {"type":"Rule","id":"r0","name":"AmountRequired","Rule":{
                    "errorEntityRelPath":"Amount","errorCondition":"FieldFilled(Amount)",
                    "errorCode":"amount.required","errorMessage":[{"locale":"en_US","text":"x"}]}}
                ]}}
              ]}}
             ]}}}
        """.trimIndent()
    }
}
