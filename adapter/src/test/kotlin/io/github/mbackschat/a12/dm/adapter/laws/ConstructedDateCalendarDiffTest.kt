package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.UnsupportedModelTimeZoneException
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.ModelDocument
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Both kernel strategies lock `Date(...)` construction reality at the two legacy-calendar separators:
 * the Julian side of the default Gregorian cutover and a model-zone whole-date discontinuity.
 */
class ConstructedDateCalendarDiffTest {

    @Test
    fun utcJulianLeapDayAndNeighborsTriCheck() {
        val fixture = fixture("UTC")
        assertClass(fixture, 29, 2, 1500, setOf(VALID), includeInterpreter = true)
        assertClass(fixture, 28, 2, 1500, setOf(VALID), includeInterpreter = true)
        assertClass(fixture, 1, 3, 1500, setOf(VALID), includeInterpreter = true)
        assertClass(fixture, 29, 2, 1501, setOf(INVALID), includeInterpreter = true)
    }

    @Test
    fun julianConstructionRemainsAValueForEveryLegalDateConsumerFamily() {
        val fixture = fixture("UTC")
        val expected = setOf(
            EXTRACTED, DAY_DIFF, DAY_DIFF_YEAR, MONTH_DIFF, YEAR_DIFF, ADD_DAY, ADD_MONTH, ADD_YEAR,
            DATE_TIME_DIFF, DATE_TIME_ADD, MIN_LEGACY,
        )
        val document = document(29, 2, 1500)

        val dynamic = RuntimeLaws.messages(fixture.modelJson, document)
            .map { it.code() }.filter { it in expected }.toSet()
        val generated = fixture.javaEngine.messages(fixture.javaEngine.instance(document))
            .map { it.code() }.filter { it in expected }.toSet()
        assertEquals(expected, dynamic, "dynamic-Groovy must preserve the constructed Julian date")
        assertEquals(dynamic, generated, "generated Java must preserve the same constructed value")

        val definition = ModelLoader.load(fixture.modelJson)
        val interpreted = DmInterpreter(definition).validateFull(
            interpretedDocument(definition, 29, 2, 1500),
        ).map { it.code }.filter { it in expected }.toSet()
        assertEquals(dynamic, interpreted, "interpreter must not reparse Date(...) as proleptic Gregorian")
    }

    @Test
    fun constructedDatesStepAcrossTheDefaultCutoverWithoutInventingTenDays() {
        val fixture = fixture("UTC")
        val expected = setOf(CUTOVER_DIFF, CUTOVER_ADD)
        val document = document(4, 10, 1582)

        val dynamic = RuntimeLaws.messages(fixture.modelJson, document)
            .map { it.code() }.filter { it in expected }.toSet()
        val generated = fixture.javaEngine.messages(fixture.javaEngine.instance(document))
            .map { it.code() }.filter { it in expected }.toSet()
        assertEquals(expected, dynamic, "1582-10-04 plus one legacy-calendar day is 1582-10-15")
        assertEquals(dynamic, generated, "generated Java cutover result")

        val definition = ModelLoader.load(fixture.modelJson)
        val interpreted = DmInterpreter(definition).validateFull(
            interpretedDocument(definition, 4, 10, 1582),
        ).map { it.code }.filter { it in expected }.toSet()
        assertEquals(dynamic, interpreted, "interpreter cutover result")
    }

    @Test
    fun unrealConstructedDateExtractorsYieldZeroWhileMalformedPartsStayUnknown() {
        val fixture = fixture("UTC")
        val expected = setOf(
            ZERO_EXTRACT, ZERO_QUARTER_EXTRACT, ZERO_DATE_TIME_EXTRACT, ZERO_DATE_TIME_HOUR,
            ZERO_DATE_DIFF, ZERO_DATE_TIME_DIFF, ZERO_MIN_SKIP, ZERO_MAX_SKIP,
        )
        assertConsumerCodes(fixture, "10", "10", "1582", expected, "cutover-hole Date")
        assertConsumerCodes(fixture, "abc", "10", "1582", emptySet(), "malformed Date component")
    }

