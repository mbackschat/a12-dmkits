package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.DatePrecision
import io.github.mbackschat.a12.dm.interpreter.model.EvalClock
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.ModelTimeZone
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals

/** JVM + Node lock for the reason-preserving temporal extractor/construction path (IF235). */
class TemporalExtractorComponentTest {

    private val definition = ModelDef(
        fields = listOf(
            FieldDef(ORDER_DATE, Kind.DATE, format = "yyyy-MM-dd"),
            FieldDef(DELIVERY_DATE, Kind.DATE, format = "yyyy-MM-dd"),
            FieldDef(PICKUP_TIME, Kind.TIME, format = "HH:mm:ss"),
            FieldDef(SCHEDULED_AT, Kind.DATE_TIME, format = "yyyy-MM-dd'T'HH:mm:ss"),
            FieldDef(COMPLETED_AT, Kind.DATE_TIME, format = "yyyy-MM-dd'T'HH:mm:ss"),
            FieldDef(APPROX_DATE, Kind.DATE, format = "dd.MM.yyyy", datePrecision = DatePrecision.DAY_OPTIONAL),
            FieldDef(QUANTITY, Kind.NUMBER, scale = 0),
            FieldDef(BIRTH_MONTH, Kind.NUMBER, scale = 0),
        ),
        rules = listOf(
            rule(
                DATE_EQUAL,
                "../OrderDate",
                "[$DELIVERY_DATE] == Date(DayFromDate($ORDER_DATE), \"01\", YearFromDate($SCHEDULED_AT))",
            ),
            rule(
                DATE_INVALID,
                "../OrderDate",
                "Invalid(Date(DayFromDate($ORDER_DATE), $BIRTH_MONTH, YearFromDate($SCHEDULED_AT)))",
            ),
            rule(
                TIME_TRANSITION,
                "../PickupTime",
                "[$PICKUP_TIME] == Time(HoursFromTime(AddHours($SCHEDULED_AT, 1)), \"30\", \"00\")",
            ),
            rule(
                TIME_EMPTY,
                "../PickupTime",
                "[$PICKUP_TIME] == Time(HoursFromTime($COMPLETED_AT))",
            ),
            rule(
                TIME_DOMAIN,
                "../PickupTime",
                "[$PICKUP_TIME] == Time(HoursFromTime(AddHours($SCHEDULED_AT, [$QUANTITY] / 0)))",
            ),
            rule(
                TIME_NOW,
                "../PickupTime",
                "[$PICKUP_TIME] == Time(HoursFromTime(AddHours(Now, [$QUANTITY])))",
            ),
            rule(
                WALL_CLOCK,
                "../CompletedAt",
                "[$COMPLETED_AT] == DateTime(ValueAsDate($APPROX_DATE, LastDay), TimeFromDateTime($SCHEDULED_AT))",
            ),
        ),
        timeZone = ModelTimeZone.EUROPE_BERLIN,
    )

    @Test
    fun dateExtractorSourcesKeepEmptyAndFormalReasons() {
        assertEquals(
            listOf(DATE_EQUAL to MsgType.VALUE),
            messages(
                mapOf(
                    ORDER_DATE to "2024-06-15",
                    BIRTH_MONTH to "1",
                    SCHEDULED_AT to "2024-07-20T10:30:45",
                    DELIVERY_DATE to "2024-01-15",
                ),
                setOf(DATE_EQUAL, DATE_INVALID),
            ),
        )
        assertEquals(
            listOf(DATE_INVALID to MsgType.OMISSION),
            messages(
                mapOf(
                    BIRTH_MONTH to "1",
                    SCHEDULED_AT to "2024-07-20T10:30:45",
                    DELIVERY_DATE to "2024-01-15",
                ),
                setOf(DATE_EQUAL, DATE_INVALID),
                empty = setOf(ORDER_DATE),
            ),
        )
        assertEquals(
            emptyList(),
            messages(
                mapOf(
                    ORDER_DATE to "not-a-date",
                    BIRTH_MONTH to "1",
                    SCHEDULED_AT to "2024-07-20T10:30:45",
                    DELIVERY_DATE to "2024-01-15",
                ),
                setOf(DATE_EQUAL, DATE_INVALID),
            ),
        )
    }

