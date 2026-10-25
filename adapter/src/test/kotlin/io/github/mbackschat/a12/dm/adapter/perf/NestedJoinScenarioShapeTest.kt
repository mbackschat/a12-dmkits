package io.github.mbackschat.a12.dm.adapter.perf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The nested-join perf rows ([PerfCatalog.nestedJoin], [PerfCatalog.nestedJoinComputation]) time real work: every
 * joined order's lines are visited, and lines 3 and 4 exceed the SKU's stock of 2. A rule that never reached the
 * nested level, or a join that never matched, would leave both engines agreeing on an empty result, which the
 * parity lane alone cannot tell from a correct one. The expectation is the same on the interpreter and on A12, so
 * the kernel settles it.
 */
class NestedJoinScenarioShapeTest {

    private val orders = 1..3

    @Test
    fun theRuleFiresOnTheLinesThatExceedTheirStock() {
        val expected = orders.flatMap { o -> listOf(3, 4).map { l -> "NJ|VALUE_ERROR|/Ship[1]/Orders[$o]/Lines[$l]/Units" } }
        assertEquals(expected, outcomes(PerfCatalog.nestedJoin()))
    }

    @Test
    fun theComputationFillsTheShortfallOfTheLinesThatExceedTheirStock() {
        // The projection keeps the computed cells only: lines 1 and 2 fail the guard and stay absent.
        val expected = orders.flatMap { o -> listOf(3 to 1, 4 to 2).map { (l, shortfall) ->
            "/Ship[1]/Orders[$o]/Lines[$l]/Shortfall|VALUE|$shortfall" } }
        assertEquals(expected, outcomes(PerfCatalog.nestedJoinComputation()))
    }

    private fun outcomes(scenario: PerfScenario): List<String> {
        val interp = InterpreterPerfEngine.build(scenario).instance(orders.last).outcome()
        val groovy = KernelPerfEngine.build(scenario).instance(orders.last).outcome()
        assertEquals(emptyList<Any>(), interp.unsupported, "the interpreter evaluates every rule")
        assertEquals(interp.signatures, groovy.signatures, "interpreter against A12")
        return interp.signatures
    }
}