    @Test
    fun constructedDateNoValueReasonSurvivesLegalNestedConsumers() {
        val fixture = fixture("UTC")
        assertNestedConsumerCodes(
            fixture, day = "10", hour = "12",
            expected = setOf(NESTED_SHIFT_ZERO, NESTED_DATE_FROM_ZERO, COMPOSED_DATE_TIME_ZERO),
            clue = "unreal date plus valid time",
        )
        assertNestedConsumerCodes(
            fixture, day = "15", hour = null,
            expected = setOf(NESTED_DATE_FROM_ZERO, COMPOSED_DATE_TIME_ZERO),
            clue = "real date plus empty time",
        )
        assertNestedConsumerCodes(
            fixture, day = null, hour = "bad",
            expected = setOf(NESTED_SHIFT_ZERO),
            clue = "incomplete date plus malformed time",
        )
    }

    @Test
    fun monthAndYearArithmeticUsesTheExactLegacyCutoverSeparators() {
        val fixture = fixture("UTC")
        val expected = setOf(
            CUTOVER_ADD_MONTH, CUTOVER_ADD_YEAR,
            CUTOVER_MONTH_BEFORE, CUTOVER_MONTH_AT,
            CUTOVER_YEAR_BEFORE, CUTOVER_MONTHS_BEFORE,
            CUTOVER_YEAR_AT, CUTOVER_MONTHS_AT,
            GREGORIAN_END_OF_FEBRUARY,
        )
        val document = document(29, 2, 1500)
        val dynamic = RuntimeLaws.messages(fixture.modelJson, document)
            .map { it.code() }.filter { it in expected }.toSet()
        val generated = fixture.javaEngine.messages(fixture.javaEngine.instance(document))
            .map { it.code() }.filter { it in expected }.toSet()
        assertEquals(expected, dynamic, "dynamic-Groovy legacy month/year separators")
        assertEquals(dynamic, generated, "generated Java legacy month/year separators")

        val definition = ModelLoader.load(fixture.modelJson)
        val interpreted = DmInterpreter(definition).validateFull(
            interpretedDocument(definition, 29, 2, 1500),
        ).map { it.code }.filter { it in expected }.toSet()
        assertEquals(dynamic, interpreted, "interpreter legacy month/year separators")
    }

    @Test
    fun supportedGmtAndBerlinUseTheSameHybridCutoverCalendar() {
        for (zone in listOf("GMT", "Europe/Berlin")) {
            val fixture = fixture(zone)
            assertClass(fixture, 29, 2, 1500, setOf(VALID), includeInterpreter = true)
            assertClass(fixture, 10, 10, 1582, setOf(INVALID), includeInterpreter = true)
            assertClass(fixture, 15, 10, 1582, setOf(VALID), includeInterpreter = true)
        }
    }

    @Test
    fun apiaSkippedDateAndNeighborsAgreeAcrossBothKernelStrategies() {
        val fixture = fixture("Pacific/Apia")
        assertClass(fixture, 29, 12, 2011, setOf(VALID), includeInterpreter = false)
        assertClass(fixture, 30, 12, 2011, setOf(INVALID), includeInterpreter = false)
        assertClass(fixture, 31, 12, 2011, setOf(VALID), includeInterpreter = false)

        assertThrows(UnsupportedModelTimeZoneException::class.java) {
            ModelLoader.load(fixture.modelJson)
        }
    }

