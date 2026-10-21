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
 * Tri-engine lock for `Today` at a pinned instant before the Gregorian cutover (KF576).
 *
 * A12 reads its validation clock through a `GregorianCalendar` in the model's zone, which is Julian before
 * 1582-10-15, while the interpreter's clock was proleptic. Every date before 1583-10-16 is `datumFalsch` when
 * stored, so the rows read `Today` itself: its day, month and year, and `Now`'s day, one rule per candidate value, so each instant
 * fires exactly the calendar it is read in. 1582-10-15 and a modern instant are the controls on which both
 * calendars agree; the modern one also carries a stored-date comparison.
 */
class HybridCalendarClockDiffTest {

    @Test
    fun todayBeforeTheCutoverAgreesOnAllThreeEngines() {
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
        println(observed.entries.joinToString("\n") { (row, sets) -> "[clock] $row → dynamic ${sets[0]} java ${sets[1]} interpreter ${sets[2]}" })
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

        /** Proleptic → hybrid label in Europe/Berlin: 1500-03-10 → 1500-02-29, 1582-10-14 → 1582-10-04; the
         *  first Gregorian day and the modern instant read alike in both. */
        val INSTANTS = listOf("1500-03-10T12:00:00Z", "1582-10-14T12:00:00Z", "1582-10-15T12:00:00Z", "2025-06-23T10:00:00Z")
    }
}
