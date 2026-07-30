package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.EvalClock
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
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
 * Runtime half of SPEC-2026-07-27-12/-09/-07. The typed conditions deliberately nest matching extractors
 * inside Date/Time constructors, so the differential observes the source-before-extractor order and preserves
 * the distinction between an empty source, a formally unavailable source, and a reached valueless expression.
 * Dynamic Groovy is normative; generated Java and the interpreter are checked independently.
 */
class TemporalExtractorComponentDiffTest {

    private val baseJson = RuntimeLaws.model("models/order.dm.json")
    private val model = BoundModel.ofJson(baseJson)
    private val dateFromExtractors = Dsl.date(
        Dsl.dayFromDate(model.date(ORDER_DATE)),
        Dsl.temporalComponent("01"),
        Dsl.yearFromDate(model.dateTime(SCHEDULED_AT)),
    )
    private val dateForValidity = Dsl.date(
        Dsl.dayFromDate(model.date(ORDER_DATE)),
        model.num(BIRTH_MONTH).asNumber(),
        Dsl.yearFromDate(model.dateTime(SCHEDULED_AT)),
    )
    private val transitionTime = Dsl.time(
        Dsl.hoursFromTime(Dsl.addHours(model.dateTime(SCHEDULED_AT), 1)),
        Dsl.temporalComponent("30"),
        Dsl.temporalComponent("00"),
    )
    private val emptySourceTime = Dsl.time(Dsl.hoursFromTime(model.dateTime(COMPLETED_AT)))
    private val domainFailureTime = Dsl.time(
        Dsl.hoursFromTime(
            Dsl.addHours(model.dateTime(SCHEDULED_AT), model.num(QUANTITY).asNumber().dividedBy(Dsl.number(0))),
        ),
    )
    private val dynamicTime = Dsl.time(
        Dsl.hoursFromTime(Dsl.addHours(Dsl.now(), model.num(QUANTITY).asNumber())),
    )
    private val resolvedDateTime = Dsl.dateTime(
        Dsl.valueAsDateLastDay(model.field(APPROX_DATE)),
        Dsl.timeFromDateTime(model.dateTime(SCHEDULED_AT)),
    )
    private val authoredModel = model.addAll(
        listOf(
            rule(DATE_EQUAL, ORDER_DATE, model.date(DELIVERY_DATE).eq(dateFromExtractors)),
            rule(DATE_INVALID, ORDER_DATE, Dsl.invalid(dateForValidity)),
            rule(TIME_TRANSITION, PICKUP_TIME, model.time(PICKUP_TIME).eq(transitionTime)),
            rule(TIME_EMPTY, PICKUP_TIME, model.time(PICKUP_TIME).eq(emptySourceTime)),
            rule(TIME_DOMAIN, PICKUP_TIME, model.time(PICKUP_TIME).eq(domainFailureTime)),
            rule(TIME_NOW, PICKUP_TIME, model.time(PICKUP_TIME).eq(dynamicTime)),
            rule(WALL_CLOCK, COMPLETED_AT, model.dateTime(COMPLETED_AT).eq(resolvedDateTime)),
        ),
        emptyList(),
    )
    private val modelJson = authoredModel.toJson()
    private val definition = ModelLoader.load(modelJson)
    private val javaEngine = RuntimeLaws.kernelJavaEngine(modelJson)

    @Test
    fun mixedDateExtractorsEvaluateTheirSourcesBeforeConstruction() {
        check(
            values = mapOf(
                ORDER_DATE to "2024-06-15",
                BIRTH_MONTH to "1",
                SCHEDULED_AT to "2024-07-20T10:30:45",
                DELIVERY_DATE to "2024-01-15",
            ),
            expected = listOf("$DATE_EQUAL|VALUE"),
            relevant = setOf(DATE_EQUAL, DATE_INVALID),
            label = "mixed Date extractor/constant components",
        )
    }

    @Test
    fun anEmptyDateExtractorSourceKeepsOmissionWhileAFormalSourceSuppresses() {
        check(
            values = mapOf(
                SCHEDULED_AT to "2024-07-20T10:30:45",
                BIRTH_MONTH to "1",
                DELIVERY_DATE to "2024-01-15",
            ),
            empty = setOf(ORDER_DATE),
            expected = listOf("$DATE_INVALID|OMISSION"),
            relevant = setOf(DATE_EQUAL, DATE_INVALID),
            label = "empty Day extractor source",
        )
        check(
            values = mapOf(
                ORDER_DATE to "not-a-date",
                BIRTH_MONTH to "1",
                SCHEDULED_AT to "2024-07-20T10:30:45",
                DELIVERY_DATE to "2024-01-15",
            ),
            expected = emptyList(),
            relevant = setOf(DATE_EQUAL, DATE_INVALID),
            label = "formal Day extractor source",
        )
    }