    private fun assertClass(
        fixture: Fixture,
        day: Int,
        month: Int,
        year: Int,
        expected: Set<String>,
        includeInterpreter: Boolean,
    ) {
        val document = document(day, month, year)
        val dynamic = RuntimeLaws.messages(fixture.modelJson, document)
            .map { it.code() }.filter { it == VALID || it == INVALID }.toSet()
        val generated = fixture.javaEngine.messages(fixture.javaEngine.instance(document))
            .map { it.code() }.filter { it == VALID || it == INVALID }.toSet()

        val clue = "${fixture.zone} $year-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"
        assertEquals(expected, dynamic, "$clue — dynamic-Groovy classification")
        assertEquals(dynamic, generated, "$clue — generated Java")

        if (includeInterpreter) {
            val definition = ModelLoader.load(fixture.modelJson)
            val interpretedDocument = interpretedDocument(definition, day, month, year)
            val interpreted = DmInterpreter(definition).validateFull(interpretedDocument)
                .map { it.code }.filter { it == VALID || it == INVALID }.toSet()
            assertEquals(dynamic, interpreted, "$clue — interpreter")
        }
    }

    private fun fixture(zone: String): Fixture {
        val zoned = ModelDocument.read(BASE).setTimeZone(zone).toJson()
        val model = BoundModel.ofJson(zoned)
        val date = Dsl.date(
            model.num(DAY).asNumber(),
            model.num(MONTH).asNumber(),
            model.num(YEAR).asNumber(),
        )
        val valid = rule(model, "ConstructedDateValid", VALID, Dsl.valid(date))
        val invalid = rule(model, "ConstructedDateInvalid", INVALID, Dsl.invalid(date))
        fun target(prefix: String) = Dsl.date(
            model.num("$ROOT/${prefix}Day").asNumber(),
            model.num("$ROOT/${prefix}Month").asNumber(),
            model.num("$ROOT/${prefix}Year").asNumber(),
        )
        val marchFirst1500 = target("NextDay")
        val marchTwentyNinth1500 = target("NextMonth")
        val februaryTwentyEighth1501 = target("NextYear")
        val octoberFourth1582 = target("CutoverStart")
        val octoberFifteenth1582 = target("CutoverEnd")
        val septemberTenth1582 = target("September1582")
        val octoberTwentieth1582 = target("October1582")
        val octoberTenth1581 = target("October1581")
        val februaryTwentyEighth1499 = target("February1499")
        val februaryTwentyEighth1500 = target("February1500")
        val noon = Dsl.time("12", "00", "00")
        val suppliedTime = Dsl.time(model.field(HOUR), model.field(MINUTE), model.field(SECOND))
        val constructedDateTime = Dsl.dateTime(date, noon)
        val suppliedDateTime = Dsl.dateTime(date, suppliedTime)
        val nextDayDateTime = Dsl.dateTime(marchFirst1500, noon)
        val rules = listOf(
            valid,
            invalid,
            rule(model, "ConstructedDateExtracted", EXTRACTED, Dsl.yearFromDate(date).eq(1500)),
            rule(model, "ConstructedDateMinKeepsCalendar", MIN_LEGACY,
                Dsl.min(date, marchFirst1500).eq(date)),
            rule(model, "UnrealConstructedDateExtracted", ZERO_EXTRACT, Dsl.yearFromDate(date).eq(0)),
            rule(model, "UnrealConstructedDateQuarter", ZERO_QUARTER_EXTRACT, Dsl.quarterFromDate(date).eq(0)),
            rule(model, "UnrealConstructedDateTimeExtracted", ZERO_DATE_TIME_EXTRACT,
                Dsl.yearFromDate(constructedDateTime).eq(0)),
            rule(model, "UnrealConstructedDateTimeHour", ZERO_DATE_TIME_HOUR,
                Dsl.hoursFromTime(constructedDateTime).eq(0)),
            rule(model, "UnrealConstructedDateDifference", ZERO_DATE_DIFF,
                Dsl.differenceInDays(date, marchFirst1500).eq(0)),
            rule(model, "UnrealConstructedDateTimeDifference", ZERO_DATE_TIME_DIFF,
                Dsl.differenceInHours(constructedDateTime, nextDayDateTime).eq(0)),
            rule(model, "UnrealConstructedDateMinSkipped", ZERO_MIN_SKIP,
                Dsl.min(date, marchFirst1500).eq(marchFirst1500)),
            rule(model, "UnrealConstructedDateMaxSkipped", ZERO_MAX_SKIP,
                Dsl.max(date, marchFirst1500).eq(marchFirst1500)),
            rule(model, "UnrealConstructedDateNestedShift", NESTED_SHIFT_ZERO,
                Dsl.yearFromDate(Dsl.addDays(date, 1)).eq(0)),
            rule(model, "ConstructedDateNestedDateTimePart", NESTED_DATE_FROM_ZERO,
                Dsl.yearFromDate(Dsl.dateFromDateTime(suppliedDateTime)).eq(0)),
            rule(model, "ConstructedDateTimeCarriesNoValue", COMPOSED_DATE_TIME_ZERO,
                Dsl.yearFromDate(suppliedDateTime).eq(0)),
            rule(model, "ConstructedDateDayDifference", DAY_DIFF, Dsl.differenceInDays(date, marchFirst1500).eq(1)),
            rule(model, "ConstructedDateCrossYearDayDifference", DAY_DIFF_YEAR,
                Dsl.differenceInDays(date, februaryTwentyEighth1501).eq(365)),
            rule(model, "ConstructedDateMonthDifference", MONTH_DIFF, Dsl.differenceInMonths(date, marchTwentyNinth1500).eq(1)),
            rule(model, "ConstructedDateYearDifference", YEAR_DIFF, Dsl.differenceInYears(date, februaryTwentyEighth1501).eq(1)),
            rule(model, "ConstructedDateAddDay", ADD_DAY, Dsl.addDays(date, 1).eq(marchFirst1500)),
            rule(model, "ConstructedDateAddMonth", ADD_MONTH, Dsl.addMonths(date, 1).eq(marchTwentyNinth1500)),
            rule(model, "ConstructedDateAddYear", ADD_YEAR, Dsl.addYears(date, 1).eq(februaryTwentyEighth1501)),
            rule(model, "ConstructedDateTimeDifference", DATE_TIME_DIFF,
                Dsl.differenceInHours(constructedDateTime, nextDayDateTime).eq(24)),
            rule(model, "ConstructedDateTimeAdd", DATE_TIME_ADD,
                Dsl.addDays(constructedDateTime, 1).eq(nextDayDateTime)),
            rule(model, "ConstructedDateCutoverDifference", CUTOVER_DIFF,
                Dsl.differenceInDays(date, octoberFifteenth1582).eq(1)),
            rule(model, "ConstructedDateCutoverAdd", CUTOVER_ADD,
                model.num(YEAR).filled().and(Dsl.addDays(octoberFourth1582, 1).eq(octoberFifteenth1582))),
            rule(model, "ConstructedDateCutoverAddMonth", CUTOVER_ADD_MONTH,
                model.num(YEAR).filled().and(Dsl.addMonths(septemberTenth1582, 1).eq(octoberTwentieth1582))),
            rule(model, "ConstructedDateCutoverAddYear", CUTOVER_ADD_YEAR,
                model.num(YEAR).filled().and(Dsl.addYears(octoberTenth1581, 1).eq(octoberTwentieth1582))),
            rule(model, "ConstructedDateCutoverMonthBefore", CUTOVER_MONTH_BEFORE,
                model.num(YEAR).filled().and(
                    Dsl.differenceInMonths(septemberTenth1582, octoberFifteenth1582).eq(0))),
            rule(model, "ConstructedDateCutoverMonthAt", CUTOVER_MONTH_AT,
                model.num(YEAR).filled().and(
                    Dsl.differenceInMonths(septemberTenth1582, octoberTwentieth1582).eq(1))),
            rule(model, "ConstructedDateCutoverYearBefore", CUTOVER_YEAR_BEFORE,
                model.num(YEAR).filled().and(
                    Dsl.differenceInYears(octoberTenth1581, octoberFifteenth1582).eq(0))),
            rule(model, "ConstructedDateCutoverMonthsBefore", CUTOVER_MONTHS_BEFORE,
                model.num(YEAR).filled().and(
                    Dsl.differenceInMonths(octoberTenth1581, octoberFifteenth1582).eq(11))),
            rule(model, "ConstructedDateCutoverYearAt", CUTOVER_YEAR_AT,
                model.num(YEAR).filled().and(
                    Dsl.differenceInYears(octoberTenth1581, octoberTwentieth1582).eq(1))),
            rule(model, "ConstructedDateCutoverMonthsAt", CUTOVER_MONTHS_AT,
                model.num(YEAR).filled().and(
                    Dsl.differenceInMonths(octoberTenth1581, octoberTwentieth1582).eq(12))),
            rule(model, "ConstructedDateGregorianEndOfFebruary", GREGORIAN_END_OF_FEBRUARY,
                model.num(YEAR).filled().and(
                    Dsl.addYears(februaryTwentyEighth1499, 1).eq(februaryTwentyEighth1500))),
        )
        var completed = model
        rules.forEach { completed = completed.add(it) }
        val modelJson = completed.toJson()
        return Fixture(zone, modelJson, RuntimeLaws.kernelJavaEngine(modelJson))
    }

