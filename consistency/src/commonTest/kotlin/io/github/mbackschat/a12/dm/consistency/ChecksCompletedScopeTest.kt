package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `checksCompleted` is EVIDENCE, so it names what ran — and on a model with no declarations that is not
 * everything.
 *
 * Before the per-declaration key every producer ran exactly once per check, so deriving the set from the
 * registry was exact. A per-declaration producer now runs once per declaration, which is **zero** times on a
 * source that declares no elements — a state the report discloses as a gap of its own, so it is reachable
 * rather than hypothetical. The registry-derived set went on naming all of them (cold review, 2026-09-18).
 *
 * The fix derives from the SCHEDULE instead, which is the same set whenever anything is declared; the
 * populated row below is the control that says so.
 */
class ChecksCompletedScopeTest {

    @Test
    fun aSourceThatDeclaresNothingCompletesOnlyTheChecksThatCouldRun() {
        val report = checkRead(ModelSource(NO_DECLARATIONS))
        val perDeclarationOnly = CHECK_PRODUCERS
            .filterNot { it is CheckProducer.ModelWide }
            .flatMap { it.completes }
            .toSet() - CHECK_PRODUCERS.filterIsInstance<CheckProducer.ModelWide>()
            .flatMap { it.completes }.toSet()

        assertEquals(emptySet(), report.checksCompleted.intersect(perDeclarationOnly),
            "a per-declaration family runs once per declaration, so on a source with none it ran zero times " +
                "and `checksCompleted` may not offer it as evidence")
        // Non-vacuity in both directions: the set is not simply empty, and the state under test is the one
        // the report itself discloses rather than a shape nobody reaches.
        assertTrue(report.checksCompleted.isNotEmpty(),
            "the model-wide families did run on this source, so an empty set would be the opposite error")
        assertTrue(report.gaps.any { it.reason.contains("declares no elements") },
            "this row is about the disclosed no-element state; without that gap it is testing another model")
    }

    @Test
    fun aSourceThatDeclaresSomethingStillCompletesEveryFamily() {
        val report = checkRead(ModelSource(ONE_OF_EACH))

        assertEquals(CheckFamily.entries.toSet(), report.checksCompleted,
            "deriving from the schedule may not narrow the ordinary case: every producer is asked at least " +
                "once as soon as anything is declared")
    }

    private companion object {

        /** Admitted, and declares nothing — the state `emptyFactBase` reports as a gap. */
        private val NO_DECLARATIONS = """
            {"header":{"id":"empty","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[]}}}
        """.trimIndent()

        /** One group, one field, one rule, one computation: enough that every producer is asked. */
        private val ONE_OF_EACH = """
            {"header":{"id":"populated","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Code","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"f1","name":"Total","Field":{"fieldType":{"type":"NumberType"}}},
                {"type":"Rule","id":"r0","name":"CodeRequired","Rule":{
                  "errorEntityRelPath":"../Code","errorCondition":"FieldFilled(Code)",
                  "errorCode":"code.required"}},
                {"type":"Computation","id":"c0","name":"Doubled","Computation":{
                  "computedFieldRelPath":"../Total","computationAlternatives":[{"operation":"{1 + 1}"}]}}
              ]}}
             ]}}}
        """.trimIndent()
    }
}
