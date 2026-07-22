package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Tri-engine composition lock for SPEC-2026-07-23-04. */
class TemporalNumericWrapperDiffTest {

    private val base = DocumentModelDraft.named("schedule-temporal-wrappers").locale("en_US").baseYear(2020)
        .rootGroup("Schedule") { schedule ->
            schedule.stringField("Anchor") { }
            schedule.dateField("StartDate") { date -> date.format("yyyy-MM-dd") }
            schedule.dateField("EndDate") { date -> date.format("yyyy-MM-dd") }
            schedule.timeField("StartTime") { time -> time.format("HH:mm:ss") }
            schedule.dateTimeField("LoggedAt") { dateTime -> dateTime.format("yyyy-MM-dd'T'HH:mm:ss") }
            schedule.numberField("DateRound") { number -> number.fractionDigits(2, 2) }
            schedule.numberField("TimeRound") { number -> number.fractionDigits(0) }
            schedule.numberField("DateTimeRound") { number -> number.fractionDigits(1, 1) }
            schedule.numberField("BaseMagnitude") { number -> number.fractionDigits(0) }
            schedule.numberField("DirectBaseRound") { number -> number.fractionDigits(0) }
            schedule.numberField("DirectBaseMagnitude") { number -> number.fractionDigits(0) }
            schedule.numberField("GapMagnitude") { number -> number.fractionDigits(0) }
            schedule.numberField("Followup") { number -> number.fractionDigits(0) }
        }
        .build().toJson()
    private val bound = BoundModel.ofJson(base)
    private val dateComponent = Dsl.monthFromDate(bound.date(START_DATE))
    private val timeComponent = Dsl.hoursFromTime(bound.time(START_TIME))
    private val dateTimeComponent = Dsl.monthFromDate(bound.dateTime(LOGGED_AT))
    private val baseYearComponent = Dsl.yearFromDate(Dsl.baseYear())
    private val signedDifference = Dsl.differenceInMonths(bound.date(START_DATE), bound.date(END_DATE))
    private val rules = listOf(
        rule("DATE_UP", Dsl.roundUp(dateComponent, 2).eq("5.00"), START_DATE),
        rule("TIME_DOWN", Dsl.roundDown(timeComponent).eq(18), START_TIME),
        rule("DATETIME_ACCOUNTING", Dsl.roundAccounting(dateTimeComponent, 1).eq("7.0"), LOGGED_AT),
        rule("BASE_ABS", bound.field(ANCHOR).filled().and(Dsl.abs(baseYearComponent).eq(2020)), ANCHOR),
        rule("DIRECT_BASE_ROUND", bound.field(ANCHOR).filled().and(Dsl.roundDown(Dsl.baseYearNumber()).eq(2020)), ANCHOR),
        rule("DIRECT_BASE_ABS", bound.field(ANCHOR).filled().and(Dsl.abs(Dsl.baseYearNumber()).eq(2020)), ANCHOR),
        rule("DIFF_ABS", Dsl.abs(signedDifference).eq(2), END_DATE),
        rule("DATE_ROUND_GE", Dsl.roundDown(dateComponent).ge(0), START_DATE),
        rule("DATE_ROUND_LE", Dsl.roundDown(dateComponent).le(0), START_DATE),
        rule("DATE_ABS_GE", Dsl.abs(dateComponent).ge(0), START_DATE),
        rule("DATE_ABS_LE", Dsl.abs(dateComponent).le(0), START_DATE),
    )
    private val computations = listOf(
        computation("DateRoundProjection", DATE_ROUND, Dsl.roundUp(dateComponent, 2)),
        computation("TimeRoundProjection", TIME_ROUND, Dsl.roundDown(timeComponent)),
        computation("DateTimeRoundProjection", DATE_TIME_ROUND, Dsl.roundAccounting(dateTimeComponent, 1)),
        computation("BaseMagnitudeProjection", BASE_MAGNITUDE, Dsl.abs(baseYearComponent)),
        computation("DirectBaseRoundProjection", DIRECT_BASE_ROUND, Dsl.roundDown(Dsl.baseYearNumber())),
        computation("DirectBaseMagnitudeProjection", DIRECT_BASE_MAGNITUDE, Dsl.abs(Dsl.baseYearNumber())),
        computation("GapMagnitudeProjection", GAP_MAGNITUDE, Dsl.abs(signedDifference)),
        computation("FollowupProjection", FOLLOWUP, bound.num(GAP_MAGNITUDE).asNumber().plus(1)),
    )
    private val modelJson = computations.fold(
        rules.fold(base) { json, rule -> TypedRules.inject(json, rule) },
    ) { json, computation -> TypedRules.inject(json, computation) }
    private val model = ModelLoader.load(modelJson)
    private val staticEngine by lazy { RuntimeLaws.kernelJavaEngine(modelJson) }

    private fun rule(code: String, condition: Condition, errorPath: String): Rule =
        bound.rule(bound.group(ROOT)).name("${code}Rule").errorField(bound.field(errorPath)).`when`(condition)
            .code(code).severity(Severity.ERROR).message(Dsl.en(code)).build()

    private fun computation(name: String, target: String, value: NumberExpr) =
        bound.computation(bound.num(target)).name(name).calc(value.expression())
            .message(Dsl.en("Wrapped temporal number.")).build()