    private fun document(day: Int, month: Int, year: Int): RuntimeLaws.Doc =
        document(day.toString(), month.toString(), year.toString())

    private fun document(day: String, month: String, year: String): RuntimeLaws.Doc =
        RuntimeLaws.doc().group(ROOT, 1)
            .field(DAY, day, 1, 1)
            .field(MONTH, month, 1, 1)
            .field(YEAR, year, 1, 1)
            .withTargets()

    private fun assertConsumerCodes(
        fixture: Fixture,
        day: String,
        month: String,
        year: String,
        expected: Set<String>,
        clue: String,
    ) {
        val relevant = setOf(
            ZERO_EXTRACT, ZERO_QUARTER_EXTRACT, ZERO_DATE_TIME_EXTRACT, ZERO_DATE_TIME_HOUR,
            ZERO_DATE_DIFF, ZERO_DATE_TIME_DIFF, ZERO_MIN_SKIP, ZERO_MAX_SKIP,
        )
        val document = document(day, month, year)
        val dynamic = RuntimeLaws.messages(fixture.modelJson, document)
            .map { it.code() }.filter { it in relevant }.toSet()
        val generated = fixture.javaEngine.messages(fixture.javaEngine.instance(document))
            .map { it.code() }.filter { it in relevant }.toSet()
        assertEquals(expected, dynamic, "$clue — dynamic-Groovy")
        assertEquals(dynamic, generated, "$clue — generated Java")

        val definition = ModelLoader.load(fixture.modelJson)
        val interpreted = DmInterpreter(definition).validateFull(
            interpretedDocument(definition, day, month, year),
        ).map { it.code }.filter { it in relevant }.toSet()
        assertEquals(dynamic, interpreted, "$clue — interpreter")
    }

