package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.DateExpr
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The year a stored yearless `MM-dd` field's day shift starts from, and how the shifted value then compares
 * (KERNEL-FINDINGS KF93, a12-kernel-lean SPEC-2026-10-30-04).
 *
 * With no Base Year the shift starts from 2000 on the hybrid calendar: `02-28` plus 1, 36890 and 73049 days and minus
 * 36160 lands on `02-29`, `02-28`, `02-28` and `02-27`, which a leap year divisible by 400 alone gives, and the two
 * shifts back past 1582 land on the Julian labels 2000 reaches, where the proleptic Gregorian count gives others. The
 * same model under Base Year 2001, differing in that member alone, is the control.
 *
 * The shifted value keeps the year it reaches while the stored value it meets is read in the anchor year, so
 * `AddDays(12-31, 1)` is not the stored `01-01` although its month and day are. Two yearless constants complete in one
 * leap year with no Base Year: `DifferenceInDays("31.12.", "01.01.")` is `-365`, where a completion straddling two
 * years would make it 1.
 *
 * Every document compares dynamic Groovy, static Java and the interpreter; the outcomes are pinned from dynamic
 * Groovy. Own domain (an insurance policy's renewal day).
 */
class YearlessShiftAnchorDiffTest {

    private fun model(baseYear: Int?): String {
        val draft = DocumentModelDraft.named("renewal-shift").locale("en_US")
        if (baseYear != null) draft.baseYear(baseYear)
        val session = EditSession.read(draft.rootGroup("Renewal") { r ->
            (listOf("Day", "Next") + SHIFTS.keys + CONSTANT_SHIFTS.keys).forEach { r.dateField(it) { d -> d.format("MM-dd") } }
            DIFFERENCES.forEach { r.numberField(it) { n -> n.fractionDigits(0) } }
            r
        }.build().toJson())
        val renewal = session.group("/Renewal")
        val day = renewal.date("Day")
        fun shift(target: String, calc: DateExpr) = renewal.addComputation(renewal.date(target)) {
            it.name("Compute$target").calc(calc).message(Dsl.en("Check the renewal day.")).build()
        }
        fun difference(target: String, calc: NumberExpr) = renewal.addComputation(renewal.num(target)) {
            it.name("Compute$target").calc(calc).message(Dsl.en("Check the day count.")).build()
        }
        SHIFTS.forEach { (target, days) -> shift(target, Dsl.addDays(day, days)) }
        CONSTANT_SHIFTS.forEach { (target, days) -> shift(target, Dsl.addDays(Dsl.dateValue("15.06."), days)) }
        difference("YearEnd", Dsl.differenceInDays(Dsl.dateValue("31.12."), Dsl.dateValue("01.01.")))
        difference("LeapSpan", Dsl.differenceInDays(Dsl.dateValue("28.02."), Dsl.dateValue("01.03.")))
        difference("FromNewYear", Dsl.differenceInDays(Dsl.dateValue("01.01."), day))
        renewal.addRule {
            it.name("NextDay").code("NEXT_DAY").errorField(renewal.date("Next"))
                .`when`(Dsl.addDays(day, 1).eq(renewal.date("Next"))).message(Dsl.en("The next day follows.")).build()
        }
        return session.toJson()
    }

    private val unanchored = model(null)
    private val anchored2001 = model(2001)

    private fun renewal(day: String, next: String? = null) =
        """{"Renewal":{"Day":"$day"${next?.let { ",\"Next\":\"$it\"" } ?: ""}}}"""

    private val documents = linkedMapOf(
        "leap day next" to renewal("02-28", "02-29"),
        "march next" to renewal("02-28", "03-01"),
        "june" to renewal("06-15", "06-16"),
        "december" to renewal("12-30", "12-31"),
        "year end" to renewal("12-31", "01-01"),
        "march first" to renewal("03-01"),
    )

    /** Every shift, difference and comparison is admitted with and without a Base Year, by A12 and the portable checker. */
    @Test
    fun a12AndThePortableCheckerAdmitBothModels() {
        for (model in listOf(unanchored, anchored2001)) {
            assertEquals(emptyList<String>(), MessageParameterProbes.kernel(model))
            assertEquals(emptyList<String>(), rejectingCodes(model))
        }
    }

    @Test
    fun allThreeEnginesAgreeOnEveryDocument() {
        DocumentDifferential.assertAgree(unanchored, documents)
        DocumentDifferential.assertAgree(anchored2001, documents.filterKeys { it != "leap day next" })
    }

    /** No Base Year: the shifts start from 2000, the only year of 1600 to 8999 giving all of these on the hybrid calendar. */
    @Test
    fun withoutABaseYearTheShiftStartsFromTheYear2000() {
        assertEquals(shifts("02-29", "02-28", "02-28", "02-27"), outcomes(unanchored, "march next", SHIFTS.keys.take(4)))
        assertEquals(shifts("06-16", "06-16", "06-16", "06-15", "05-13", "11-06"), outcomes(unanchored, "june", SHIFTS.keys))
        assertEquals(mapOf("Back160000" to "11-28", "Back200000" to "05-24").asStored(),
            outcomes(unanchored, "year end", SHIFTS.keys.drop(4)))
        assertEquals(mapOf("Back160000" to "11-27", "Back200000" to "05-23").asStored(),
            outcomes(unanchored, "december", SHIFTS.keys.drop(4)))
    }

    /** Base Year 2001 moves every shift that crosses a February the Gregorian way and none that lands before 1582. */
    @Test
    fun theBaseYearControlMovesOnlyTheGregorianShifts() {
        assertEquals(shifts("03-01", "03-01", "03-01", "02-28"), outcomes(anchored2001, "march next", SHIFTS.keys.take(4)))
        assertEquals(shifts("06-16", "06-16", "06-16", "06-15", "05-13", "11-06"), outcomes(anchored2001, "june", SHIFTS.keys))
    }

    /** The shifted value keeps the year it reaches: `12-31` plus one day is not the stored `01-01`. */
    @Test
    fun aShiftedDayKeepsTheYearItReaches() {
        assertEquals(listOf("leap day next", "june", "december"), firing(unanchored, documents.keys))
        assertEquals(listOf("march next", "june", "december"), firing(anchored2001, documents.keys - "leap day next"))
    }

    /** Two yearless constants share one leap year with no Base Year, and the field beside a constant shares it too. */
    @Test
    fun yearlessConstantsCompleteInOneYear() {
        assertEquals(differences("-365", "2", "365"), outcomes(unanchored, "year end", DIFFERENCES))
        assertEquals(differences("-365", "2", "60"), outcomes(unanchored, "march first", DIFFERENCES))
        assertEquals(differences("-364", "1", "364"), outcomes(anchored2001, "year end", DIFFERENCES))
        assertEquals(differences("-364", "1", "59"), outcomes(anchored2001, "march first", DIFFERENCES))
        for (model in listOf(unanchored, anchored2001)) {
            assertEquals(listOf("VALUE|05-13", "VALUE|11-06"), outcomes(model, "june", CONSTANT_SHIFTS.keys).values.toList())
        }
    }

    private fun shifts(vararg days: String) = SHIFTS.keys.zip(days.map { "VALUE|$it" }).toMap()

    private fun differences(vararg values: String) = DIFFERENCES.zip(values.map { "VALUE|$it" }).toMap()

    private fun Map<String, String>.asStored() = mapValues { (_, day) -> "VALUE|$day" }

    private fun outcomes(model: String, document: String, targets: Collection<String>): Map<String, String> =
        DocumentDifferential.kernel(model, documents.getValue(document), dynamic = true).outcomes
            .mapKeys { (key, _) -> key.substringAfterLast('/') }.filterKeys { it in targets }

    private fun firing(model: String, names: Collection<String>): List<String> = names.filter { name ->
        DocumentDifferential.kernel(model, documents.getValue(name), dynamic = true).messages.any { it.code == "NEXT_DAY" }
    }

    private companion object {
        /** Each shift's amount in days; the last two cross the 1582 calendar change backwards. */
        val SHIFTS = linkedMapOf("Plus1" to 1L, "Plus36890" to 36890L, "Plus73049" to 73049L, "Minus36160" to -36160L,
            "Back160000" to -160000L, "Back200000" to -200000L)
        val CONSTANT_SHIFTS = linkedMapOf("ConstBack160000" to -160000L, "ConstBack200000" to -200000L)
        val DIFFERENCES = listOf("YearEnd", "LeapSpan", "FromNewYear")
    }
}
