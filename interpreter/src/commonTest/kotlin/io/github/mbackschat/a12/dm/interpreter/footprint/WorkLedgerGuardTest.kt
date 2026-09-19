package io.github.mbackschat.a12.dm.interpreter.footprint

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.WorkLedger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Each footprint scenario does an EXACT, constant amount of work per row — the gate NFR #1's timed rows cannot have.
 *
 * **What this closes (IG152).** The standing anchor holds H4's byte rows at 5 %, but no quantity this estate
 * TIMES can carry that bar: all 66 keys measurable across three or more committed runs spread more than 5 %
 * run-to-run (28.8 % at the median), and the margin — whose noise genuinely cancels — still leaves zero of 68
 * rows inside 5 %. So a uniform sub-1.65x regression passes every permanent gate, and two migration phases at
 * +4.9 % would sit permanently invisible. Work is a COUNT, so it is barred at zero instead of five.
 *
 * **Why exact equality rather than a tolerance, and why it cannot be defeated by a small regression.** The
 * per-row constants below are integers in the low single digits, so the smallest regression that exists at all
 * — one extra node per row — is a third of the smallest row's work. There is no sub-threshold band to hide in,
 * which is the whole point. The intercept is zero on every row measured (scale 200 costs exactly 200, never
 * 201), so a per-CALL regression is caught by the same equality rather than needing its own term.
 *
 * **It runs on every carrier, and that is a second invariant for free.** `commonTest` executes on JVM and on
 * Node, and both are held to the same integers — so a carrier-specific divergence in evaluation work fails
 * here rather than waiting for someone to compare two platforms' numbers by hand.
 *
 * **What a count does NOT see:** cost per unit. Identical work in more cycles moves nothing here; allocation is
 * the estate's instrument for that half and is already anchored. Same-work-same-allocation-more-cycles remains
 * uncovered by both, which [io.github.mbackschat.a12.dm.interpreter.eval.WorkLedger] states.
 *
 * Changing a constant below is a deliberate, legible edit: down is an optimization, up is the regression this
 * exists to refuse.
 */
class WorkLedgerGuardTest {

    /** The work one row of a scenario costs, measured 2026-09-19 on both carriers. */
    private data class PerRow(val conditionNodes: Int, val expressionNodes: Int, val ruleRows: Int)

    @Test
    fun everyScenarioDoesExactlyTheWorkItsPerRowConstantPredicts() {
        for (scenario in FootprintScenario.entries) {
            val perRow = PER_ROW.getValue(scenario)
            for (scale in SCALES) {
                val work = workOf(scenario, scale)
                val where = "${scenario.id} at scale $scale"
                assertEquals(perRow.conditionNodes.toLong() * scale, work.conditionNodes,
                    "$where evaluated a different number of condition nodes per row")
                assertEquals(perRow.expressionNodes.toLong() * scale, work.expressionNodes,
                    "$where evaluated a different number of value-expression nodes per row")
                assertEquals(perRow.ruleRows.toLong() * scale, work.ruleRows,
                    "$where instantiated a different number of validation rule rows")
            }
        }
    }

    /**
     * The vacuity control: constants of zero would satisfy the bar above while measuring nothing.
     *
     * Each scenario must do SOME work, and its total must actually grow with the scale — otherwise a ledger
     * that stopped being installed, or a fixture that stopped scaling, would read as a clean pass.
     */
    @Test
    fun everyScenarioDoesWorkThatGrowsWithItsScale() {
        for (scenario in FootprintScenario.entries) {
            val small = workOf(scenario, SCALES.first()).total()
            val large = workOf(scenario, SCALES.last()).total()
            assertTrue(small > 0, "${scenario.id} recorded no work at all, so the ledger reached nothing")
            assertTrue(large > small,
                "${scenario.id} recorded $large at scale ${SCALES.last()} against $small at ${SCALES.first()}, " +
                    "so its work does not scale and the per-row bar above is vacuous")
        }
    }

    /** Determinism is the property the whole gate rests on: an unrepeatable count could not be barred at zero. */
    @Test
    fun theSameWorkloadCostsTheSameWorkTwice() {
        for (scenario in FootprintScenario.entries) {
            val first = workOf(scenario, SCALES.first())
            val second = workOf(scenario, SCALES.first())
            assertEquals(first.triple(), second.triple(),
                "${scenario.id} counted different work on two identical runs, so the count is not deterministic " +
                    "and cannot carry an exact bar")
        }
    }

    private fun workOf(scenario: FootprintScenario, scale: Int): WorkLedger {
        val fixture = FootprintScenarios.fixture(scenario, scale)
        val ledger = WorkLedger()
        val result = fixture.evaluate(DmInterpreter(fixture.model).also { it.workLedger = ledger })
        // A scenario that stopped producing its expected outcome is measuring some other workload, and its
        // counts would then be locked against the wrong thing.
        assertTrue(result.supported, "${scenario.id} at scale $scale is no longer fully supported")
        assertEquals(scenario.expectedOutcome, result.outcome,
            "${scenario.id} at scale $scale changed outcome, so its work is no longer the work this locks")
        return ledger
    }

    private fun WorkLedger.total(): Long = conditionNodes + expressionNodes + ruleRows

    private fun WorkLedger.triple(): List<Long> = listOf(conditionNodes, expressionNodes, ruleRows)

    private companion object {

        /**
         * Three scales, not two: an exact bar at a single scale cannot distinguish per-row work from a fixed
         * per-call cost, and the third point is what shows the growth is linear rather than merely monotone.
         * 1600 is deliberately left to the footprint worker — it buys no further discrimination against an
         * exact bar and would quadruple this lane's cost on the Node carrier.
         */
        val SCALES = listOf(200, 400, 800)

        val PER_ROW = mapOf(
            // One presence rule over one row each.
            FootprintScenario.SIMPLE_VALIDATION to PerRow(conditionNodes = 1, expressionNodes = 0, ruleRows = 1),
            // Three condition nodes per row, and no value-expression work: the correlation fires on presence and
            // duplicate predicates, which resolve no operand values.
            FootprintScenario.EQUALITY_CORRELATION to PerRow(conditionNodes = 3, expressionNodes = 0, ruleRows = 1),
            // Compute instantiates no VALIDATION rule row, so its iteration shows up as expression work instead.
            FootprintScenario.COMPUTE_OVERLAY to PerRow(conditionNodes = 3, expressionNodes = 9, ruleRows = 0),
        )
    }
}