    private fun assertNestedConsumerCodes(
        fixture: Fixture,
        day: String?,
        hour: String?,
        expected: Set<String>,
        clue: String,
    ) {
        val relevant = setOf(NESTED_SHIFT_ZERO, NESTED_DATE_FROM_ZERO, COMPOSED_DATE_TIME_ZERO)
        val document = stateDocument(day, hour)
        val dynamic = RuntimeLaws.messages(fixture.modelJson, document)
            .map { it.code() }.filter { it in relevant }.toSet()
        val generated = fixture.javaEngine.messages(fixture.javaEngine.instance(document))
            .map { it.code() }.filter { it in relevant }.toSet()
        assertEquals(expected, dynamic, "$clue — dynamic-Groovy")
        assertEquals(dynamic, generated, "$clue — generated Java")

        val definition = ModelLoader.load(fixture.modelJson)
        val interpreted = DmInterpreter(definition).validateFull(
            interpretedStateDocument(definition, day, hour),
        ).map { it.code }.filter { it in relevant }.toSet()
        assertEquals(dynamic, interpreted, "$clue — interpreter")
    }

    private fun stateDocument(day: String?, hour: String?): RuntimeLaws.Doc {
        val document = RuntimeLaws.doc().group(ROOT, 1)
            .field(MONTH, "10", 1, 1)
            .field(YEAR, "1582", 1, 1)
            .field(MINUTE, "0", 1, 1)
            .field(SECOND, "0", 1, 1)
            .withTargets()
        day?.let { document.field(DAY, it, 1, 1) }
        hour?.let { document.field(HOUR, it, 1, 1) }
        return document
    }

