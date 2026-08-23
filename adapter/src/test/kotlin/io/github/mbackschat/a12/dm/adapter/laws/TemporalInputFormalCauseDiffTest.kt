package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.modelkit.DatePrecision
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — which FORMAL CAUSE a rejected temporal input carries (SPEC-2026-08-23-08 and -11).
 *
 * **The two-stage rule for a partial Date.** A FULL-precision declaration reports *any* omission marker as
 * `datumFormatFalsch`, because an omission is not a legal spelling for it at all — so the same stored text
 * draws different answers from two declarations differing only in precision. A declaration that admits
 * omissions splits its rejections instead: the format code for a wrong component width and for an unreal
 * calendar date among the value's PRESENT components, and the date code for a zero pattern that is not a
 * monotone suffix, for a suffix deeper than the precision admits, and for an admitted value whose EARLIEST
 * COMPLETION falls below the Gregorian floor.
 *
 * **The gate order is reality, then position in time, then precision**, and
 * [anImpossibleMonthBesideAnOmittedDayIsTheFormatError] is what measures it: at `DAY_OPTIONAL` the omission
 * is admitted and the month is not, so the month's cause wins. An implementation that obtains its partial
 * value through a constructor applying the floor internally reports the adjacent floor case as a *format*
 * error, because the constructor's "no value" is indistinguishable from unreality.
 *
 * **Time and DateTime have no codes of their own.** Both report through the DATE codes; a Time cell reaches
 * only the format one — every out-of-range component and every width violation alike — because a clock has no
 * position in time, while a DateTime cell reaches both and carries the Date floor.
 *
 * Own domain (insurance claim intake).
 */
class TemporalInputFormalCauseDiffTest {

    private val json = DocumentModelDraft.named("intake").locale("en_US")
        .rootGroup("Intake") { intake ->
            intake.dateField("FullDate") { it.format("yyyy-MM-dd") }
            intake.dateField("DayOptional") { it.format("yyyy-MM-dd").datePrecision(DatePrecision.DAY_OPTIONAL) }
            intake.dateField("MonthOptional") {
                it.format("yyyy-MM-dd").datePrecision(DatePrecision.MONTH_OPTIONAL)
            }
            intake.timeField("ClockTime") { it.format("HH:mm:ss") }
            intake.dateTimeField("Stamp") { it.format("yyyy-MM-dd'T'HH:mm:ss") }
        }.build().toJson()

    private val definition = ModelLoader.load(json)

    /** The formal codes the two kernel strategies and the interpreter report for one stored cell. */
    private fun causeOf(field: String, value: String): String {
        val path = "$ROOT/$field"
        val kernelDocument = RuntimeLaws.doc().group(ROOT, 1).field(path, value, 1, 1)
        val dynamic = RuntimeLaws.messages(json, kernelDocument).map { it.code() }.sorted()
        val engine = RuntimeLaws.kernelJavaEngine(json)
        val generated = engine.messages(engine.instance(kernelDocument)).map { it.code() }.sorted()
        val document = definition.newDocument().group(ROOT, 1).field(path, value, 1, 1)
        val interpreted = DmInterpreter(definition).validateFull(document).map { it.code }.sorted()

        assertEquals(dynamic, generated, "generated Java disagrees ($field = \"$value\")")
        assertEquals(dynamic, interpreted, "the interpreter disagrees ($field = \"$value\")")
        return if (dynamic.isEmpty()) ADMITTED else dynamic.joinToString(",")
    }

    /**
     * The precision pair. One stored text, two declarations differing ONLY in precision: the omission-admitting
     * one accepts it and the full-precision one calls it a FORMAT error, not a date error — an omission is not
     * a legal spelling for a full-precision declaration at all.
     */
    @Test
    fun fullPrecisionReportsAnyOmissionMarkerAsAFormatError() {
        val actual = linkedMapOf(
            "day omitted at FULL" to causeOf("FullDate", "2024-02-00"),
            "the same text at DAY_OPTIONAL" to causeOf("DayOptional", "2024-02-00"),
            "day and month omitted at FULL" to causeOf("FullDate", "2024-00-00"),
            "the same text at MONTH_OPTIONAL" to causeOf("MonthOptional", "2024-00-00"),
        )
        val expected = linkedMapOf(
            "day omitted at FULL" to FORMAT,
            "the same text at DAY_OPTIONAL" to ADMITTED,
            "day and month omitted at FULL" to FORMAT,
            "the same text at MONTH_OPTIONAL" to ADMITTED,
        )
        assertEquals(expected, actual, "precision is the only variable, and it changes the cause")
    }

