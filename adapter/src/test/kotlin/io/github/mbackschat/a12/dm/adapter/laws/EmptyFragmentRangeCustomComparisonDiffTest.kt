package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.ThreeEngineRuns.admitted
import io.github.mbackschat.a12.dm.adapter.laws.ThreeEngineRuns.fired
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Whether a comparison over an empty DateFragment, DateRange or Custom field is evaluated, the acceptance of
 * a12-kernel-lean SPEC-2026-10-26-30 ([KERNEL-FINDINGS KF635]). Neither `==` nor `!=` fires while either side is
 * empty, and `!=` fires once every DateFragment and DateRange side is filled, so the silence is the empty read
 * and not a dead rule. A filled Custom field needs a registered validator this harness does not carry, so its
 * filled control is `EqualityKindDiffTest`'s Custom case. Every row is three-engine agreed.
 *
 * Own domain (a holiday booking).
 */
class EmptyFragmentRangeCustomComparisonDiffTest {

    private val base = DocumentModelDraft.named("booking-empty-kinds").locale("en_US")
        .rootGroup("Booking") { b -> b.stringField("Guard") { }
            .dateFragmentField("MonthLeft") { it.fragmentFormat("yyyy-MM") }
            .dateFragmentField("MonthRight") { it.fragmentFormat("yyyy-MM") }
            .dateRangeField("StayLeft") { it.format("dd.MM.yyyy").separator("-") }
            .dateRangeField("StayRight") { it.format("dd.MM.yyyy").separator("-") }
            .customField("Voucher") { it.type("VoucherCode") } }
        .build().toJson()
    private val m = BoundModel.ofJson(base)

    private val model: String = run {
        val guard = m.str("/Booking/Guard").filled()
        val rules: List<Pair<String, Condition>> = listOf(
            "MonthEq" to m.dateFragment("/Booking/MonthLeft").eq(m.dateFragment("/Booking/MonthRight")),
            "MonthNe" to m.dateFragment("/Booking/MonthLeft").ne(m.dateFragment("/Booking/MonthRight")),
            "StayEq" to m.dateRange("/Booking/StayLeft").eq(m.dateRange("/Booking/StayRight")),
            "StayNe" to m.dateRange("/Booking/StayLeft").ne(m.dateRange("/Booking/StayRight")),
            "VoucherEq" to m.custom("/Booking/Voucher").eq("x"),
            "VoucherNe" to m.custom("/Booking/Voucher").ne("x"),
        )
        admitted(rules.fold(base) { json, (code, condition) ->
            RuntimeLaws.withRule(json, "/Booking", "${code}Rule", "../Guard", code,
                RuntimeLaws.render(guard.and(condition)))
        })
    }

    @Test
    fun aComparisonWithAnEmptySideIsNotEvaluated() {
        val observed = linkedMapOf(
            "all empty" to """{"Booking":{"Guard":"g"}}""",
            "left sides filled" to """{"Booking":{"Guard":"g","MonthLeft":"2024-06","StayLeft":"01.06.2024-30.06.2024"}}""",
            "every date side filled and different" to """{"Booking":{"Guard":"g","MonthLeft":"2024-06","MonthRight":"2024-07",""" +
                """"StayLeft":"01.06.2024-30.06.2024","StayRight":"01.07.2024-31.07.2024"}}""",
        ).mapValues { (_, document) -> fired(model, document) }
        assertEquals(linkedMapOf(
            "all empty" to emptyList(),
            "left sides filled" to emptyList(),
            "every date side filled and different" to listOf("MonthNe", "StayNe"),
        ), observed)
    }
}