    @Test
    fun nestedTimeExtractorsKeepMissingAndReachedValuelessSourcesDistinct() {
        check(
            values = mapOf(PICKUP_TIME to "03:30:00", SCHEDULED_AT to "2024-03-31T01:30:00"),
            expected = listOf("$TIME_TRANSITION|VALUE"),
            relevant = setOf(TIME_TRANSITION),
            label = "AddHours source crosses the Berlin gap before HoursFromTime",
        )
        check(
            values = mapOf(PICKUP_TIME to "00:00:00"),
            empty = setOf(COMPLETED_AT),
            expected = emptyList(),
            relevant = setOf(TIME_EMPTY),
            label = "empty DateTime source leaves Time incomplete",
        )
        check(
            values = mapOf(PICKUP_TIME to "00:00:00", SCHEDULED_AT to "2024-06-15T10:30:45", QUANTITY to "1"),
            expected = listOf("$TIME_DOMAIN|VALUE"),
            relevant = setOf(TIME_DOMAIN),
            label = "reached divide-by-zero shift projects to fixed zero",
        )
    }

    @Test
    fun nowIsEvaluatedAsTheChangingNestedSource() {
        val springClock = Instant.parse("2024-03-31T00:30:00Z")
        check(
            values = mapOf(PICKUP_TIME to "03:00:00", QUANTITY to "1"),
            expected = listOf("$TIME_NOW|VALUE"),
            relevant = setOf(TIME_NOW),
            label = "01:30 CET Now plus one hour projects 03",
            kernelClock = springClock,
            interpreterClock = EvalClock("2024-03-31", "2024-03-31T01:30:00"),
        )
        val laterClock = Instant.parse("2024-03-31T02:30:00Z")
        check(
            values = mapOf(PICKUP_TIME to "03:00:00", QUANTITY to "1"),
            expected = emptyList(),
            relevant = setOf(TIME_NOW),
            label = "changing Now changes the extracted hour",
            kernelClock = laterClock,
            interpreterClock = EvalClock("2024-03-31", "2024-03-31T04:30:00"),
        )
    }

    @Test
    fun timeFromDateTimeProjectsTheWallClockBesideAResolvedPartialDate() {
        check(
            values = mapOf(
                APPROX_DATE to "00.06.2024",
                SCHEDULED_AT to "2024-06-15T10:30:45",
                COMPLETED_AT to "2024-06-30T10:30:45",
            ),
            expected = listOf("$WALL_CLOCK|VALUE"),
            relevant = setOf(WALL_CLOCK),
            label = "LastDay date plus projected wall clock",
        )
        check(
            values = mapOf(APPROX_DATE to "00.06.2024", COMPLETED_AT to "2024-06-30T10:30:45"),
            empty = setOf(SCHEDULED_AT),
            expected = emptyList(),
            relevant = setOf(WALL_CLOCK),
            label = "empty TimeFromDateTime source stays empty",
        )
        check(
            values = mapOf(
                APPROX_DATE to "bad-date",
                SCHEDULED_AT to "2024-06-15T10:30:45",
                COMPLETED_AT to "2024-06-30T10:30:45",
            ),
            expected = emptyList(),
            relevant = setOf(WALL_CLOCK),
            label = "formal Date side short-circuits the outer construction",
        )
    }

    private fun rule(code: String, errorPath: String, condition: Condition): Rule =
        model.rule(model.group(ROOT))
            .name(code)
            .errorField(model.field(errorPath))
            .`when`(condition)
            .code(code)
            .severity(Severity.ERROR)
            .message(Dsl.en("probe"))
            .build()

    private fun check(
        values: Map<String, String>,
        empty: Set<String> = emptySet(),
        expected: List<String>,
        relevant: Set<String>,
        label: String,
        kernelClock: Instant? = null,
        interpreterClock: EvalClock = EvalClock("2024-06-15", "2024-06-15T12:00:00"),
    ) {
        val kernelDoc = RuntimeLaws.doc().group(ROOT, 1).also { doc ->
            values.forEach { (path, value) -> doc.field(path, value, 1, 1) }
            empty.forEach { path -> doc.emptyField(path, 1, 1) }
        }
        val clock = kernelClock?.let(Date::from)
        val dynamicMessages = if (clock == null) RuntimeLaws.messages(modelJson, kernelDoc)
            else RuntimeLaws.messagesModern(modelJson, kernelDoc, clock)
        val generatedMessages = if (clock == null) javaEngine.messages(javaEngine.instance(kernelDoc))
            else javaEngine.messagesModern(kernelDoc, clock)
        val dynamic = signatures(dynamicMessages, relevant)
        val generated = signatures(generatedMessages, relevant)

        val interpreterDoc = definition.newDocument().group(ROOT, 1).also { doc ->
            values.forEach { (path, value) -> doc.field(path, value, 1, 1) }
            empty.forEach { path -> doc.emptyField(path, 1, 1) }
        }
        val interpreted = DmInterpreter(definition, interpreterClock).validateFull(interpreterDoc)
            .mapNotNull { message ->
                if (message.code !in relevant) null
                else "${message.code}|${if (message.type == MsgType.OMISSION) "OMISSION" else "VALUE"}"
            }
            .sorted()

        assertEquals(expected, dynamic, "$label — dynamic Groovy")
        assertEquals(dynamic, generated, "$label — generated Java")
        assertEquals(dynamic, interpreted, "$label — interpreter")
    }

    private fun signatures(messages: List<RuntimeLaws.Msg>, relevant: Set<String>): List<String> =
        messages.mapNotNull { message ->
            if (message.code() !in relevant) null
            else "${message.code()}|${if (message.type().name == "OMISSION_ERROR") "OMISSION" else "VALUE"}"
        }.sorted()

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
    }
}
