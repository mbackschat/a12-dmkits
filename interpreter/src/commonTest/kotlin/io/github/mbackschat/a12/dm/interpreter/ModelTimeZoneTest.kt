package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.ModelTimeZone
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Multiplatform (JVM + JS) lock for the model time zone (`modelConfig.timeZone`, the IG62 close) — the PURE
 * interpreter (loader + [io.github.mbackschat.a12.dm.interpreter.eval.DateMath] zone math + eval), kernel-free
 * on Kotlin/JS too. The kernel truths are pinned by `adapter.laws.DstTimeZoneDiffTest` /
 * `adapter.laws.WallDayArithmeticDiffTest`; this proves the kernel-free build reproduces them end-to-end:
 * a `Europe/Berlin` model's datetime arithmetic is DST-aware (instant diffs, standard-time resolution of the
 * ambiguous fall-back hour, the spring gap as `datumFormatFalsch`, the no-overshoot `AddDays` landings), the
 * wall-day `DifferenceInDays` count is time-of-day aware, and UTC keeps the naive arithmetic.
 */
class ModelTimeZoneTest {

    // ---- the loader mapping -------------------------------------------------------------------------------
    private fun claimModel(timeZone: String?) = """
        {
          "header": { "id": "claims" },
          "content": {
            ${if (timeZone == null) "" else "\"modelConfig\": { \"timeZone\": \"$timeZone\" },"}
            "modelRoot": { "rootGroups": [ {
              "type": "Group", "id": "/Claim", "name": "Claim", "Group": { "elements": [
                { "type": "Field", "id": "/Claim/ReportedAt", "name": "ReportedAt", "Field": { "fieldType": {"type":"DateTimeType","DateTimeType":{"format":"yyyy-MM-dd'T'HH:mm:ss"}} } }
              ] } } ] } }
        }
    """.trimIndent()

    @Test fun loaderReadsBerlin() =
        assertEquals(ModelTimeZone.EUROPE_BERLIN, ModelLoader.load(claimModel("Europe/Berlin")).timeZone)

    @Test fun absentSlotDefaultsToUtc() =
        assertEquals(ModelTimeZone.UTC, ModelLoader.load(claimModel(null)).timeZone)

    @Test fun gmtCollapsesToUtc() =
        assertEquals(ModelTimeZone.UTC, ModelLoader.load(claimModel("GMT")).timeZone)

    @Test fun anUnmodeledZoneIsRefusedLoud() {
        // A kernel-LEGAL zone the interpreter does not model → a loud, corrective refusal (the documented
        // scope limit, IG83), not a bare IllegalArgumentException and never a silent evaluation.
        val e = assertFailsWith<UnsupportedModelTimeZoneException> { ModelLoader.load(claimModel("America/New_York")) }
        val msg = e.message ?: ""
        assertTrue("America/New_York" in msg, "the refusal names the zone: $msg")
        assertTrue("UTC" in msg && "Europe/Berlin" in msg, "the refusal names the supported set: $msg")
    }

    // ---- Berlin evaluation semantics (the kernel truths, kernel-free) --------------------------------------
    private val fields = listOf(
        FieldDef(RA, Kind.DATE_TIME, format = "yyyy-MM-dd'T'HH:mm:ss"),
        FieldDef(SA, Kind.DATE_TIME, format = "yyyy-MM-dd'T'HH:mm:ss"),
    )

    private fun fires(cond: String, ra: String, sa: String, tz: ModelTimeZone = ModelTimeZone.EUROPE_BERLIN): Boolean {
        val def = ModelDef(fields, listOf(RuleDef("/Claim/R", "/Claim", cond, "LATE", "../ReportedAt")), timeZone = tz)
        val doc = def.newDocument().group("/Claim", 1).field(RA, ra, 1, 1).field(SA, sa, 1, 1)
        return DmInterpreter(def).validateFull(doc).any { it.code == "LATE" }
    }

    @Test fun springForwardDiffCountsRealHours() = assertTrue(
        fires("DifferenceInHours(ReportedAt, SettledAt) == 1", "2024-03-31T01:30:00", "2024-03-31T03:30:00"),
        "01:30 CET → 03:30 CEST is 1 real hour")

    @Test fun fallBackAmbiguousHourResolvesToStandardTime() = assertTrue(
        fires("DifferenceInMinutes(ReportedAt, SettledAt) == 120", "2024-10-27T01:30:00", "2024-10-27T02:30:00"),
        "the repeated 02:30 parses as CET → 120 minutes")

    @Test fun gapValueIsAFormalErrorAndSuppresses() {
        val def = ModelDef(fields, listOf(RuleDef("/Claim/R", "/Claim", "DifferenceInMinutes(ReportedAt, SettledAt) == 0", "LATE", "../ReportedAt")),
            timeZone = ModelTimeZone.EUROPE_BERLIN)
        val doc = def.newDocument().group("/Claim", 1)
            .field(RA, "2024-03-31T02:30:00", 1, 1).field(SA, "2024-03-31T03:30:00", 1, 1)
        val codes = DmInterpreter(def).validateFull(doc).map { it.code }
        assertTrue("datumFormatFalsch" in codes, "the nonexistent wall time is datumFormatFalsch, got $codes")
        assertTrue("LATE" !in codes, "the referencing rule is suppressed, got $codes")
    }

    @Test fun aGapValueUnderUtcIsAPlainValidDateTime() = assertTrue(
        fires("DifferenceInMinutes(ReportedAt, SettledAt) == 60", "2024-03-31T02:30:00", "2024-03-31T03:30:00", tz = ModelTimeZone.UTC),
        "under UTC there is no gap: a plain 60-minute pair")

    @Test fun chainedAddKeepsItsInstantIdentity() = assertTrue(
        fires("DifferenceInMinutes(AddHours(ReportedAt, 1), SettledAt) == 60", "2024-10-27T01:30:00", "2024-10-27T02:30:00"),
        "AddHours lands on the daylight side of the ambiguous hour — 60 real minutes to the stored value")

    @Test fun addDaysIsTimePreservingAndTheGapLandingNeverOvershoots() {
        assertTrue(fires("AddDays(ReportedAt, 1) == [SettledAt]", "2024-06-15T22:00:00", "2024-06-16T22:00:00"),
            "a wall-day add keeps the clock time")
        // Forward into the gap: the landing takes the DAYLIGHT (earlier) instant — 60 min before the rolled 03:30.
        assertTrue(fires("DifferenceInMinutes(AddDays(ReportedAt, 1), SettledAt) == 60", "2024-03-30T02:30:00", "2024-03-31T03:30:00"),
            "a forward gap landing takes the earlier instant")
        // Backward into the gap: the landing takes the STANDARD (later) instant — equal to the 03:30 instant.
        assertTrue(fires("DifferenceInMinutes(AddDays(ReportedAt, -1), SettledAt) == 0", "2024-04-01T02:30:00", "2024-03-31T03:30:00"),
            "a backward gap landing takes the later instant")
    }

    @Test fun differenceInDaysCountsWholeWallDays() {
        assertTrue(fires("DifferenceInDays(ReportedAt, SettledAt) == 0", "2024-06-15T23:00:00", "2024-06-16T01:00:00"),
            "2 wall hours into the next date = 0 whole days")
        assertTrue(fires("DifferenceInDays(ReportedAt, SettledAt) == 1", "2024-03-30T12:00:00", "2024-03-31T12:00:00"),
            "the 23-real-hour Berlin day is still 1 WALL day")
    }

    private companion object {
        const val RA = "/Claim/ReportedAt"
        const val SA = "/Claim/SettledAt"
    }
}
