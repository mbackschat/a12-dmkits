package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.ThreeEngineRuns.admitted
import io.github.mbackschat.a12.dm.adapter.laws.ThreeEngineRuns.fired
import io.github.mbackschat.a12.dm.adapter.laws.ThreeEngineRuns.outcomes
import io.github.mbackschat.a12.dm.modelkit.DatePrecision
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.DateExpr
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.FirstFilledOperand
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Tri-engine differential — the value `FirstFilledValue` selects from a well-formed partial Date.
 *
 * A12 refuses a bracket read of a partial Date (`MVK_INVALID_DATE_TYPE`, KERNEL-FINDINGS KF473), so a selection
 * is how one reaches a comparison or a computation. `FirstFilledValue` stopping at a well-formed partial yields
 * its earliest day when the year is known (`2024-06-00` → `2024-06-01`, `2024-00-00` → `2024-01-01`); an unknown
 * year stays unknown, and a malformed partial stops the walk as unknown. Every arm the walk can stop on is asked:
 * a direct field, a starred list, a bare group's descendant field and a keyed field, on the validation and the
 * computation side.
 *
 * Own domain (an insurance claim with partially known dates).
 */
// One instance per class: the models are fields, and this task carries timing floors (see FilteredStarCapacityExtentDiffTest).
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FirstFilledValuePartialDateDiffTest {
    private val base = DocumentModelDraft.named("claim-partial-first-filled").locale("en_US")
        .rootGroup("Claim") { c ->
            c.dateField("Day") { it.format(ISO).datePrecision(DatePrecision.DAY_OPTIONAL) }
                .dateField("Month") { it.format(ISO).datePrecision(DatePrecision.MONTH_OPTIONAL) }
                .dateField("Year") { it.format(ISO).datePrecision(DatePrecision.YEAR_OPTIONAL) }
                .dateField("Reported") { it.format(ISO) }.dateField("Closed") { it.format(ISO) }
                .dateField("CopyDay") { it.format(ISO) }.dateField("CopyVisit") { it.format(ISO) }
                .dateField("CopyYear") { it.format(ISO) }.dateField("CopyKey") { it.format(ISO) }
                .group("Window") { w -> w.dateField("Opened") { it.format(ISO).datePrecision(DatePrecision.DAY_OPTIONAL) } }
                .group("Visits") { v ->
                    v.repeatable(3).indexField("Key").stringField("Key") { }
                        .dateField("On") { it.format(ISO).datePrecision(DatePrecision.DAY_OPTIONAL) }
                }
        }.build().toJson()

    private val m = BoundModel.ofJson(base)
    private val reported = m.field("/Claim/Reported")
    private val closed = m.date("/Claim/Closed")

    private fun first(operand: String) = Dsl.firstFilledDate(m.field(operand), reported)

    private fun rule(code: String, condition: Condition): Rule =
        m.rule(m.group("/Claim")).name("${code}Rule").errorField(closed.field()).`when`(condition)
            .code(code).severity(Severity.ERROR).message(Dsl.en(code)).build()

    private val rules = admitted(listOf(
        rule("DAY_EQ", first("/Claim/Day").eq(closed)),
        rule("DAY_LT", first("/Claim/Day").before(closed)),
        rule("MONTH_EQ", first("/Claim/Month").eq(closed)),
        rule("YEAR_EQ", first("/Claim/Year").eq(closed)),
        rule("STAR_EQ", Dsl.firstFilledDate(m.flatten("/Claim/Visits*/On"), reported).eq(closed)),
        rule("GROUP_EQ", Dsl.firstFilledDate(m.firstFilledGroup("/Claim/Window"), FirstFilledOperand.of(reported))
            .eq(closed)),
        rule("KEY_EQ", Dsl.firstFilledDate(FirstFilledOperand.of(m.field("/Claim/Visits/On")).forIndex("a"),
            FirstFilledOperand.of(reported)).eq(closed)),
        rule("KEY_FILLED", m.field("/Claim/Visits/On").semanticIndex("a").filled().and(closed.filled())),
    ).fold(base) { json, r -> TypedRules.inject(json, r) })

    @Test
    fun aComparisonSeesThePartialDateAsItsFirstDay() {
        val rows = linkedMapOf(
            doc(""""Day":"2024-06-00","Closed":"2024-06-01"""") to listOf("DAY_EQ"),
            doc(""""Day":"2024-06-00","Closed":"2024-06-30"""") to listOf("DAY_LT"),
            doc(""""Month":"2024-00-00","Closed":"2024-01-01"""") to listOf("MONTH_EQ"),
            doc(""""Month":"2024-03-00","Closed":"2024-03-01"""") to listOf("MONTH_EQ"),
            doc(""""Year":"0000-00-00","Closed":"2024-06-15"""") to
                listOf("DAY_EQ", "GROUP_EQ", "KEY_EQ", "MONTH_EQ", "STAR_EQ"),
            doc(""""Visits":[{"Key":"a","On":"2024-06-00"}],"Closed":"2024-06-01"""") to listOf("KEY_EQ", "KEY_FILLED", "STAR_EQ"),
            doc(""""Window":{"Opened":"2024-06-00"},"Closed":"2024-06-01"""") to listOf("GROUP_EQ"),
            doc(""""Day":"2024-00-00","Closed":"2024-06-15"""") to
                listOf("GROUP_EQ", "KEY_EQ", "MONTH_EQ", "STAR_EQ", "YEAR_EQ", "datumFalsch"),
            doc(""""Day":"1583-10-00","Closed":"2024-06-15"""") to
                listOf("GROUP_EQ", "KEY_EQ", "MONTH_EQ", "STAR_EQ", "YEAR_EQ", "datumFalsch"),
        )
        assertEquals(rows.toList(), rows.keys.map { it to fired(rules, it) })
    }

    private fun copy(target: String, calc: DateExpr) =
        m.computation(m.date(target)).name("${target.substringAfterLast('/')}Comp").calc(calc.expression())
            .message(Dsl.en("copied")).build()

    private val computations = admitted(listOf(
        copy("/Claim/CopyDay", first("/Claim/Day")),
        copy("/Claim/CopyVisit", Dsl.firstFilledDate(m.flatten("/Claim/Visits*/On"), reported)),
        copy("/Claim/CopyYear", first("/Claim/Year")),
        copy("/Claim/CopyKey", Dsl.firstFilledDate(FirstFilledOperand.of(m.field("/Claim/Visits/On")).forIndex("a"),
            FirstFilledOperand.of(reported))),
    ).fold(base) { json, c -> TypedRules.inject(json, c) })

    @Test
    fun aComputationStoresThePartialDateAsItsFirstDay() {
        val rows = linkedMapOf(
            seeded(""""Day":"2024-06-00"""") to listOf("/Claim[1]/CopyDay|VALUE|2024-06-01",
                "/Claim[1]/CopyKey|VALUE|2024-06-15", "/Claim[1]/CopyVisit|VALUE|2024-06-15",
                "/Claim[1]/CopyYear|VALUE|2024-06-15"),
            seeded(""""Day":"2024-00-00","Year":"0000-00-00","Visits":[{"Key":"a","On":"2024-06-00"}]""") to
                listOf("/Claim[1]/CopyDay|CLEARED", "/Claim[1]/CopyKey|VALUE|2024-06-01",
                    "/Claim[1]/CopyVisit|VALUE|2024-06-01", "/Claim[1]/CopyYear|CLEARED"),
        )
        assertEquals(rows.toList(), rows.keys.map { it to outcomes(computations, it) })
    }

    /** One claim reported on 2024-06-15, carrying [cells]. */
    private fun doc(cells: String) = """{"Claim":{"Reported":"2024-06-15",$cells}}"""

    /** [doc] with every computed target seeded, so a computation that clears one reports it. */
    private fun seeded(cells: String) =
        doc(""""CopyDay":"2000-01-01","CopyVisit":"2000-01-01","CopyYear":"2000-01-01","CopyKey":"2000-01-01",$cells""")

    private companion object {
        const val ISO = "yyyy-MM-dd"
    }
}
