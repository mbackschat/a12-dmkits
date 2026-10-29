package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A cell's charge may not depend on WHICH OTHER cells ran, which is what a memoized view can silently break.
 *
 * **The invariant, stated twice by the design itself.** [ReadTranscript] says charging an inner producer
 * instead of the outer one "would leave the outer cell keyed on less than it read, which is the one
 * direction that makes reuse UNSOUND"; `TrackedDeclaration.isRepeatable` says "a charge narrower than what
 * was read is the one direction that makes reuse UNSOUND rather than coarse". Both are about a cell whose
 * recorded reads are a subset of the facts its answer actually came from, because [CheckCells.validIn]
 * revalidates exactly the recorded set and nothing else.
 *
 * **The mechanism a memoized view introduces, which neither of those notes anticipated.** A `by lazy` on
 * [TrackedFactBase]'s shared views is computed ONCE per check, inside whichever producer happens to force
 * it. If that computation reads through the barrier, its reads are charged to that first producer alone, and
 * every later producer is answered from the same memoized value while recording nothing for it. Order then
 * decides soundness, which no fixture that runs the whole dispatch can see: the leak is invisible in a full
 * profile because the reads ARE present, just all on one row.
 *
 * **So the oracle is solo-versus-group, and it covers the class rather than one view.** A producer run alone
 * must charge exactly what it charges alongside the others. Run alone it forces every view it needs and is
 * charged for whatever those computations read; run in company it may find them already computed. Equality
 * of the two is precisely the absence of a leak, for any view, present or future — no list of views to keep
 * up to date, and no need to name which producer happens to be first.
 *
 * The fixture must declare a REPEATABLE group, or the repeatable-group view is an empty list that every
 * producer can compute without reading any body, and the test passes while proving nothing. That is checked
 * rather than assumed.
 */
class MemoizedViewChargeTest {

    private val source = ModelSource(
        """
        {"header":{"modelVersion":"28.4.0","id":"views","locales":[{"code":"en_US"}]},
         "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Code","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Field","id":"f2","name":"Total","Field":{"fieldType":{"type":"NumberType"}}},
            {"type":"Rule","id":"r0","name":"CodeRequired","Rule":{
              "errorEntityRelPath":"../Code","errorCondition":"FieldFilled(Code)",
              "errorCode":"code.required"}},
            {"type":"Computation","id":"c0","name":"Doubled","Computation":{
              "computedFieldRelPath":"../Total","computationAlternatives":[{"operation":"{1 + 1}"}]}},
            {"type":"Group","id":"g1","name":"Lines","Group":{"repeatability":9,"elements":[
              {"type":"Field","id":"f3","name":"Amount","Field":{"fieldType":{"type":"NumberType"}}}
            ]}}
          ]}}
         ]}}}
        """.trimIndent(),
    )

    private fun prepared(): PreparedModel {
        val admitted = admit(source)
        assertIs<Admission.Admitted>(admitted, "the fixture must be admissible")
        return prepare(admitted)
    }

    /**
     * What [only] charges — run by itself when [only] is given, or the whole dispatch when it is null.
     *
     * A FRESH fact base each time, which is the whole point: the views are memoized per fact base, so a
     * reused one would carry another run's already-computed value and measure nothing.
     */
    private fun chargesWhenRunning(only: CellKey?): Map<CellKey, Set<FactRead>> {
        val base = prepared()
        val tracked = TrackedFactBase(base, ReadTranscript())
        val scheduled = schedule(tracked)
        val running = if (only == null) scheduled else scheduled.filter { it.key == only }
        running.forEach { cell -> tracked.whileProducing(cell.key) { cell.produce() } }
        return running.associate { it.key to tracked.observedWhileProducing(it.key) }
    }

    @Test
    fun theFixtureDeclaresARepeatableGroupOrThisTestProvesNothing() {
        val base = prepared()
        val repeatable = base.elements.filter { it.isRepeatableGroup }.map { it.path }
        assertEquals(
            listOf("/Order/Lines"),
            repeatable,
            "the repeatable-group view must be non-empty here, or every producer computes it without " +
                "reading a body and the leak this test looks for cannot occur",
        )
    }

    @Test
    fun aCellChargesTheSameFactsAloneAsItDoesAlongsideTheOtherProducers() {
        val together = chargesWhenRunning(null)
        assertTrue(together.size > 1, "a one-cell dispatch cannot show an order-dependent charge")

        val narrowed = together.keys.mapNotNull { key ->
            val alone = chargesWhenRunning(key)[key].orEmpty()
            val missing = alone - together.getValue(key)
            missing.takeIf { it.isNotEmpty() }?.let { key to it }
        }

        assertEquals(
            listOf(),
            narrowed,
            "${narrowed.size} cell(s) charged LESS in company than alone, so a memoized view read through " +
                "the barrier and charged its reads to whichever producer forced it first. Those cells are " +
                "keyed on less than they were answered from, which `validIn` will happily revalidate:\n  " +
                narrowed.joinToString("\n  ") { (key, missing) ->
                    "${key.producer.completes.joinToString(", ")}" +
                        (key.at?.let { " @${it.path}" } ?: "") + " lost " + missing.joinToString(", ")
                },
        )
    }
}
