package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.ModelDocument
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * `DateFromDateTime` reports the source label's OWN date — the wall date the document states — with the time
 * dropped and no conversion to the instant's UTC day (a12-kernel-lean `SPEC-2026-08-23-13`, [KF194]).
 *
 * **Why this file exists beside `DateTimeExtractDiffTest`.** That neighbour already reads year, month, and day
 * out of a `DateFromDateTime`, but at a MIDDAY label (`10:30:45`), where the wall date and the instant's UTC
 * date are the same day at every ordinary zone offset. It therefore cannot separate the two readings, and the
 * rival — resolve the retained instant and take ITS date — is the natural implementation and the one that
 * silently produces an off-by-one-day answer. Only a label within the zone offset of midnight discriminates.
 *
 * **The variable is controlled here rather than ambient.** The model declares `Europe/Berlin`, so a stored
 * `2024-06-15T00:30:00` is the instant `2024-06-14T22:30:00Z`: the two accounts predict different days and the
 * fixture names both as rival rules, so a row is informative in both directions rather than by non-firing
 * alone. (The upstream measurement had to record its `+0200` host zone as observed rather than chosen; this
 * fixture chooses it, which also removes that entry's stated scope limit.)
 *
 * The two control rows are what make the separator readable: a MIDDAY label fires both rules, proving the time
 * is dropped rather than the wall rule being right by accident, and a row whose two candidate dates match
 * neither reading fires nothing, proving a silent row is a real negative.
 */
class DateFromDateTimeWallDateDiffTest {

    /** The near-midnight separator: at Berlin the instant's UTC day is the PREVIOUS one. */
    @Test
    fun aNearMidnightLabelReadsItsOwnWallDateAndNotTheInstantsUtcDay() {
        assertFired(BERLIN, "2024-06-15T00:30:00", wall = "2024-06-15", utc = "2024-06-14", expected = setOf(WALL))
    }

    /** Midday: both candidates are the same day, so both rules fire — the positive control for the separator. */
    @Test
    fun aMiddayLabelDropsTheTimeAndMatchesBothCandidates() {
        assertFired(BERLIN, "2024-06-15T12:00:00", wall = "2024-06-15", utc = "2024-06-15",
            expected = setOf(WALL, UTC))
    }

    /** Neither candidate is the extracted date — a silent row here is a measured negative, not a vacuous one. */
    @Test
    fun aRowMatchingNeitherCandidateFiresNothing() {
        assertFired(BERLIN, "2024-06-15T00:30:00", wall = "2024-01-01", utc = "2024-01-02", expected = emptySet())
    }

    /** The same separator under UTC, where the two readings coincide — so the Berlin row's split is the ZONE. */
    @Test
    fun underUtcTheNearMidnightLabelMatchesBothCandidates() {
        assertFired(UTC_ZONE, "2024-06-15T00:30:00", wall = "2024-06-15", utc = "2024-06-15",
            expected = setOf(WALL, UTC))
    }

    private fun assertFired(zone: String, moment: String, wall: String, utc: String, expected: Set<String>) {
        val fixture = fixture(zone)
        val document = RuntimeLaws.doc().group(ROOT, 1)
            .field(MOMENT, moment, 1, 1)
            .field(WALL_DATE, wall, 1, 1)
            .field(UTC_DATE, utc, 1, 1)
        val dynamic = ours(RuntimeLaws.messages(fixture.modelJson, document).map { it.code() })
        val generated = ours(fixture.javaEngine.messages(fixture.javaEngine.instance(document)).map { it.code() })
        val clue = "$zone $moment (wall=$wall utc=$utc)"
        assertEquals(expected, dynamic, "$clue — dynamic-Groovy")
        assertEquals(dynamic, generated, "$clue — generated Java")

        val definition = ModelLoader.load(fixture.modelJson)
        val interpreted = ours(
            DmInterpreter(definition).validateFull(
                definition.newDocument().group(ROOT, 1)
                    .field(MOMENT, moment, 1, 1)
                    .field(WALL_DATE, wall, 1, 1)
                    .field(UTC_DATE, utc, 1, 1),
            ).map { it.code },
        )
        assertEquals(dynamic, interpreted, "$clue — interpreter")
    }

    private fun fixture(zone: String): Fixture {
        var model = BoundModel.ofJson(ModelDocument.read(BASE).setTimeZone(zone).toJson())
        val moment = model.dateTime(MOMENT)
        for ((code, candidate) in listOf(WALL to WALL_DATE, UTC to UTC_DATE)) {
            val extracted = Dsl.dateFromDateTime(moment)
            model = model.add(
                model.rule(model.group(ROOT)).name(code)
                    .errorField(moment.field())
                    .`when`(moment.filled().and(extracted.eq(model.date(candidate))))
                    .code(code).severity(Severity.ERROR).message(Dsl.en("extracted date is $candidate"))
                    .build(),
            )
        }
        val modelJson = model.toJson()
        return Fixture(modelJson, RuntimeLaws.kernelJavaEngine(modelJson))
    }

    private fun ours(codes: List<String>): Set<String> = codes.filter { it == WALL || it == UTC }.toSet()

    private data class Fixture(val modelJson: String, val javaEngine: RuntimeLaws.JavaKernelEngine)

    private companion object {
        const val ROOT = "/Booking"
        const val MOMENT = "$ROOT/CheckedInAt"
        const val WALL_DATE = "$ROOT/WallDate"
        const val UTC_DATE = "$ROOT/UtcDate"
        const val WALL = "MATCHES_WALL_DATE"
        const val UTC = "MATCHES_UTC_DATE"
        const val BERLIN = "Europe/Berlin"
        const val UTC_ZONE = "UTC"

        val BASE: String = DocumentModelDraft.named("booking").locale("en_US")
            .rootGroup("Booking") { group ->
                group.dateTimeField("CheckedInAt") { }
                group.dateField("WallDate") { it.format("yyyy-MM-dd") }
                group.dateField("UtcDate") { it.format("yyyy-MM-dd") }
            }
            .build()
            .toJson()
    }
}
