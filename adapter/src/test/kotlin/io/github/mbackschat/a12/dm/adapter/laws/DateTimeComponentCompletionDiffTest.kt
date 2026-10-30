package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.DocumentDifferential.underUtc
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.interpreter.model.EvalClock
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Date

/**
 * A component-omitting Date argument of `DateTime(date, time)` completes to its earliest day (KERNEL-FINDINGS KF627,
 * a12-kernel-lean SPEC-2026-10-30-06): `yyyy` to January 1, `yyyy-MM` to the first of its month, and a yearless
 * `MM-dd` or `MM` in the Base Year, `MM` to the first. The completed label then resolves in the model's zone, so a
 * Berlin spring-gap label clears.
 *
 * The Base Year 2023 differs from the clock's 2026 and from the year-bearing arguments' 2022, so the yearless
 * argument's year is separated from both: Berlin's 2023 gap is March 26 and its 2026 gap March 29, and only the Base
 * Year clears `03-26` and stores `03-29` at 02:30. The UTC twin, differing in its zone alone, stores both. Every
 * document compares dynamic Groovy, static Java and the interpreter; the outcomes are pinned from dynamic Groovy. Own
 * domain (an insurance claim's reported date and clock).
 */
class DateTimeComponentCompletionDiffTest {

    private fun model(zone: String): String = underUtc {
        val session = EditSession.read(DocumentModelDraft.named("claim-stamp-completion").locale("en_US").baseYear(2023)
            .rootGroup("Claim") { c ->
                ARGUMENTS.forEach { (field, format) -> c.dateField(field) { it.format(format) } }
                c.timeField("Clock") { it.format("HH:mm:ss") }
                ARGUMENTS.keys.forEach { c.dateTimeField("Stamp$it") { d -> d.format(ISO_DT) } }
                c
            }.build().toJson())
        session.config().timeZone(zone)
        val claim = session.group("/Claim")
        for (argument in ARGUMENTS.keys) {
            claim.addComputation(claim.dateTime("Stamp$argument")) {
                it.name("Stamp${argument}Comp").calc(Dsl.dateTime(claim.date(argument), claim.time("Clock")))
                    .message(Dsl.en("Check the claim stamp.")).build()
            }
        }
        session.toJson()
    }

    private val berlin = model("Europe/Berlin")
    private val utc = model("UTC")

    private fun claim(clock: String, vararg dates: Pair<String, String>): String {
        val seeds = ARGUMENTS.keys.map { "\"Stamp$it\":\"2001-12-25T12:30:00\"" }
        val given = dates.map { (field, value) -> "\"$field\":\"$value\"" }
        return """{"Claim":{${(seeds + given + "\"Clock\":\"$clock\"").joinToString(",")}}}"""
    }

    private val documents = linkedMapOf(
        "noon" to claim("12:30:00", "Year" to "2022", "YearMonth" to "2022-03", "Month" to "03", "MonthDay" to "03-31"),
        "gap of the Base Year" to claim("02:30:00", "Year" to "2022", "YearMonth" to "2022-03", "Month" to "03", "MonthDay" to "03-26"),
        "gap of the clock's year" to claim("02:30:00", "MonthDay" to "03-29"),
    )

    @Test
    fun a12AndThePortableCheckerAdmitBothModels() {
        for (model in listOf(berlin, utc)) {
            assertEquals(emptyList<String>(), MessageParameterProbes.kernel(model))
            assertEquals(emptyList<String>(), rejectingCodes(model))
        }
    }

    @Test
    fun allThreeEnginesAgreeOnEveryDocument() {
        DocumentDifferential.assertAgree(berlin, documents, CLOCK)
        DocumentDifferential.assertAgree(utc, documents, CLOCK)
    }

    /** The year-bearing arguments keep their own year and the yearless ones take the Base Year, not the clock's. */
    @Test
    fun eachArgumentCompletesToItsEarliestDay() {
        val noon = mapOf("StampYear" to "2022-01-01T12:30:00", "StampYearMonth" to "2022-03-01T12:30:00",
            "StampMonth" to "2023-03-01T12:30:00", "StampMonthDay" to "2023-03-31T12:30:00")
        assertEquals(noon.stored(), outcomes(berlin, "noon"))
        assertEquals(noon.stored(), outcomes(utc, "noon"))
    }

    /** At 02:30 only the Base Year's gap clears in Berlin; the UTC twin stores every label. */
    @Test
    fun theCompletedLabelResolvesInTheModelsZone() {
        val gap = mapOf("StampYear" to "2022-01-01T02:30:00", "StampYearMonth" to "2022-03-01T02:30:00",
            "StampMonth" to "2023-03-01T02:30:00", "StampMonthDay" to "2023-03-26T02:30:00").stored()
        assertEquals(gap + ("StampMonthDay" to "CLEARED"), outcomes(berlin, "gap of the Base Year"))
        assertEquals(gap, outcomes(utc, "gap of the Base Year"))
        assertEquals("VALUE|2023-03-29T02:30:00", outcomes(berlin, "gap of the clock's year")["StampMonthDay"])
    }

    private fun Map<String, String>.stored() = mapValues { (_, stamp) -> "VALUE|$stamp" }

    private fun outcomes(model: String, document: String): Map<String, String> =
        DocumentDifferential.kernel(model, documents.getValue(document), dynamic = true, CLOCK).outcomes
            .mapKeys { (key, _) -> key.substringAfterLast('/') }

    private companion object {
        const val ISO_DT = "yyyy-MM-dd'T'HH:mm:ss"
        val ARGUMENTS = linkedMapOf("Year" to "yyyy", "YearMonth" to "yyyy-MM", "Month" to "MM", "MonthDay" to "MM-dd")

        /** A clock in 2026, so a yearless argument completed in the clock's year would differ from the Base Year 2023. */
        val CLOCK = DifferentialClock(Date.from(Instant.parse("2026-10-04T10:00:00Z")),
            EvalClock(today = "2026-10-04", now = "2026-10-04T12:00:00"))
    }
}
