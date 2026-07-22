package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals

/** JVM+Node lock for SPEC-2026-07-23-04; the adapter peer anchors it to both kernel strategies. */
class TemporalNumericWrapperTest {

    private val model = ModelDef(
        fields = listOf(
            FieldDef(ANCHOR, Kind.STRING),
            FieldDef(START_DATE, Kind.DATE, format = "yyyy-MM-dd"),
            FieldDef(END_DATE, Kind.DATE, format = "yyyy-MM-dd"),
            FieldDef(START_TIME, Kind.TIME, format = "HH:mm:ss"),
            FieldDef(LOGGED_AT, Kind.DATE_TIME, format = "yyyy-MM-dd'T'HH:mm:ss"),
            FieldDef(DATE_ROUND, Kind.NUMBER, scale = 2, minFractionalDigits = 2),
            FieldDef(TIME_ROUND, Kind.NUMBER, scale = 0),
            FieldDef(DATE_TIME_ROUND, Kind.NUMBER, scale = 1, minFractionalDigits = 1),
            FieldDef(BASE_MAGNITUDE, Kind.NUMBER, scale = 0),
            FieldDef(DIRECT_BASE_ROUND, Kind.NUMBER, scale = 0),
            FieldDef(DIRECT_BASE_MAGNITUDE, Kind.NUMBER, scale = 0),
            FieldDef(GAP_MAGNITUDE, Kind.NUMBER, scale = 0),
            FieldDef(FOLLOWUP, Kind.NUMBER, scale = 0),
        ),
        rules = listOf(
            rule("DATE_UP", "RoundUp(MonthFromDate(StartDate), 2) == 5.00", "../StartDate"),
            rule("TIME_DOWN", "RoundDown(HoursFromTime(StartTime)) == 18", "../StartTime"),
            rule("DATETIME_ACCOUNTING", "RoundAccounting(MonthFromDate(LoggedAt), 1) == 7.0", "../LoggedAt"),
            rule("BASE_ABS", "FieldFilled(Anchor) And Abs(YearFromDate(BaseYear)) == 2020", "../Anchor"),
            rule("DIRECT_BASE_ROUND", "FieldFilled(Anchor) And RoundDown(BaseYear) == 2020", "../Anchor"),
            rule("DIRECT_BASE_ABS", "FieldFilled(Anchor) And Abs(BaseYear) == 2020", "../Anchor"),
            rule("DIFF_ABS", "Abs(DifferenceInMonths(StartDate, EndDate)) == 2", "../EndDate"),
            rule("DATE_ROUND_GE", "RoundDown(MonthFromDate(StartDate)) >= 0", "../StartDate"),
            rule("DATE_ROUND_LE", "RoundDown(MonthFromDate(StartDate)) <= 0", "../StartDate"),
            rule("DATE_ABS_GE", "Abs(MonthFromDate(StartDate)) >= 0", "../StartDate"),
            rule("DATE_ABS_LE", "Abs(MonthFromDate(StartDate)) <= 0", "../StartDate"),
        ),
        computations = listOf(
            ComputationDef(DATE_ROUND, ROOT, "RoundUp(MonthFromDate(StartDate), 2)"),
            ComputationDef(TIME_ROUND, ROOT, "RoundDown(HoursFromTime(StartTime))"),
            ComputationDef(DATE_TIME_ROUND, ROOT, "RoundAccounting(MonthFromDate(LoggedAt), 1)"),
            ComputationDef(BASE_MAGNITUDE, ROOT, "Abs(YearFromDate(BaseYear))"),
            ComputationDef(DIRECT_BASE_ROUND, ROOT, "RoundDown(BaseYear)"),
            ComputationDef(DIRECT_BASE_MAGNITUDE, ROOT, "Abs(BaseYear)"),
            ComputationDef(GAP_MAGNITUDE, ROOT, "Abs(DifferenceInMonths(StartDate, EndDate))"),
            ComputationDef(FOLLOWUP, ROOT, "[GapMagnitude] + 1"),
        ),
        baseYear = 2020,
    )

    private fun rule(code: String, condition: String, errorRel: String) =
        RuleDef("$ROOT/${code}Rule", ROOT, condition, code, errorRel)

