package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.ThreeEngineRuns.admitted
import io.github.mbackschat.a12.dm.adapter.laws.ThreeEngineRuns.outcomes
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * What a dependent computation reads from a joined target instance that A12 skipped, the acceptance of
 * a12-kernel-lean SPEC-2026-10-26-12 ([KERNEL-FINDINGS KF630]). A defective key in the root-level `Refs` shares no
 * repeatable level with the target, so every `Total` is skipped, and its dependents `Doubled` and `Grand` are
 * cleared rather than computing `0` from an empty read. A defective key in the per-line `Tags` skips only that
 * line's instance. Every row is three-engine agreed.
 *
 * Own domain (an order's line slots).
 */
class JoinedInstanceSkipDependentDiffTest {

    private val base = DocumentModelDraft.named("order-joined-skip").locale("en_US")
        .rootGroup("Order") { o -> o.numberField("Grand") { it.fractionDigits(0) }
            .group("Lines") { l -> l.repeatable(5).numberField("LineNo") { it.fractionDigits(0) }
                .group("Slots") { s -> s.repeatable(5).indexField("Key")
                    .numberField("Key") { it.fractionDigits(0) }.numberField("Total") { it.fractionDigits(0) }
                    .numberField("Doubled") { it.fractionDigits(0) }.numberField("TagTotal") { it.fractionDigits(0) } }
                .group("Tags") { t -> t.repeatable(5).indexField("Key")
                    .numberField("Key") { it.fractionDigits(0) }.numberField("Bonus") { it.fractionDigits(0) } } }
            .group("Refs") { r -> r.repeatable(5).indexField("Key")
                .numberField("Key") { it.fractionDigits(0) }.numberField("Amount") { it.fractionDigits(0) } } }
        .build().toJson()

    private val model = admitted(listOf(
        listOf("/Order/Lines/Slots", "TotalComp", "../Total", "[../../Refs/Amount]"),
        listOf("/Order/Lines/Slots", "DoubledComp", "../Doubled", "[/Order/Lines/Slots/Total] * 2"),
        listOf("/Order/Lines/Slots", "TagComp", "../TagTotal", "[/Order/Lines/Tags/Bonus]"),
        listOf("/Order", "GrandComp", "../Grand", "Sum(Lines*/Slots*/Total)"),
    ).fold(base) { json, (group, name, target, op) -> RuntimeLaws.withComputation(json, group, name, target, op) })

    private fun doc(refs: String, tags1: String) =
        """{"Order":{"Grand":99,"Refs":[$refs],"Lines":[""" +
            """{"LineNo":1,"Slots":[{"Key":1,"Total":7,"Doubled":7,"TagTotal":7}],"Tags":[$tags1]},""" +
            """{"LineNo":2,"Slots":[{"Key":2,"Total":7,"Doubled":7,"TagTotal":7}],"Tags":[{"Key":2,"Bonus":20}]}]}}"""

    @Test
    fun aSkippedInstancePoisonsItsDependents() {
        val docs = linkedMapOf(
            "control" to doc("""{"Key":1,"Amount":100},{"Key":2,"Amount":200}""", """{"Key":1,"Bonus":10}"""),
            "off-path duplicate key" to doc("""{"Key":1,"Amount":100},{"Key":1,"Amount":150},{"Key":2,"Amount":200}""",
                """{"Key":1,"Bonus":10}"""),
            "off-path malformed key" to doc("""{"Key":"abc","Amount":100},{"Key":2,"Amount":200}""",
                """{"Key":1,"Bonus":10}"""),
            "partially shared duplicate key" to doc("""{"Key":1,"Amount":100},{"Key":2,"Amount":200}""",
                """{"Key":1,"Bonus":10},{"Key":1,"Bonus":11}"""),
        )
        val observed = docs.mapValues { (_, d) -> outcomes(model, d) }
        val everyTotalSkipped = listOf("/Order[1]/Grand|CLEARED", "/Order[1]/Lines[1]/Slots[1]/Doubled|CLEARED",
            "/Order[1]/Lines[1]/Slots[1]/TagTotal|VALUE|10", "/Order[1]/Lines[1]/Slots[1]/Total|CLEARED",
            "/Order[1]/Lines[2]/Slots[1]/Doubled|CLEARED", "/Order[1]/Lines[2]/Slots[1]/TagTotal|VALUE|20",
            "/Order[1]/Lines[2]/Slots[1]/Total|CLEARED")
        assertEquals(linkedMapOf(
            "control" to listOf("/Order[1]/Grand|VALUE|300", "/Order[1]/Lines[1]/Slots[1]/Doubled|VALUE|200",
                "/Order[1]/Lines[1]/Slots[1]/TagTotal|VALUE|10", "/Order[1]/Lines[1]/Slots[1]/Total|VALUE|100",
                "/Order[1]/Lines[2]/Slots[1]/Doubled|VALUE|400", "/Order[1]/Lines[2]/Slots[1]/TagTotal|VALUE|20",
                "/Order[1]/Lines[2]/Slots[1]/Total|VALUE|200"),
            "off-path duplicate key" to everyTotalSkipped,
            "off-path malformed key" to everyTotalSkipped,
            "partially shared duplicate key" to listOf("/Order[1]/Grand|VALUE|300",
                "/Order[1]/Lines[1]/Slots[1]/Doubled|VALUE|200", "/Order[1]/Lines[1]/Slots[1]/TagTotal|CLEARED",
                "/Order[1]/Lines[1]/Slots[1]/Total|VALUE|100", "/Order[1]/Lines[2]/Slots[1]/Doubled|VALUE|400",
                "/Order[1]/Lines[2]/Slots[1]/TagTotal|VALUE|20", "/Order[1]/Lines[2]/Slots[1]/Total|VALUE|200"),
        ), observed)
    }

    /**
     * The target's OWN key, a12-kernel-lean SPEC-2026-10-28-04. A key two slots of the first line share skips both
     * of that line's instances, so their `Total`, its dependent `Doubled`, the `TagTotal` the line's tags feed and
     * `Grand` are cleared, while the second line computes. A key no `Refs` row carries is no skip: its Number copies
     * compute `0`, and so does their dependent.
     */
    @Test
    fun theTargetsOwnKeySkipsOnlyWhenDuplicated() {
        val seeded = """"Total":7,"Doubled":7,"TagTotal":7"""
        val refs = """"Refs":[{"Key":1,"Amount":100},{"Key":2,"Amount":200}]"""
        val observed = linkedMapOf(
            "own duplicate key" to """{"Order":{"Grand":99,$refs,"Lines":[""" +
                """{"LineNo":1,"Slots":[{"Key":1,$seeded},{"Key":1,$seeded}],"Tags":[{"Key":1,"Bonus":10}]},""" +
                """{"LineNo":2,"Slots":[{"Key":2,$seeded}],"Tags":[{"Key":2,"Bonus":20}]}]}}""",
            "own key unmatched" to """{"Order":{"Grand":99,$refs,"Lines":[""" +
                """{"LineNo":1,"Slots":[{"Key":1,$seeded}],"Tags":[{"Key":1,"Bonus":10}]},""" +
                """{"LineNo":2,"Slots":[{"Key":3,$seeded}],"Tags":[{"Key":2,"Bonus":20}]}]}}""",
        ).mapValues { (_, d) -> outcomes(model, d) }
        fun cleared(slot: Int) = listOf("Doubled", "TagTotal", "Total").map { "/Order[1]/Lines[1]/Slots[$slot]/$it|CLEARED" }
        assertEquals(linkedMapOf(
            "own duplicate key" to listOf("/Order[1]/Grand|CLEARED") + cleared(1) + cleared(2) + listOf(
                "/Order[1]/Lines[2]/Slots[1]/Doubled|VALUE|400", "/Order[1]/Lines[2]/Slots[1]/TagTotal|VALUE|20",
                "/Order[1]/Lines[2]/Slots[1]/Total|VALUE|200"),
            "own key unmatched" to listOf("/Order[1]/Grand|VALUE|100", "/Order[1]/Lines[1]/Slots[1]/Doubled|VALUE|200",
                "/Order[1]/Lines[1]/Slots[1]/TagTotal|VALUE|10", "/Order[1]/Lines[1]/Slots[1]/Total|VALUE|100",
                "/Order[1]/Lines[2]/Slots[1]/Doubled|VALUE|0", "/Order[1]/Lines[2]/Slots[1]/TagTotal|VALUE|0",
                "/Order[1]/Lines[2]/Slots[1]/Total|VALUE|0"),
        ), observed)
    }
}