    @Test
    fun storedBaseYearAndSignedDifferenceSourcesComposeBeforeTheirWrappers() {
        val values = TemporalValues(
            startDate = "2020-05-31",
            endDate = "2020-03-31",
            startTime = "18:30:00",
            loggedAt = "2022-07-04T09:15:00",
        )
        assertSignals(
            values,
            listOf(
                "BASE_ABS", "DATETIME_ACCOUNTING", "DATE_ABS_GE", "DATE_ROUND_GE", "DATE_UP", "DIFF_ABS",
                "DIRECT_BASE_ABS", "DIRECT_BASE_ROUND", "TIME_DOWN",
            ),
            "present sources",
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
            compute(values, staleTargets = false),
        )
    }

    @Test
    fun emptyStoredComponentKeepsRoundingSymmetricButAbsMakesMagnitudeGrowOnly() {
        assertSignals(
            TemporalValues(),
            listOf(
                "BASE_ABS",
                "DATE_ABS_GE",
                "DATE_ABS_LE:OMISSION",
                "DATE_ROUND_GE:OMISSION",
                "DATE_ROUND_LE:OMISSION",
                "DIRECT_BASE_ABS",
                "DIRECT_BASE_ROUND",
            ),
            "empty temporal sources",
        )
    }

    @Test
    fun formalInvalidityPoisonsOnlyTheAffectedTemporalComputationsAndDependentRead() {
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
            compute(
                TemporalValues("invalid", "invalid", "invalid", "invalid"),
                staleTargets = true,
            ),
        )
    }

    private fun assertSignals(values: TemporalValues, expected: List<String>, label: String) {
        val kernelDocument = kernelDocument(values, staleTargets = false)
        val dynamic = RuntimeLaws.messages(modelJson, kernelDocument).filter { it.code() in CODES }
            .map { "${it.code()}${if (it.type().name == "OMISSION_ERROR") ":OMISSION" else ""}" }.sorted()
        val static = staticEngine.messages(staticEngine.instance(kernelDocument)).filter { it.code() in CODES }
            .map { "${it.code()}${if (it.type().name == "OMISSION_ERROR") ":OMISSION" else ""}" }.sorted()
        val interpreter = DmInterpreter(model).validateFull(interpreterDocument(values, staleTargets = false))
            .filter { it.code in CODES }
            .map { "${it.code}${if (it.type == MsgType.OMISSION) ":OMISSION" else ""}" }.sorted()

        assertEquals(expected.sorted(), dynamic, "$label — normative dynamic-Groovy")
        assertEquals(dynamic, static, "$label — generated Java")
        assertEquals(dynamic, interpreter, "$label — interpreter")
    }

    private fun compute(values: TemporalValues, staleTargets: Boolean): List<String> {
        val kernelDocument = kernelDocument(values, staleTargets)
        fun outcome(path: String, value: String?, cleared: Boolean, errored: Boolean): String = when {
            cleared -> "$path|CLEARED"
            errored -> "$path|ERRORED"
            else -> "$path|VALUE|$value"
        }
        val dynamic = RuntimeLaws.compute(modelJson, kernelDocument).filter { it.path() in TARGETS }
            .map { outcome(it.path(), it.value().orElse(null), it.cleared(), it.errored()) }.sorted()
        val static = staticEngine.compute(staticEngine.instance(kernelDocument)).filter { it.path() in TARGETS }
            .map { outcome(it.path(), it.value().orElse(null), it.cleared(), it.errored()) }.sorted()
        val interpreter = DmInterpreter(model).compute(interpreterDocument(values, staleTargets)).filter { it.path in TARGETS }.map {
            when (it.outcome) {
                ComputeOutcome.VALUE -> "${it.path}|VALUE|${it.value}"
                ComputeOutcome.CLEARED -> "${it.path}|CLEARED"
                ComputeOutcome.ERRORED -> "${it.path}|ERRORED"
            }
        }.sorted()

        assertEquals(dynamic, static, "generated Java temporal-wrapper computations")
        assertEquals(dynamic, interpreter, "interpreter temporal-wrapper computations")
        return dynamic
    }

    private fun kernelDocument(values: TemporalValues, staleTargets: Boolean): RuntimeLaws.Doc {
        val document = RuntimeLaws.doc().group(ROOT, 1).field(ANCHOR, "active", 1, 1)
        values.startDate?.let { document.field(START_DATE, it, 1, 1) }
        values.endDate?.let { document.field(END_DATE, it, 1, 1) }
        values.startTime?.let { document.field(START_TIME, it, 1, 1) }
        values.loggedAt?.let { document.field(LOGGED_AT, it, 1, 1) }
        if (staleTargets) TARGETS.forEach { document.field(it, "9", 1, 1) }
        return document
    }

    private fun interpreterDocument(values: TemporalValues, staleTargets: Boolean) =
        model.newDocument().group(ROOT, 1).field(ANCHOR, "active", 1, 1).also { document ->
            values.startDate?.let { document.field(START_DATE, it, 1, 1) }
            values.endDate?.let { document.field(END_DATE, it, 1, 1) }
            values.startTime?.let { document.field(START_TIME, it, 1, 1) }
            values.loggedAt?.let { document.field(LOGGED_AT, it, 1, 1) }
            if (staleTargets) TARGETS.forEach { document.field(it, "9", 1, 1) }
        }

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
