package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.ThreeEngineRuns.admitted
import io.github.mbackschat.a12.dm.adapter.laws.ThreeEngineRuns.outcomes
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * What a computation reads where a cell is malformed or a computed input failed, on the open clauses of
 * a12-kernel-lean SPEC-2026-10-26-02, -05 and -12 ([KERNEL-FINDINGS KF630]). Every row is three-engine agreed.
 *
 * - A field presence read (`Ff`) poisons on a malformed cell, while group presence (`Gf`, `Gnf`) never does: a
 *   malformed stored descendant is group content, and a descendant empty only because its computation failed is not.
 * - A starred count (`Rows`, `VisitRows`) counts a row holding only malformed content, and a repeatable descendant
 *   does not multiply it.
 * - A filtered count or `Sum` with no survivor (`Kept`, `KeptSum`) is an ordinary `0`.
 *
 * Own domain (an insurance policy).
 */
class ComputationOpenClausesDiffTest {

    private val base = DocumentModelDraft.named("policy-compute-clauses").locale("en_US")
        .rootGroup("Policy") { p -> p.stringField("Ref") { }
            .numberField("Base") { it.fractionDigits(0) }
            .numberField("Gnf") { it.fractionDigits(0) }.numberField("Gf") { it.fractionDigits(0) }
            .numberField("Ff") { it.fractionDigits(0) }.numberField("Rows") { it.fractionDigits(0) }
            .numberField("Kept") { it.fractionDigits(0) }.numberField("KeptSum") { it.fractionDigits(0) }
            .numberField("VisitRows") { it.fractionDigits(0) }
            .group("Rider") { r -> r.numberField("Limit") { it.fractionDigits(0) }.numberField("Calc") { it.fractionDigits(0) } }
            .group("Claims") { c -> c.repeatable(3).numberField("Amount") { it.fractionDigits(0) }.stringField("Flag") { } }
            .group("Visits") { v -> v.repeatable(3).stringField("Place") { }
                .group("Calls") { c -> c.repeatable(3).numberField("Minutes") { it.fractionDigits(0) } } } }
        .build().toJson()

    private val m = BoundModel.ofJson(base)
    private val rider = m.group("/Policy/Rider")
    private val amounts = m.flatten("/Policy/Claims*/Amount")
    private val flagged = m.str("/Policy/Claims/Flag").eq("Y")

    private val model = admitted(listOf(
        m.computation(m.num("/Policy/Rider/Calc")).name("CalcComp").calc(m.num("/Policy/Base").times(2L))
            .message(Dsl.en("Calc.")).build(),
        m.computation(m.num("/Policy/Gnf")).name("GnfComp").`when`(Dsl.groupNotFilled(rider)).calc(Dsl.number(1L))
            .`when`(rider.filled()).calc(Dsl.number(0L)).message(Dsl.en("Gnf.")).build(),
        m.computation(m.num("/Policy/Gf")).name("GfComp").`when`(rider.filled()).calc(Dsl.number(1L))
            .`when`(Dsl.groupNotFilled(rider)).calc(Dsl.number(0L)).message(Dsl.en("Gf.")).build(),
        m.computation(m.num("/Policy/Ff")).name("FfComp").`when`(m.field("/Policy/Rider/Limit").filled())
            .calc(Dsl.number(1L)).`when`(m.field("/Policy/Rider/Limit").notFilled()).calc(Dsl.number(0L)).message(Dsl.en("Ff.")).build(),
        m.computation(m.num("/Policy/Rows")).name("RowsComp").calc(Dsl.numberOfFilledGroups(m.flattenGroup("/Policy/Claims*")))
            .message(Dsl.en("Rows.")).build(),
        m.computation(m.num("/Policy/Kept")).name("KeptComp").calc(Dsl.numberOfFilledFields(amounts.having(flagged)))
            .message(Dsl.en("Kept.")).build(),
        m.computation(m.num("/Policy/KeptSum")).name("KeptSumComp").calc(Dsl.sum(amounts.having(flagged)))
            .message(Dsl.en("Kept sum.")).build(),
        m.computation(m.num("/Policy/VisitRows")).name("VisitRowsComp")
            .calc(Dsl.numberOfFilledGroups(m.flattenGroup("/Policy/Visits*"))).message(Dsl.en("Visit rows.")).build(),
    ).fold(base) { json, c -> TypedRules.inject(json, c) })