    @Test
    fun timeExtractorSourcesSeparateMissingFromReachedValueless() {
        assertEquals(
            listOf(TIME_TRANSITION to MsgType.VALUE),
            messages(
                mapOf(PICKUP_TIME to "03:30:00", SCHEDULED_AT to "2024-03-31T01:30:00"),
                setOf(TIME_TRANSITION),
            ),
        )
        assertEquals(
            emptyList(),
            messages(mapOf(PICKUP_TIME to "00:00:00"), setOf(TIME_EMPTY), empty = setOf(COMPLETED_AT)),
        )
        assertEquals(
            listOf(TIME_DOMAIN to MsgType.VALUE),
            messages(
                mapOf(PICKUP_TIME to "00:00:00", SCHEDULED_AT to "2024-06-15T10:30:45", QUANTITY to "1"),
                setOf(TIME_DOMAIN),
            ),
        )
    }

    @Test
    fun changingNowIsEvaluatedBeforeItsExtractor() {
        assertEquals(
            listOf(TIME_NOW to MsgType.VALUE),
            messages(
                mapOf(PICKUP_TIME to "03:00:00", QUANTITY to "1"),
                setOf(TIME_NOW),
                clock = EvalClock("2024-03-31", "2024-03-31T01:30:00"),
            ),
        )
        assertEquals(
            emptyList(),
            messages(
                mapOf(PICKUP_TIME to "03:00:00", QUANTITY to "1"),
                setOf(TIME_NOW),
                clock = EvalClock("2024-03-31", "2024-03-31T04:30:00"),
            ),
        )
    }

    @Test
    fun timeFromDateTimeProjectsWallClockBesideResolvedPartialDate() {
        assertEquals(
            listOf(WALL_CLOCK to MsgType.VALUE),
            messages(
                mapOf(
                    APPROX_DATE to "00.06.2024",
                    SCHEDULED_AT to "2024-06-15T10:30:45",
                    COMPLETED_AT to "2024-06-30T10:30:45",
                ),
                setOf(WALL_CLOCK),
            ),
        )
        assertEquals(
            emptyList(),
            messages(
                mapOf(APPROX_DATE to "00.06.2024", COMPLETED_AT to "2024-06-30T10:30:45"),
                setOf(WALL_CLOCK),
                empty = setOf(SCHEDULED_AT),
            ),
        )
        assertEquals(
            emptyList(),
            messages(
                mapOf(
                    APPROX_DATE to "bad-date",
                    SCHEDULED_AT to "2024-06-15T10:30:45",
                    COMPLETED_AT to "2024-06-30T10:30:45",
                ),
                setOf(WALL_CLOCK),
            ),
        )
    }

    private fun messages(
        values: Map<String, String>,
        relevant: Set<String>,
        empty: Set<String> = emptySet(),
        clock: EvalClock = EvalClock("2024-06-15", "2024-06-15T12:00:00"),
    ): List<Pair<String, MsgType>> {
        val document = definition.newDocument().group(ROOT, 1).also { doc ->
            values.forEach { (path, value) -> doc.field(path, value, 1, 1) }
            empty.forEach { path -> doc.emptyField(path, 1, 1) }
        }
        return DmInterpreter(definition, clock).validateFull(document)
            .filter { it.code in relevant }
            .map { it.code to it.type }
            .sortedBy { it.first }
    }

    private companion object {
        const val ROOT = "/Order"
        const val ORDER_DATE = "$ROOT/OrderDate"
        const val DELIVERY_DATE = "$ROOT/DeliveryDate"
        const val PICKUP_TIME = "$ROOT/PickupTime"
        const val SCHEDULED_AT = "$ROOT/ScheduledAt"
        const val COMPLETED_AT = "$ROOT/CompletedAt"
        const val APPROX_DATE = "$ROOT/ApproxDate"
        const val QUANTITY = "$ROOT/Quantity"
        const val BIRTH_MONTH = "$ROOT/BirthMonth"

        const val DATE_EQUAL = "EXTRACT_DATE_EQUAL"
        const val DATE_INVALID = "EXTRACT_DATE_INVALID"
        const val TIME_TRANSITION = "EXTRACT_TIME_TRANSITION"
        const val TIME_EMPTY = "EXTRACT_TIME_EMPTY"
        const val TIME_DOMAIN = "EXTRACT_TIME_DOMAIN"
        const val TIME_NOW = "EXTRACT_TIME_NOW"
        const val WALL_CLOCK = "TIME_FROM_DATETIME_WALL"

        fun rule(code: String, errorRelPath: String, condition: String): RuleDef =
            RuleDef("$ROOT/$code", ROOT, condition, code, errorRelPath)
    }
}
