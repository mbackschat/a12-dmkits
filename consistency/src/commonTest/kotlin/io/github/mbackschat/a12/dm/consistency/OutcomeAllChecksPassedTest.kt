package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The checker's one POSITIVE outcome: every family it implements ran, and none rejected the model.
 *
 * **Why a fourth value, when [CheckOutcome]'s own doc said none was coming.** That sentence tied a pass to
 * the coverage registry, which is the bar for claiming A12 would ACCEPT a model — a universal claim over
 * constraints the checker never implemented, and `MVK_INVALID_TIME_ZONE` is the measured witness that it
 * cannot be made. This value makes the weaker claim, and the weaker claim is decidable: the
 * families are a finite enumerable set, so "all of them ran and none rejected" is a fact rather than an
 * extrapolation. It is deliberately NOT called `VALID` — `valid` already names the kernel's verdict on
 * dmtool's envelope, and the two disagree on exactly that timezone model, so one word would carry two
 * contracts across our own surfaces (owner-decided 2026-09-18).
 *
 * **The denominator is [CheckFamily.entries], and that direction is the safe one.** A family added to the
 * enum but never scheduled makes `containsAll` fail, so the outcome degrades to [CheckOutcome.NO_REJECTION]
 * rather than passing on coverage it does not have. The opposite keying — deriving the denominator from the
 * schedule — would call any run complete by construction and could never fail.
 */
class OutcomeAllChecksPassedTest {

    @Test
    fun aPopulatedModelWithNothingWrongPassesEveryImplementedCheck() {
        val report = check(ModelSource(CLEAN))

        assertEquals(
            CheckOutcome.ALL_CHECKS_PASSED,
            report.outcome,
            "every implemented family ran and none rejected, which is the one thing this checker can " +
                "positively assert. Rejecting findings were: " +
                report.findings.filter { it.justifiesRejection }.map { "${it.code}@${it.at}" },
        )
        // Non-vacuity: a model that silently rejected would also have to fail the assertion above, so this
        // pins that the positive arm is positive for the right reason.
        assertTrue(
            report.findings.none { it.justifiesRejection },
            "this row is about a clean model; a rejecting one tests the REJECTED arm instead",
        )
        assertEquals(
            CheckFamily.entries.toSet(),
            report.checksCompleted,
            "the fixture must schedule every family, or the pass above is about a narrower run",
        )
    }

    @Test
    fun aSourceThatDeclaresNothingDoesNotPassEveryCheck() {
        val report = check(ModelSource(NO_DECLARATIONS))

        assertEquals(
            CheckOutcome.NO_REJECTION,
            report.outcome,
            "per-declaration families run zero times here, so nothing may report a complete pass — this is " +
                "the discriminator between the two non-rejecting outcomes",
        )
        assertTrue(
            report.checksCompleted.isNotEmpty(),
            "the model-wide families did run, so this is not the NOT_ESTABLISHED state",
        )
    }

    @Test
    fun aRejectedModelIsNeverReportedAsPassing() {
        val report = check(ModelSource(REJECTED))

        assertEquals(
            CheckOutcome.REJECTED,
            report.outcome,
            "a rejection outranks completeness: a model may complete every family and still be refused",
        )
        assertTrue(
            report.checksCompleted.containsAll(CheckFamily.entries),
            "the fixture must be a COMPLETE run that was rejected, or this passes for the wrong reason — " +
                "an incomplete run would miss the pass arm anyway and the row would lock nothing",
        )
    }

    /**
     * A model A12 refuses outright reads as REJECTED, not as "nothing was checked".
     *
     * **Found by a surviving mutant, and it is not an equivalent one.** Swapping the first two branches of
     * [outcome] leaves every other state alone, because a rejected model normally has a non-empty
     * `checksCompleted` and reaches the rejection branch either way. The one state where the order decides is
     * this one: an unsupported `conditionLanguage` returns `Invalid` **with `checksCompleted` empty**, since
     * there is no reading of the model's conditions to have. Under the swap it would report
     * [CheckOutcome.NOT_ESTABLISHED].
     *
     * That is not a cosmetic difference. `ci-gate.mjs` maps the not-established class to exit 70, an
     * OPERATIONAL failure — "our tooling broke" — where a rejection is exit 1, "your model is refused". The
     * swap would tell a CI job to go debug the checker about a model A12 simply rejects.
     */
    @Test
    fun aModelA12RefusesOutrightIsRejectedRatherThanUnchecked() {
        val report = check(ModelSource(UNSUPPORTED_LANGUAGE))

        assertEquals(
            CheckOutcome.REJECTED,
            report.outcome,
            "a model rejected before anything could run must still read as rejected",
        )
        // The discriminating property, asserted rather than assumed: without it this row would pass on any
        // ordinary rejected model and would not cover the branch order at all.
        assertTrue(
            report.checksCompleted.isEmpty(),
            "this row only discriminates while the rejection carries an EMPTY completed set — if that " +
                "changes, the branch order stops being observable here and this test must be re-derived",
        )
    }

    private companion object {

        /** One group, one field, one rule, one computation, nothing wrong: every producer is asked. */
        private val CLEAN = """
            {"header":{"id":"clean","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Policy","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Holder","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"f1","name":"Premium","Field":{"fieldType":{"type":"NumberType"}}},
                {"type":"Rule","id":"r0","name":"HolderRequired","Rule":{
                  "errorEntityRelPath":"../Holder","errorCondition":"FieldFilled(Holder)",
                  "errorCode":"holder.required",
                  "errorMessage":[{"locale":"en_US","text":"A policy holder is required."}]}},
                {"type":"Computation","id":"c0","name":"Doubled","Computation":{
                  "computedFieldRelPath":"../Premium","computationAlternatives":[{"operation":"{1 + 1}"}],
                  "errorMessage":[{"locale":"en_US","text":"The premium could not be computed."}]}}
              ]}}
             ]}}}
        """.trimIndent()

        /** The same shape, with the rule naming a field that is not declared. */
        private val REJECTED = """
            {"header":{"id":"rejected","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Policy","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Holder","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"f1","name":"Premium","Field":{"fieldType":{"type":"NumberType"}}},
                {"type":"Rule","id":"r0","name":"HolderRequired","Rule":{
                  "errorEntityRelPath":"../Holder","errorCondition":"FieldFilled(Nonexistent)",
                  "errorCode":"holder.required",
                  "errorMessage":[{"locale":"en_US","text":"A policy holder is required."}]}},
                {"type":"Computation","id":"c0","name":"Doubled","Computation":{
                  "computedFieldRelPath":"../Premium","computationAlternatives":[{"operation":"{1 + 1}"}],
                  "errorMessage":[{"locale":"en_US","text":"The premium could not be computed."}]}}
              ]}}
             ]}}}
        """.trimIndent()

        /** A condition language A12 does not support: refused outright, so nothing is examined. */
        private val UNSUPPORTED_LANGUAGE = """
            {"header":{"id":"nolang","locales":[{"code":"en_US"}]},
             "content":{"modelConfig":{"conditionLanguage":{"code":"xx_XX"}},
              "modelRoot":{"rootGroups":[
               {"type":"Group","id":"g0","name":"Policy","Group":{"elements":[
                 {"type":"Field","id":"f0","name":"Holder","Field":{"fieldType":{"type":"StringType"}}}
               ]}}
              ]}}}
        """.trimIndent()

        /** Admitted, and declares nothing — the state `emptyFactBase` reports as a gap. */
        private val NO_DECLARATIONS = """
            {"header":{"id":"empty","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[]}}}
        """.trimIndent()
    }
}
