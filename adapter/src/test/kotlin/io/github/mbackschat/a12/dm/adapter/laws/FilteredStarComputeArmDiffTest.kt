package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.ThreeEngineRuns.admitted
import io.github.mbackschat.a12.dm.adapter.laws.ThreeEngineRuns.fired
import io.github.mbackschat.a12.dm.adapter.laws.ThreeEngineRuns.outcomes
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Tri-engine differential — SPEC-2026-10-01-04 on our own carrier: a filtered Number star answers from its
 * in-capacity rows on the COMPUTATION arm and under value-reading filters, as [FilteredStarCapacityExtentDiffTest]
 * already holds for the validation arm under a reflexive filter.
 *
 * `Items` holds three rows, so the fourth is over the limit. The keeping filter `Cost > 6` is the row that
 * separates exclusion from filtering: an over-limit `7` would pass it, so a sum of `0` means the row never
 * reached the filter. The reflexive filter separates exclusion from poison: the over-limit row's position read
 * does not make the filter unknown. The reversed document puts the counted value inside capacity, so the same
 * filters answer differently there and the zero above cannot be the operand seeing nothing.
 *
 * Own domain (an insurance claim and its cost items).
 */
// One instance per class: the models are fields, and this task carries timing floors (see FilteredStarCapacityExtentDiffTest).
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FilteredStarComputeArmDiffTest {
    private val base = DocumentModelDraft.named("claim-filtered-star").locale("en_US")
        .rootGroup("Claim") { claim -> claim.stringField("Handler") { }
            .numberField("TotalAll") { it.fractionDigits(0) }.numberField("TotalEvery") { it.fractionDigits(0) }
            .numberField("TotalHigh") { it.fractionDigits(0) }.numberField("TotalLow") { it.fractionDigits(0) }
            .group("Items") { items -> items.repeatable(3).numberField("Cost") { it.fractionDigits(0) } }
        }.build().toJson()

    private val m = BoundModel.ofJson(base)
    private val costs = m.flatten("/Claim/Items*/Cost")
    private val items = m.group("/Claim/Items")
    private val cost = m.num("/Claim/Items/Cost")

    /** The four sums by the target each one computes into. */
    private val sums: Map<String, NumberExpr> = linkedMapOf(
        "TotalAll" to Dsl.sum(costs),
        "TotalEvery" to Dsl.sum(costs.having(Dsl.currentRepetition(items).eq(Dsl.currentRepetition(items)))),
        "TotalHigh" to Dsl.sum(costs.having(cost.asNumber().gt(6L))),
        "TotalLow" to Dsl.sum(costs.having(cost.asNumber().lt(6L))),
    )

    private val computing = admitted(sums.entries.fold(base) { json, (target, sum) ->
        TypedRules.inject(json, m.computation(m.num("/Claim/$target")).name("${target}Comp").calc(sum)
            .message(Dsl.en("$target recomputed.")).build())
    })

    /** Every value any document below can produce, so a fired member names the answer and none firing is visible. */
    private val ladder = listOf(0L, 7L, 10L, 15L, 17L)

    private val validating = admitted(sums.entries.fold(base) { json, (target, sum) ->
        ladder.fold(json) { inner, n -> TypedRules.inject(inner, ruleAt("$target$n", sum.eq(n))) }
    })

    private fun ruleAt(code: String, condition: Condition) = m.rule(m.group("/Claim")).name("${code}Rule")
        .errorField(m.str("/Claim/Handler")).`when`(m.str("/Claim/Handler").filled().and(condition))
        .code(code).severity(Severity.ERROR).message(Dsl.en(code)).build()

    private fun document(vararg values: Int): String =
        """{"Claim":{"Handler":"H","TotalAll":99,"TotalEvery":99,"TotalHigh":99,"TotalLow":99,"Items":[""" +
            values.joinToString(",") { """{"Cost":$it}""" } + "]}}"

    private fun computed(all: Int, every: Int, high: Int, low: Int) = listOf(
        "/Claim[1]/TotalAll|VALUE|$all", "/Claim[1]/TotalEvery|VALUE|$every",
        "/Claim[1]/TotalHigh|VALUE|$high", "/Claim[1]/TotalLow|VALUE|$low").sorted()

    private fun ruleCodes(all: Int, every: Int, high: Int, low: Int) =
        listOf("TotalAll$all", "TotalEvery$every", "TotalHigh$high", "TotalLow$low")

    @Test
    fun theComputationArmSumsTheInCapacityRowsUnderEveryFilter() {
        assertEquals(computed(15, 15, 0, 15), outcomes(computing, document(5, 5, 5, 7)), "7 over the limit")
        assertEquals(computed(15, 15, 0, 15), outcomes(computing, document(5, 5, 5)), "the in-capacity control")
        assertEquals(computed(17, 17, 7, 10), outcomes(computing, document(7, 5, 5, 5)), "5 over the limit")
    }

    @Test
    fun theValidationArmReadsTheSameSumsUnderTheValueReadingFilters() {
        val over = fired(validating, document(5, 5, 5, 7))
        assertEquals(ruleCodes(15, 15, 0, 15).sorted(), over.filter { it.startsWith("Total") }, "7 over the limit")
        assertEquals(ruleCodes(15, 15, 0, 15).sorted(), fired(validating, document(5, 5, 5)), "the in-capacity control")
        val reversed = fired(validating, document(7, 5, 5, 5))
        assertEquals(ruleCodes(17, 17, 7, 10).sorted(), reversed.filter { it.startsWith("Total") }, "5 over the limit")
        // The over-limit row still raises both structural findings on each document that carries one.
        val overLimit = listOf("zuGrosseKontextnummer", "zuGrosseZeile")
        assertEquals(listOf(overLimit, overLimit),
            listOf(over, reversed).map { codes -> codes.filterNot { it.startsWith("Total") } }, "the over-limit findings")
    }
}