    private fun RuntimeLaws.Doc.withTargets(): RuntimeLaws.Doc = this
        .field("$ROOT/NextDayDay", "1", 1, 1)
        .field("$ROOT/NextDayMonth", "3", 1, 1)
        .field("$ROOT/NextDayYear", "1500", 1, 1)
        .field("$ROOT/NextMonthDay", "29", 1, 1)
        .field("$ROOT/NextMonthMonth", "3", 1, 1)
        .field("$ROOT/NextMonthYear", "1500", 1, 1)
        .field("$ROOT/NextYearDay", "28", 1, 1)
        .field("$ROOT/NextYearMonth", "2", 1, 1)
        .field("$ROOT/NextYearYear", "1501", 1, 1)
        .field("$ROOT/CutoverStartDay", "4", 1, 1)
        .field("$ROOT/CutoverStartMonth", "10", 1, 1)
        .field("$ROOT/CutoverStartYear", "1582", 1, 1)
        .field("$ROOT/CutoverEndDay", "15", 1, 1)
        .field("$ROOT/CutoverEndMonth", "10", 1, 1)
        .field("$ROOT/CutoverEndYear", "1582", 1, 1)
        .field("$ROOT/September1582Day", "10", 1, 1)
        .field("$ROOT/September1582Month", "9", 1, 1)
        .field("$ROOT/September1582Year", "1582", 1, 1)
        .field("$ROOT/October1582Day", "20", 1, 1)
        .field("$ROOT/October1582Month", "10", 1, 1)
        .field("$ROOT/October1582Year", "1582", 1, 1)
        .field("$ROOT/October1581Day", "10", 1, 1)
        .field("$ROOT/October1581Month", "10", 1, 1)
        .field("$ROOT/October1581Year", "1581", 1, 1)
        .field("$ROOT/February1499Day", "28", 1, 1)
        .field("$ROOT/February1499Month", "2", 1, 1)
        .field("$ROOT/February1499Year", "1499", 1, 1)
        .field("$ROOT/February1500Day", "28", 1, 1)
        .field("$ROOT/February1500Month", "2", 1, 1)
        .field("$ROOT/February1500Year", "1500", 1, 1)

    private fun interpretedDocument(
        definition: io.github.mbackschat.a12.dm.interpreter.model.ModelDef,
        day: Int,
        month: Int,
        year: Int,
    ) = interpretedDocument(definition, day.toString(), month.toString(), year.toString())

