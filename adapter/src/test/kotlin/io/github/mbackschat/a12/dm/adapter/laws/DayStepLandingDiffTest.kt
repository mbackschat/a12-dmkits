package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.ThreeEngineRuns.admitted
import io.github.mbackschat.a12.dm.adapter.laws.ThreeEngineRuns.outcomes
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Tri-engine differential — the day step `DifferenceInDays` counts with, and a Date source's day shift, land on
 * the expected civil date across a DST transition (INTERPRETER-FINDINGS IF348).
 *
 * Each row's source sits within the offset delta of midnight, the only place a re-fit kept for preserving the raw
 * landing's own date and one kept for landing on the source's date plus `n` disagree. The second reading is the
 * JDK's `GregorianCalendar.add(DAY_OF_MONTH, n)` and both kernel strategies; the first gives `2`, `1`, `2` and `1`
 * on the four discriminating cells (`Loop` twice, `DateLoop`, `ShiftedLoop`). An empty source counts `0`.
 *
 * Own domain (a shift roster in `Europe/Berlin`).
 */
// One instance per class: the models are fields, and this task carries timing floors (see FilteredStarCapacityExtentDiffTest).
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DayStepLandingDiffTest {
    private val base = EditSession.read(DocumentModelDraft.named("shift-day-step").locale("en_US")
        .rootGroup("Shift") { s ->
            s.dateTimeField("Start") { it.format(STAMP) }.dateTimeField("Finish") { it.format(STAMP) }
                .dateField("Rostered") { it.format("yyyy-MM-dd") }
                .numberField("Loop") { it.fractionDigits(0) }.numberField("DateLoop") { it.fractionDigits(0) }
                .numberField("ShiftedLoop") { it.fractionDigits(0) }
        }.build().toJson()).setTimeZone("Europe/Berlin").toJson()

    private val m = BoundModel.ofJson(base)
    private val finish = m.dateTime("/Shift/Finish")
    private val rostered = m.date("/Shift/Rostered")

    private fun count(target: String, calc: NumberExpr) =
        m.computation(m.num("/Shift/$target")).name("${target}Comp").calc(calc).message(Dsl.en("counted")).build()

    private val model = admitted(listOf(
        count("Loop", Dsl.differenceInDays(m.dateTime("/Shift/Start"), finish)),
        count("DateLoop", Dsl.differenceInDays(rostered, finish)),
        count("ShiftedLoop", Dsl.differenceInDays(Dsl.addDays(rostered, 2), finish)),
    ).fold(base) { json, c -> TypedRules.inject(json, c) })

    @Test
    fun aDayStepLandsOnTheExpectedCivilDateAcrossATransition() {
        val rows = linkedMapOf(
            shift(""""Start":"2024-10-26T00:30:00","Finish":"2024-10-28T00:00:00"""") to counts(1, 0, 0),
            shift(""""Start":"2024-03-29T23:30:00","Finish":"2024-03-31T23:45:00"""") to counts(2, 0, 0),
            shift(""""Rostered":"2024-10-26","Finish":"2024-10-27T23:30:00"""") to counts(0, 1, 0),
            shift(""""Rostered":"2024-10-26","Finish":"2024-10-28T23:30:00"""") to counts(0, 2, 0),
        )
        assertEquals(rows.toList(), rows.keys.map { it to outcomes(model, it) })
    }

    /** A shift carrying [cells], every count seeded so each computation reports its value. */
    private fun shift(cells: String) = """{"Shift":{"Loop":99,"DateLoop":99,"ShiftedLoop":99,$cells}}"""

    private fun counts(loop: Int, dateLoop: Int, shiftedLoop: Int) = listOf(
        "/Shift[1]/DateLoop|VALUE|$dateLoop", "/Shift[1]/Loop|VALUE|$loop", "/Shift[1]/ShiftedLoop|VALUE|$shiftedLoop")

    private companion object {
        const val STAMP = "yyyy-MM-dd'T'HH:mm:ss"
    }
}