    @Test
    fun storedBaseYearAndSignedDifferenceSourcesComposeBeforeTheirWrappers() {
        val present = document(
            TemporalValues(
                startDate = "2020-05-31",
                endDate = "2020-03-31",
                startTime = "18:30:00",
                loggedAt = "2022-07-04T09:15:00",
            ),
        )
        assertEquals(
            listOf(
                "BASE_ABS", "DATETIME_ACCOUNTING", "DATE_ABS_GE", "DATE_ROUND_GE", "DATE_UP", "DIFF_ABS",
                "DIRECT_BASE_ABS", "DIRECT_BASE_ROUND", "TIME_DOWN",
            ),
            signals(present),
        )
        assertEquals(
            listOf(
                "$BASE_MAGNITUDE|VALUE|2020",
                "$DATE_ROUND|VALUE|5.00",
                "$DATE_TIME_ROUND|VALUE|7.0",
                "$DIRECT_BASE_MAGNITUDE|VALUE|2020",
                "$DIRECT_BASE_ROUND|VALUE|2020",
                "$FOLLOWUP|VALUE|3",
                "$GAP_MAGNITUDE|VALUE|2",
                "$TIME_ROUND|VALUE|18",
            ),
            outcomes(present),
        )
    }

    @Test
    fun emptyAndFormalStatesRetainTheirWrapperProjection() {
        assertEquals(
            listOf(
                "BASE_ABS",
                "DATE_ABS_GE",
                "DATE_ABS_LE:OMISSION",
                "DATE_ROUND_GE:OMISSION",
                "DATE_ROUND_LE:OMISSION",
                "DIRECT_BASE_ABS",
                "DIRECT_BASE_ROUND",
            ),
            signals(document(TemporalValues())),
        )

        val invalid = document(TemporalValues("invalid", "invalid", "invalid", "invalid"))
        TARGETS.forEach { invalid.field(it, "9", 1, 1) }
        assertEquals(
            listOf(
                "$BASE_MAGNITUDE|VALUE|2020",
                "$DATE_ROUND|CLEARED",
                "$DATE_TIME_ROUND|CLEARED",
                "$DIRECT_BASE_MAGNITUDE|VALUE|2020",
                "$DIRECT_BASE_ROUND|VALUE|2020",
                "$FOLLOWUP|CLEARED",
                "$GAP_MAGNITUDE|CLEARED",
                "$TIME_ROUND|CLEARED",
            ),
            outcomes(invalid),
        )
    }

    private fun document(values: TemporalValues): Document {
        val document = model.newDocument().group(ROOT, 1).field(ANCHOR, "active", 1, 1)
        values.startDate?.let { document.field(START_DATE, it, 1, 1) }
        values.endDate?.let { document.field(END_DATE, it, 1, 1) }
        values.startTime?.let { document.field(START_TIME, it, 1, 1) }
        values.loggedAt?.let { document.field(LOGGED_AT, it, 1, 1) }
        return document
    }

    private fun signals(document: Document) = DmInterpreter(model).validateFull(document).filter { it.code in CODES }.map {
        "${it.code}${if (it.type == MsgType.OMISSION) ":OMISSION" else ""}"
    }.sorted()

    private fun outcomes(document: Document) = DmInterpreter(model).compute(document).filter { it.path in TARGETS }.map {
        when (it.outcome) {
            ComputeOutcome.VALUE -> "${it.path}|VALUE|${it.value}"
            ComputeOutcome.CLEARED -> "${it.path}|CLEARED"
            ComputeOutcome.ERRORED -> "${it.path}|ERRORED"
        }
    }.sorted()

    private data class TemporalValues(
        val startDate: String? = null,
        val endDate: String? = null,
        val startTime: String? = null,
        val loggedAt: String? = null,
    )

    private companion object {
        const val ROOT = "/Schedule"
        const val ANCHOR = "/Schedule/Anchor"
        const val START_DATE = "/Schedule/StartDate"
        const val END_DATE = "/Schedule/EndDate"
        const val START_TIME = "/Schedule/StartTime"
        const val LOGGED_AT = "/Schedule/LoggedAt"
        const val DATE_ROUND = "/Schedule/DateRound"
        const val TIME_ROUND = "/Schedule/TimeRound"
        const val DATE_TIME_ROUND = "/Schedule/DateTimeRound"
        const val BASE_MAGNITUDE = "/Schedule/BaseMagnitude"
        const val DIRECT_BASE_ROUND = "/Schedule/DirectBaseRound"
        const val DIRECT_BASE_MAGNITUDE = "/Schedule/DirectBaseMagnitude"
        const val GAP_MAGNITUDE = "/Schedule/GapMagnitude"
        const val FOLLOWUP = "/Schedule/Followup"
        val TARGETS = setOf(
            DATE_ROUND, TIME_ROUND, DATE_TIME_ROUND, BASE_MAGNITUDE, DIRECT_BASE_ROUND, DIRECT_BASE_MAGNITUDE,
            GAP_MAGNITUDE, FOLLOWUP,
        )
        val CODES = setOf(
            "DATE_UP", "TIME_DOWN", "DATETIME_ACCOUNTING", "BASE_ABS", "DIFF_ABS", "DATE_ROUND_GE",
            "DATE_ROUND_LE", "DATE_ABS_GE", "DATE_ABS_LE", "DIRECT_BASE_ROUND", "DIRECT_BASE_ABS",
        )
    }
}
