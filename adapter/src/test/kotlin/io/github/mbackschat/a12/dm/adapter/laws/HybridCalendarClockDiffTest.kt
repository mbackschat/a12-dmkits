package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.EvalClock
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Date

/**
 * Tri-engine lock for `Today` at a pinned instant before the Gregorian cutover (KF576, corrected by KF665).
 *
 * Kernel 31.1.1 reads its validation clock through java.time in the model's zone, which is proleptic Gregorian, so
 * `1500-03-10T12:00Z` is March 10 and `1582-10-14T12:00Z` is October 14; 30.8.1's `GregorianCalendar` read them as
 * the Julian `1500-02-29` and `1582-10-04`. Every date before 1583-10-16 is `datumFalsch` when stored, so the rows
 * read `Today` itself: its day, month and year, and `Now`'s day, one rule per candidate value, so each instant fires
 * exactly the calendar it is read in; the Julian candidates (29, 2, 4) are the rival account's. Two rules compare
 * `Today` with a constant, which fires only when both sides are read in one calendar. 1582-10-15 and a modern instant
 * are the controls on which both calendars agree; the modern one also carries a stored-date comparison.
 */
class HybridCalendarClockDiffTest {

    @Test
    fun todayBeforeTheCutoverReadsProlepticallyOnAllThreeEngines() {
        val base = RuntimeLaws.model("models/order.dm.json")
        val model = BoundModel.ofJson(base)
        val today = Dsl.today()
        val rules = buildList {
            val named = model.str(NAME).filled()
            add(rule(model, "TodayEquals", FIELD, model.date(FIELD).eq(today)))
            for (day in listOf(4, 10, 14, 15, 23, 29)) add(rule(model, "Day$day", NAME, named.and(Dsl.dayFromDate(today).eq(day.toLong()))))
            for (month in listOf(2, 3, 6, 10)) add(rule(model, "Month$month", NAME, named.and(Dsl.monthFromDate(today).eq(month.toLong()))))
            for (year in listOf(1500, 1582, 2025)) add(rule(model, "Year$year", NAME, named.and(Dsl.yearFromDate(today).eq(year.toLong()))))
            for (day in listOf(4, 10, 14, 29)) add(rule(model, "NowDay$day", NAME, named.and(Dsl.dayFromDate(Dsl.now()).eq(day.toLong()))))
            // `Today` compared with a constant. The Julian leap day itself is no constant here: Europe/Berlin cannot read
            // "29.02.1500", so a rule over it fails at evaluation on every instant (KF665).
            add(rule(model, "TodayBeforeMarch11th1500", NAME, named.and(today.before(Dsl.dateValue("11.03.1500")))))
            add(rule(model, "TodayIsMarch10th1500", NAME, named.and(today.eq(Dsl.dateValue("10.03.1500")))))
        }
        val modelJson = rules.fold(base, TypedRules::inject)
        val java = RuntimeLaws.kernelJavaEngine(modelJson)
        val definition = ModelLoader.load(modelJson)

        val observed = linkedMapOf<String, List<Set<String>>>()
        for (instant in INSTANTS) {
            val clock = Date.from(Instant.parse(instant))
            val kernelDocument = RuntimeLaws.doc().group(ROOT, 1).field(NAME, "Acme", 1, 1).field(FIELD, STORED, 1, 1)
            val dynamic = RuntimeLaws.messagesModern(modelJson, kernelDocument, clock).map { it.code() }.toSortedSet()
            val generated = java.messagesModern(kernelDocument, clock).map { it.code() }.toSortedSet()
            val interpreted = DmInterpreter(definition, EvalClock.at(clock.time, definition.timeZone))
                .validateFull(definition.newDocument().group(ROOT, 1).field(NAME, "Acme", 1, 1).field(FIELD, STORED, 1, 1))
                .map { it.code }.toSortedSet()
            observed[instant] = listOf(dynamic, generated, interpreted)
        }
        // KF665's measured values, so agreement cannot pass on three engines sharing one wrong reading.
        assertEquals(
            mapOf(
                "1500-03-10T12:00:00Z" to sortedSetOf("Day10", "Month3", "NowDay10", "TodayBeforeMarch11th1500", "TodayIsMarch10th1500", "Year1500"),
                "1582-10-14T12:00:00Z" to sortedSetOf("Day14", "Month10", "NowDay14", "Year1582"),
                "1582-10-15T12:00:00Z" to sortedSetOf("Day15", "Month10", "Year1582"),
                "2025-06-23T10:00:00Z" to sortedSetOf("Day23", "Month6", "TodayEquals", "Year2025"),
            ),
            observed.mapValues { it.value[0] },
            "dynamic Groovy",
        )
        assertEquals(observed.mapValues { it.value[0] }, observed.mapValues { it.value[1] }, "generated Java")
        assertEquals(observed.mapValues { it.value[0] }, observed.mapValues { it.value[2] }, "interpreter")
    }

    private fun rule(model: BoundModel, code: String, errorField: String, condition: Condition): Rule =
        model.rule(model.group(ROOT))
            .name(code)
            .let { if (errorField == FIELD) it.errorField(model.date(FIELD)) else it.errorField(model.str(errorField)) }
            .`when`(condition)
            .code(code)
            .severity(Severity.ERROR)
            .message(Dsl.en("Clock calendar probe."))
            .build()

    private companion object {
        const val ROOT = "/Order"
        const val FIELD = "/Order/OrderDate"
        const val NAME = "/Order/CustomerName"

        /** A stored date only the modern control's `Today` equals; any date before 1583-10-16 is `datumFalsch`. */
        const val STORED = "2025-06-23"

        /** Two instants whose proleptic and hybrid labels differ (1500-03-10 / 1500-02-29, 1582-10-14 / 1582-10-04);
         *  the first Gregorian day and the modern instant read alike in both. */
        val INSTANTS = listOf("1500-03-10T12:00:00Z", "1582-10-14T12:00:00Z", "1582-10-15T12:00:00Z", "2025-06-23T10:00:00Z")
    }
}