    /** An omission-admitting declaration splits its rejections between the two codes. */
    @Test
    fun anOmissionAdmittingDeclarationSplitsItsRejections() {
        val actual = linkedMapOf(
            "wrong component width" to causeOf("DayOptional", "2024-2-01"),
            "unreal calendar date among the PRESENT components" to causeOf("DayOptional", "2024-02-31"),
            "a zero pattern that is not a monotone suffix" to causeOf("MonthOptional", "2024-00-15"),
            "a suffix deeper than the precision admits" to causeOf("DayOptional", "2024-00-00"),
            "a real date at the same precision — control" to causeOf("DayOptional", "2024-02-15"),
        )
        val expected = linkedMapOf(
            "wrong component width" to FORMAT,
            "unreal calendar date among the PRESENT components" to FORMAT,
            "a zero pattern that is not a monotone suffix" to DATE,
            "a suffix deeper than the precision admits" to DATE,
            "a real date at the same precision — control" to ADMITTED,
        )
        assertEquals(expected, actual, "width and unreality are format errors; the suffix rules are date errors")
    }

    /**
     * Reality, then position in time. The impossible month beside a legally omitted day is a FORMAT error —
     * the omission is admitted and the month is not — while the below-floor omitted day one month earlier is a
     * DATE error against its admitted neighbour. The two rows differ only in which stage rejects them.
     */
    @Test
    fun anImpossibleMonthBesideAnOmittedDayIsTheFormatError() {
        val actual = linkedMapOf(
            "impossible month, day legally omitted" to causeOf("DayOptional", "2024-13-00"),
            "earliest completion below the floor" to causeOf("DayOptional", "1583-10-00"),
            "the same shape one month later" to causeOf("DayOptional", "1583-11-00"),
        )
        val expected = linkedMapOf(
            "impossible month, day legally omitted" to FORMAT,
            "earliest completion below the floor" to DATE,
            "the same shape one month later" to ADMITTED,
        )
        assertEquals(expected, actual, "unreality outranks position in time, which outranks the precision rules")
    }

    /** A Time cell reaches only the format code — a clock has no position in time — and hour 24 is not legal. */
    @Test
    fun aStoredTimeCarriesOnlyTheFormatCodeAndHasNoHour24() {
        val actual = linkedMapOf(
            "00:00:00" to causeOf("ClockTime", "00:00:00"),
            "23:59:59" to causeOf("ClockTime", "23:59:59"),
            "24:00:00" to causeOf("ClockTime", "24:00:00"),
            "25:00:00" to causeOf("ClockTime", "25:00:00"),
            "12:60:00 — minute out of range" to causeOf("ClockTime", "12:60:00"),
            "12:00:60 — second out of range" to causeOf("ClockTime", "12:00:60"),
            "1:00:00 — width violation" to causeOf("ClockTime", "1:00:00"),
        )
        val expected = linkedMapOf(
            "00:00:00" to ADMITTED,
            "23:59:59" to ADMITTED,
            "24:00:00" to FORMAT,
            "25:00:00" to FORMAT,
            "12:60:00 — minute out of range" to FORMAT,
            "12:00:60 — second out of range" to FORMAT,
            "1:00:00 — width violation" to FORMAT,
        )
        assertEquals(expected, actual, "hour 24 is refused exactly as 25 is; every Time rejection is the format code")
    }

    /** A DateTime cell reaches BOTH codes: it carries the Date floor, and its `'T'` is part of the format. */
    @Test
    fun aStoredDateTimeCarriesBothCodesAndTheDateFloor() {
        val actual = linkedMapOf(
            "the floor day" to causeOf("Stamp", "1583-10-16T00:00:00"),
            "the day before it" to causeOf("Stamp", "1583-10-15T00:00:00"),
            "a space instead of the literal T" to causeOf("Stamp", "2024-02-15 10:00:00"),
            "an ordinary instant — control" to causeOf("Stamp", "2024-02-15T10:00:00"),
        )
        val expected = linkedMapOf(
            "the floor day" to ADMITTED,
            "the day before it" to DATE,
            "a space instead of the literal T" to FORMAT,
            "an ordinary instant — control" to ADMITTED,
        )
        assertEquals(expected, actual, "the DateTime floor is the Date floor, and 'T' is format not whitespace")
    }

    private companion object {
        const val ROOT = "/Intake"
        const val ADMITTED = "ADMITTED"
        const val FORMAT = "datumFormatFalsch"
        const val DATE = "datumFalsch"
    }
}