    private fun interpretedDocument(
        definition: io.github.mbackschat.a12.dm.interpreter.model.ModelDef,
        day: String?,
        month: String,
        year: String,
    ): io.github.mbackschat.a12.dm.interpreter.eval.Document {
        val document = definition.newDocument().group(ROOT, 1)
            .field(MONTH, month, 1, 1)
            .field(YEAR, year, 1, 1)
            .field("$ROOT/NextDayDay", "1", 1, 1)
            .field("$ROOT/NextDayMonth", "3", 1, 1)
            .field("$ROOT/NextDayYear", "1500", 1, 1)
            .field("$ROOT/NextMonthDay", "29", 1, 1)
            .field("$ROOT/NextMonthMonth", "3", 1, 1)
            .field("$ROOT/NextMonthYear", "1500", 1, 1)
            .field("$ROOT/NextYearDay", "28", 1, 1)
            .field("$ROOT/NextYearMonth", "2", 1, 1)
            .field("$ROOT/NextYearYear", "1501", 1, 1)
            .field("$ROOT/CutoverStartDay", "4", 1, 1)
            .field("$ROOT/CutoverStartMonth", "10", 1, 1)
            .field("$ROOT/CutoverStartYear", "1582", 1, 1)
            .field("$ROOT/CutoverEndDay", "15", 1, 1)
            .field("$ROOT/CutoverEndMonth", "10", 1, 1)
            .field("$ROOT/CutoverEndYear", "1582", 1, 1)
            .field("$ROOT/September1582Day", "10", 1, 1)
            .field("$ROOT/September1582Month", "9", 1, 1)
            .field("$ROOT/September1582Year", "1582", 1, 1)
            .field("$ROOT/October1582Day", "20", 1, 1)
            .field("$ROOT/October1582Month", "10", 1, 1)
            .field("$ROOT/October1582Year", "1582", 1, 1)
            .field("$ROOT/October1581Day", "10", 1, 1)
            .field("$ROOT/October1581Month", "10", 1, 1)
            .field("$ROOT/October1581Year", "1581", 1, 1)
            .field("$ROOT/February1499Day", "28", 1, 1)
            .field("$ROOT/February1499Month", "2", 1, 1)
            .field("$ROOT/February1499Year", "1499", 1, 1)
            .field("$ROOT/February1500Day", "28", 1, 1)
            .field("$ROOT/February1500Month", "2", 1, 1)
            .field("$ROOT/February1500Year", "1500", 1, 1)
        day?.let { document.field(DAY, it, 1, 1) }
        return document
    }

    private fun interpretedStateDocument(
        definition: io.github.mbackschat.a12.dm.interpreter.model.ModelDef,
        day: String?,
        hour: String?,
    ): io.github.mbackschat.a12.dm.interpreter.eval.Document {
        val document = interpretedDocument(definition, day, "10", "1582")
            .field(MINUTE, "0", 1, 1)
            .field(SECOND, "0", 1, 1)
        hour?.let { document.field(HOUR, it, 1, 1) }
        return document
    }

    private fun rule(model: BoundModel, name: String, code: String, condition: io.github.mbackschat.a12.dm.rulekit.dsl.Condition): Rule =
        model.rule(model.group(ROOT)).name(name)
            .errorField(model.field(YEAR))
            .`when`(condition)
            .code(code)
            .severity(Severity.ERROR)
            .message(Dsl.en(code))
            .build()

    private data class Fixture(
        val zone: String,
        val modelJson: String,
        val javaEngine: RuntimeLaws.JavaKernelEngine,
    )

