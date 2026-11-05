package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.conformance.Op
import io.github.mbackschat.a12.dm.interpreter.conformance.Placement
import io.github.mbackschat.a12.dm.interpreter.conformance.PlacementKind
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A computed `SumOfTerms` whose term divides, read on both kernel 31.1.1 strategies and the interpreter: a row
 * that divides by zero, or by an empty divisor, adds nothing, exactly as the validation rows of
 * [KF670](../../../../../../../../../../../docs/KERNEL-FINDINGS.md#kf670) count it as an empty row. So a sum whose
 * only row divides by zero is 0, and no outcome is cleared or errored. The sum is wrapped in `RoundDown` because a
 * quotient has no static scale and the kernel refuses to assign it unrounded (`MVK_INVALID_COMPARE_DEC_PLACES`).
 *
 * The rival reading is that a failed term makes the whole sum undecidable, as a malformed cell does (the CLEARED row
 * of [SumOfTermsRuntimeLawsTest.aComputedTermSum]); it would clear the total on every row but the control. The
 * control divides cleanly, so a row that differs from it names the zero divisor. Requested by a12-kernel-lean as
 * SPEC-2026-11-04-02; the rows mirror its four cases in our own domain (order lines, price per unit).
 */
class SumOfTermsDivisionComputationLawsTest {

    private companion object {
        const val LINES = "/Order/Lines"
    }

    private val model: String = run {
        val base = DocumentModelDraft.named("order-unit-prices").locale("en_US")
            .rootGroup("Order") { o ->
                o.numberField("UnitPriceSum") { it.fractionDigits(2) }
                    .group("Lines") { l ->
                        l.repeatable(4)
                            .numberField("Price") { it.fractionDigits(2) }
                            .numberField("Units") { it.fractionDigits(0) }
                    }
            }.build().toJson()
        RuntimeLaws.withComputation(base, "/Order", "UnitPriceSumComp", "../UnitPriceSum",
            "RoundDown(SumOfTerms(Lines*, [Lines/Price] / [Lines/Units]), 2)")
    }

    /** One order whose lines carry `price to units`; a null units leaves the divisor unfilled. */
    private fun order(vararg lines: Pair<String, String?>): List<Placement> =
        listOf(Placement(PlacementKind.GROUP, "/Order", listOf(1))) +
            lines.withIndex().flatMap { (i, line) ->
                val row = i + 1
                listOfNotNull(Placement(PlacementKind.GROUP, LINES, listOf(1, row)),
                    Placement(PlacementKind.FIELD, "$LINES/Price", listOf(1, row, 1), line.first),
                    line.second?.let { Placement(PlacementKind.FIELD, "$LINES/Units", listOf(1, row, 1), it) })
            }

    /** Both strategies' readings and the interpreter's, asserted equal; the Groovy one returned. */
    private fun computed(placements: List<Placement>): List<String> {
        val groovy = CorpusEngines.groovyDynamic(model, placements, Op.Compute)
        assertEquals(groovy, CorpusEngines.javaStatic(model, placements, Op.Compute), "generated Java parts from Groovy")
        assertEquals(groovy, CorpusEngines.interpret(model, placements, Op.Compute), "the interpreter parts from Groovy")
        return groovy
    }

    @Test
    fun theModelIsAdmitted() = assertEquals(listOf<String>(), RuntimeLaws.consistencyMessages(model))

    @Test
    fun aRowDividingByZeroOrByAnEmptyDivisorAddsNothing() = assertEquals(
        linkedMapOf(
            "second line divides by zero" to listOf("/Order[1]/UnitPriceSum|VALUE|5"),
            "control, every line divides" to listOf("/Order[1]/UnitPriceSum|VALUE|7"),
            "the only line divides by zero" to listOf("/Order[1]/UnitPriceSum|VALUE|0"),
            "second line has no units" to listOf("/Order[1]/UnitPriceSum|VALUE|5"),
        ),
        linkedMapOf(
            "second line divides by zero" to computed(order("10" to "2", "6" to "0")),
            "control, every line divides" to computed(order("10" to "2", "6" to "3")),
            "the only line divides by zero" to computed(order("6" to "0")),
            "second line has no units" to computed(order("10" to "2", "6" to null)),
        ),
    )
}