    @Test
    fun eachOpenClauseComputesAsAllThreeEnginesAgree() {
        val seeds = "\"Ref\":\"P\",\"Gnf\":9,\"Gf\":9,\"Ff\":9,\"Rows\":9,\"Kept\":9,\"KeptSum\":9,\"VisitRows\":9"
        val docs = linkedMapOf(
            "rider malformed limit only" to """{"Policy":{$seeds,"Rider":{"Limit":"abc"}}}""",
            "rider only a failed computed" to """{"Policy":{$seeds,"Base":"abc","Rider":{"Calc":4}}}""",
            "rider failed computed plus malformed limit" to """{"Policy":{$seeds,"Base":"abc","Rider":{"Limit":"abc","Calc":4}}}""",
            "rider absent, base malformed" to """{"Policy":{$seeds,"Base":"abc"}}""",
            "claims malformed-only row" to """{"Policy":{$seeds,"Claims":[{"Amount":"abc"}]}}""",
            "claims none flagged" to """{"Policy":{$seeds,"Claims":[{"Amount":5,"Flag":"N"},{"Amount":6}]}}""",
            "one visit with three calls" to
                """{"Policy":{$seeds,"Visits":[{"Calls":[{"Minutes":1},{"Minutes":2},{"Minutes":3}]}]}}""",
        )
        val observed = docs.mapValues { (_, d) -> outcomes(model, d) }
        assertEquals(linkedMapOf(
            "rider malformed limit only" to listOf("/Policy[1]/Ff|CLEARED", "/Policy[1]/Gf|VALUE|1", "/Policy[1]/Gnf|VALUE|0", "/Policy[1]/KeptSum|VALUE|0", "/Policy[1]/Kept|VALUE|0", "/Policy[1]/Rider[1]/Calc|VALUE|0", "/Policy[1]/Rows|VALUE|0", "/Policy[1]/VisitRows|VALUE|0"),
            "rider only a failed computed" to listOf("/Policy[1]/Ff|VALUE|0", "/Policy[1]/Gf|VALUE|0", "/Policy[1]/Gnf|VALUE|1", "/Policy[1]/KeptSum|VALUE|0", "/Policy[1]/Kept|VALUE|0", "/Policy[1]/Rider[1]/Calc|CLEARED", "/Policy[1]/Rows|VALUE|0", "/Policy[1]/VisitRows|VALUE|0"),
            "rider failed computed plus malformed limit" to listOf("/Policy[1]/Ff|CLEARED", "/Policy[1]/Gf|VALUE|1", "/Policy[1]/Gnf|VALUE|0", "/Policy[1]/KeptSum|VALUE|0", "/Policy[1]/Kept|VALUE|0", "/Policy[1]/Rider[1]/Calc|CLEARED", "/Policy[1]/Rows|VALUE|0", "/Policy[1]/VisitRows|VALUE|0"),
            "rider absent, base malformed" to listOf("/Policy[1]/Ff|VALUE|0", "/Policy[1]/Gf|VALUE|0", "/Policy[1]/Gnf|VALUE|1", "/Policy[1]/KeptSum|VALUE|0", "/Policy[1]/Kept|VALUE|0", "/Policy[1]/Rows|VALUE|0", "/Policy[1]/VisitRows|VALUE|0"),
            "claims malformed-only row" to listOf("/Policy[1]/Ff|VALUE|0", "/Policy[1]/Gf|VALUE|1", "/Policy[1]/Gnf|VALUE|0", "/Policy[1]/KeptSum|VALUE|0", "/Policy[1]/Kept|VALUE|0", "/Policy[1]/Rider[1]/Calc|VALUE|0", "/Policy[1]/Rows|VALUE|1", "/Policy[1]/VisitRows|VALUE|0"),
            "claims none flagged" to listOf("/Policy[1]/Ff|VALUE|0", "/Policy[1]/Gf|VALUE|1", "/Policy[1]/Gnf|VALUE|0", "/Policy[1]/KeptSum|VALUE|0", "/Policy[1]/Kept|VALUE|0", "/Policy[1]/Rider[1]/Calc|VALUE|0", "/Policy[1]/Rows|VALUE|2", "/Policy[1]/VisitRows|VALUE|0"),
            "one visit with three calls" to listOf("/Policy[1]/Ff|VALUE|0", "/Policy[1]/Gf|VALUE|1", "/Policy[1]/Gnf|VALUE|0", "/Policy[1]/KeptSum|VALUE|0", "/Policy[1]/Kept|VALUE|0", "/Policy[1]/Rider[1]/Calc|VALUE|0", "/Policy[1]/Rows|VALUE|0", "/Policy[1]/VisitRows|VALUE|1"),
        ), observed)
    }
}