    private companion object {
        const val ROOT = "/CalendarLab"
        const val DAY = "$ROOT/Day"
        const val MONTH = "$ROOT/Month"
        const val YEAR = "$ROOT/Year"
        const val HOUR = "$ROOT/Hour"
        const val MINUTE = "$ROOT/Minute"
        const val SECOND = "$ROOT/Second"
        const val VALID = "CONSTRUCTED_DATE_VALID"
        const val INVALID = "CONSTRUCTED_DATE_INVALID"
        const val EXTRACTED = "CONSTRUCTED_DATE_EXTRACTED"
        const val ZERO_EXTRACT = "CONSTRUCTED_DATE_ZERO_EXTRACT"
        const val ZERO_QUARTER_EXTRACT = "CONSTRUCTED_DATE_ZERO_QUARTER"
        const val ZERO_DATE_TIME_EXTRACT = "CONSTRUCTED_DATE_TIME_ZERO_EXTRACT"
        const val ZERO_DATE_TIME_HOUR = "CONSTRUCTED_DATE_TIME_ZERO_HOUR"
        const val ZERO_DATE_DIFF = "CONSTRUCTED_DATE_ZERO_DIFF"
        const val ZERO_DATE_TIME_DIFF = "CONSTRUCTED_DATE_TIME_ZERO_DIFF"
        const val ZERO_MIN_SKIP = "CONSTRUCTED_DATE_ZERO_MIN_SKIP"
        const val ZERO_MAX_SKIP = "CONSTRUCTED_DATE_ZERO_MAX_SKIP"
        const val NESTED_SHIFT_ZERO = "CONSTRUCTED_DATE_NESTED_SHIFT_ZERO"
        const val NESTED_DATE_FROM_ZERO = "CONSTRUCTED_DATE_NESTED_DATE_FROM_ZERO"
        const val COMPOSED_DATE_TIME_ZERO = "CONSTRUCTED_DATE_TIME_COMPOSED_ZERO"
        const val MIN_LEGACY = "CONSTRUCTED_DATE_MIN_LEGACY"
        const val DAY_DIFF = "CONSTRUCTED_DATE_DAY_DIFF"
        const val DAY_DIFF_YEAR = "CONSTRUCTED_DATE_DAY_DIFF_YEAR"
        const val MONTH_DIFF = "CONSTRUCTED_DATE_MONTH_DIFF"
        const val YEAR_DIFF = "CONSTRUCTED_DATE_YEAR_DIFF"
        const val ADD_DAY = "CONSTRUCTED_DATE_ADD_DAY"
        const val ADD_MONTH = "CONSTRUCTED_DATE_ADD_MONTH"
        const val ADD_YEAR = "CONSTRUCTED_DATE_ADD_YEAR"
        const val DATE_TIME_DIFF = "CONSTRUCTED_DATE_TIME_DIFF"
        const val DATE_TIME_ADD = "CONSTRUCTED_DATE_TIME_ADD"
        const val CUTOVER_DIFF = "CONSTRUCTED_DATE_CUTOVER_DIFF"
        const val CUTOVER_ADD = "CONSTRUCTED_DATE_CUTOVER_ADD"
        const val CUTOVER_ADD_MONTH = "CONSTRUCTED_DATE_CUTOVER_ADD_MONTH"
        const val CUTOVER_ADD_YEAR = "CONSTRUCTED_DATE_CUTOVER_ADD_YEAR"
        const val CUTOVER_MONTH_BEFORE = "CONSTRUCTED_DATE_CUTOVER_MONTH_BEFORE"
        const val CUTOVER_MONTH_AT = "CONSTRUCTED_DATE_CUTOVER_MONTH_AT"
        const val CUTOVER_YEAR_BEFORE = "CONSTRUCTED_DATE_CUTOVER_YEAR_BEFORE"
        const val CUTOVER_MONTHS_BEFORE = "CONSTRUCTED_DATE_CUTOVER_MONTHS_BEFORE"
        const val CUTOVER_YEAR_AT = "CONSTRUCTED_DATE_CUTOVER_YEAR_AT"
        const val CUTOVER_MONTHS_AT = "CONSTRUCTED_DATE_CUTOVER_MONTHS_AT"
        const val GREGORIAN_END_OF_FEBRUARY = "CONSTRUCTED_DATE_GREGORIAN_END_OF_FEBRUARY"

        val BASE: String = DocumentModelDraft.named("calendar-lab").locale("en_US")
            .rootGroup("CalendarLab") { group ->
                group.numberField("Day") { it.fractionDigits(0, 0).min("1").max("31") }
                group.numberField("Month") { it.fractionDigits(0, 0).min("1").max("12") }
                group.numberField("Year") { it.fractionDigits(0, 0).min("1").max("9999") }
                group.numberField("Hour") { it.fractionDigits(0, 0).min("0").max("23") }
                group.numberField("Minute") { it.fractionDigits(0, 0).min("0").max("59") }
                group.numberField("Second") { it.fractionDigits(0, 0).min("0").max("59") }
                for (prefix in listOf(
                    "NextDay", "NextMonth", "NextYear", "CutoverStart", "CutoverEnd",
                    "September1582", "October1582", "October1581", "February1499", "February1500",
                )) {
                    group.numberField("${prefix}Day") { it.fractionDigits(0, 0).min("1").max("31") }
                    group.numberField("${prefix}Month") { it.fractionDigits(0, 0).min("1").max("12") }
                    group.numberField("${prefix}Year") { it.fractionDigits(0, 0).min("1").max("9999") }
                }
            }
            .build()
            .toJson()
    }
}
