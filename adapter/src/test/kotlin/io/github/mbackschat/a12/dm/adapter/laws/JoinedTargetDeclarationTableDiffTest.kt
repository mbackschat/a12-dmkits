package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.KernelConsistencyOracle
import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.core.ReferenceResolvers
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Several computations writing one KEY-JOINED target, against the Kernel (a12-kernel-lean SPEC-2026-10-24-09 and
 * its `k22-cross-declaration` capture; the capacity row is SPEC-2026-10-24-08's). The declarations form one first-match table over the join, and a
 * declaration that reads no partner field acts by its POSITION: leading the table, it clears the target at every
 * row whatever any guard says; behind a joined lead, it ends the scan with nothing stored where it is selected and
 * falls through where it is not. Orders and Stock join by `Sku`; order B asks for two units, so every declaration
 * guarded by `Qty == 1` falls through there, and no row satisfies a `Qty >= 5` guard.
 *
 * Two rival accounts. "A partnerless declaration is an ordinary row of the table" predicts a value at B for
 * `Allocated` and `Fallback`, and at every row for `Buffered`. "It clears wherever the scan reaches it, whatever
 * its guard" (the peer's clause) predicts `Skipped` cleared at B, where its partnerless middle declaration's guard
 * is false and the joined declaration after it computes instead. `GuardedByStock` and `CommonByStock` read the
 * partner only in a guard, which already makes a declaration joined. The controls `Pooled` (two joined
 * declarations), `Mixed` (a partnerless alternative INSIDE a joined declaration) and `Plain` (a target nobody
 * joins) compute under every account. `LeadFalse`, `LeadFalseTwice` and `LeadThenTwo` put a partnerless
 * declaration whose guard holds at no row in front, where a guard-respecting account computes the joined
 * declarations behind it. `BufferedTotal` reads the cleared cells.
 *
 * Own domain (an order-fulfilment sheet: orders against stock).
 */
class JoinedTargetDeclarationTableDiffTest {

    private val skeleton = DocumentModelDraft.named("order-allocation").locale("en_US")
        .rootGroup("Fulfilment") { f -> f
            .numberField("BufferedTotal") { it.fractionDigits(0) }
            .group("Orders") { o -> o.repeatable(3).indexField("Sku")
                .stringField("Sku") { }
                .numberField("Qty") { it.fractionDigits(0) }
                .numberField("Allocated") { it.fractionDigits(0) }
                .numberField("Buffered") { it.fractionDigits(0) }
                .numberField("Fallback") { it.fractionDigits(0) }
                .numberField("Skipped") { it.fractionDigits(0) }
                .numberField("Pooled") { it.fractionDigits(0) }
                .numberField("Mixed") { it.fractionDigits(0) }
                .numberField("Plain") { it.fractionDigits(0) }
                .numberField("GuardedByStock") { it.fractionDigits(0) }
                .numberField("CommonByStock") { it.fractionDigits(0) }
                .numberField("LeadFalse") { it.fractionDigits(0) }
                .numberField("LeadFalseTwice") { it.fractionDigits(0) }
                .numberField("LeadThenTwo") { it.fractionDigits(0) } }
            .group("Stock") { s -> s.repeatable(4).indexField("Sku")
                .stringField("Sku") { }
                .numberField("OnHand") { it.fractionDigits(0) } } }
        .build().toJson()

    private val bound = BoundModel.ofJson(skeleton)
    private val qty = bound.num("/Fulfilment/Orders/Qty")
    private val onHand = bound.num("/Fulfilment/Stock/OnHand")
    private val single = qty.eq(1)

    private fun computing(target: String, name: String) =
        bound.computation(bound.num("/Fulfilment/Orders/$target")).name(name).message(Dsl.en("Allocation computed."))

    private val model = bound.addAll(
        listOf(),
        listOf(
            computing("Allocated", "AllocatedFromStock").`when`(single).calc(onHand.asNumber()).build(),
            computing("Allocated", "AllocatedFromOrder").`when`(qty.ge(1)).calc(qty.plus(100)).build(),
            computing("Buffered", "BufferedFromOrder").`when`(qty.ge(1)).calc(qty.plus(100)).build(),
            computing("Buffered", "BufferedFromStock").`when`(single).calc(onHand.asNumber()).build(),
            computing("Fallback", "FallbackFromStock").`when`(single).calc(onHand.asNumber()).build(),
            computing("Fallback", "FallbackConstant").calc(Dsl.number(7L)).build(),
            computing("Skipped", "SkippedFromStock").`when`(single).calc(onHand.asNumber()).build(),
            computing("Skipped", "SkippedLargeOrder").`when`(qty.ge(5)).calc(qty.plus(100)).build(),
            computing("Skipped", "SkippedReserve").`when`(qty.ge(1)).calc(onHand.plus(1000)).build(),
            computing("Pooled", "PooledFromStock").`when`(single).calc(onHand.asNumber()).build(),
            computing("Pooled", "PooledReserve").`when`(qty.ge(1)).calc(onHand.plus(1000)).build(),
            computing("Mixed", "MixedTable").`when`(single).calc(onHand.asNumber()).`when`(qty.ge(2)).calc(qty.plus(100)).build(),
            computing("Plain", "PlainSingle").`when`(single).calc(qty.asNumber()).build(),
            computing("Plain", "PlainOrder").`when`(qty.ge(1)).calc(qty.plus(100)).build(),
            computing("GuardedByStock", "GuardedFromStock").`when`(single).calc(onHand.asNumber()).build(),
            computing("GuardedByStock", "GuardedOrder").`when`(onHand.ge(1)).calc(qty.plus(100)).build(),
            computing("CommonByStock", "CommonFromStock").`when`(single).calc(onHand.asNumber()).build(),
            computing("CommonByStock", "CommonOrder").commonPrecondition(onHand.filled()).calc(qty.plus(100)).build(),
            computing("LeadFalse", "LeadFalseLarge").`when`(qty.ge(5)).calc(qty.plus(100)).build(),
            computing("LeadFalse", "LeadFalseReserve").`when`(qty.ge(1)).calc(onHand.plus(1000)).build(),
            computing("LeadFalseTwice", "TwiceLarge").`when`(qty.ge(5)).calc(qty.plus(100)).build(),
            computing("LeadFalseTwice", "TwiceHuge").`when`(qty.ge(6)).calc(qty.plus(200)).build(),
            computing("LeadFalseTwice", "TwiceReserve").`when`(qty.ge(1)).calc(onHand.plus(1000)).build(),
            computing("LeadThenTwo", "LeadThenLarge").`when`(qty.ge(5)).calc(qty.plus(100)).build(),
            computing("LeadThenTwo", "LeadThenStock").`when`(single).calc(onHand.asNumber()).build(),
            computing("LeadThenTwo", "LeadThenReserve").`when`(qty.ge(1)).calc(onHand.plus(1000)).build(),
            bound.computation(bound.num("/Fulfilment/BufferedTotal")).name("BufferedTotalComputation")
                .calc(Dsl.sum(bound.flatten("/Fulfilment/Orders*/Buffered")).plus(1))
                .message(Dsl.en("Total computed.")).build(),
        ),
    ).toJson()

    // Every computed cell is seeded, so a cleared one is reported rather than left unmentioned.
    private val targets = listOf("Allocated", "Buffered", "Fallback", "Skipped", "Pooled", "Mixed", "Plain",
        "GuardedByStock", "CommonByStock", "LeadFalse", "LeadFalseTwice", "LeadThenTwo")
    private val seeds = targets.joinToString("") { ""","$it":999""" }

    private val sheet = """{"Fulfilment":{"BufferedTotal":999,
        "Orders":[{"Sku":"A","Qty":1$seeds},{"Sku":"B","Qty":2$seeds},{"Sku":"C","Qty":1$seeds}],
        "Stock":[{"Sku":"B","OnHand":5},{"Sku":"A","OnHand":3},{"Sku":"D","OnHand":9}]}}"""

    // A fourth order past the group's capacity of three, its key stocked so the join would pair it.
    private val overCapacity = """{"Fulfilment":{"BufferedTotal":999,
        "Orders":[{"Sku":"A","Qty":1$seeds},{"Sku":"B","Qty":2$seeds},{"Sku":"C","Qty":1$seeds},{"Sku":"E","Qty":1$seeds}],
        "Stock":[{"Sku":"E","OnHand":4},{"Sku":"A","OnHand":3}]}}"""

    /** Several declarations per target in ONE group: A12 admits the carrier, so its runtime answers are a legal model's. */
    @Test
    fun a12AndTheCheckerAdmitTheCarrier() {
        assertEquals(emptyList<String>(), KernelConsistencyOracle().checkConsistency(model, ReferenceResolvers.selfContained())
            .filter { it.severity().name == "ERROR" }.map { it.message() })
        assertEquals(emptyList<String>(), check(ModelSource(model)).findings.filter { it.justifiesRejection }.map { it.code })
    }

    @Test
    fun allThreeEnginesAgree() {
        DocumentDifferential.assertAgree(model,
            mapOf("orders A, B and an unstocked C" to sheet, "a stocked fourth order past capacity" to overCapacity))
    }

    /** What the dynamic Kernel stores, so agreement cannot pass on a table both engines got wrong alike. */
    @Test
    fun aPartnerlessDeclarationActsByItsPositionInTheJoinedTargetsTable() {
        val outcomes = DocumentDifferential.kernel(model, sheet, dynamic = true).outcomes
        fun row(field: String) = listOf("Orders/$field", "Orders[2]/$field", "Orders[3]/$field")
            .map { outcomes[it] ?: "absent" }
        assertEquals(mapOf(
            "Allocated" to listOf("VALUE|3", "CLEARED", "VALUE|0"),
            "Buffered" to listOf("CLEARED", "CLEARED", "CLEARED"),
            "Fallback" to listOf("VALUE|3", "CLEARED", "VALUE|0"),
            "Skipped" to listOf("VALUE|3", "VALUE|1005", "VALUE|0"),
            "GuardedByStock" to listOf("VALUE|3", "VALUE|102", "VALUE|0"),
            "CommonByStock" to listOf("VALUE|3", "VALUE|102", "VALUE|0"),
            "Pooled" to listOf("VALUE|3", "VALUE|1005", "VALUE|0"),
            "Mixed" to listOf("VALUE|3", "VALUE|102", "VALUE|0"),
            "Plain" to listOf("VALUE|1", "VALUE|102", "VALUE|1"),
            "LeadFalse" to listOf("CLEARED", "CLEARED", "CLEARED"),
            "LeadFalseTwice" to listOf("CLEARED", "CLEARED", "CLEARED"),
            "LeadThenTwo" to listOf("CLEARED", "CLEARED", "CLEARED"),
        ), listOf("Allocated", "Buffered", "Fallback", "Skipped", "GuardedByStock", "CommonByStock", "Pooled",
            "Mixed", "Plain", "LeadFalse", "LeadFalseTwice", "LeadThenTwo").associateWith(::row))
        assertEquals("VALUE|1", outcomes["BufferedTotal"])
    }

    /** The row past capacity clears in every table, joined or not, while the rows within it still compute. */
    @Test
    fun aTargetRowPastCapacityClearsInEveryTable() {
        val outcomes = DocumentDifferential.kernel(model, overCapacity, dynamic = true).outcomes
        assertEquals(targets.associateWith { "CLEARED" }, targets.associateWith { outcomes["Orders[4]/$it"] })
        assertEquals("VALUE|3", outcomes["Orders/Allocated"])
    }
}
