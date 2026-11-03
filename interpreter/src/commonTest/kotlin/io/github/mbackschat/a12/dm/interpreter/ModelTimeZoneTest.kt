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
 * a `Europe/Berlin` model's datetime arithmetic is DST-aware (instant diffs, the ambiguous fall-back hour at its
 * earlier, summer-time offset, a spring-gap wall time moved forward by the gap, `AddDays` landings likewise), the
 * `DifferenceInDays` counts whole days between wall clocks, and UTC keeps ordinary controls (KERNEL-FINDINGS KF665).
 */
class ModelTimeZoneTest {

    // ---- the loader mapping -------------------------------------------------------------------------------
    private fun claimModel(timeZone: String?) = claimModelValue(timeZone?.let { "\"$it\"" })

    private fun claimModelValue(timeZoneJson: String?) = """
        {
          "header": { "id": "claims" },
          "content": {
            ${if (timeZoneJson == null) "" else "\"modelConfig\": { \"timeZone\": $timeZoneJson },"}
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

    @Test fun utcMapsToUtc() =
        assertEquals(ModelTimeZone.UTC, ModelLoader.load(claimModel("UTC")).timeZone)

    @Test fun gmtCollapsesToUtc() =
        assertEquals(ModelTimeZone.UTC, ModelLoader.load(claimModel("GMT")).timeZone)

    /** Kernel 31.1.1 reads an empty zone as an absent one (KERNEL-FINDINGS KF658, `adapter.laws.EmptyTimeZoneDiffTest`). */
    @Test fun anEmptySlotDefaultsToUtcAsAnAbsentOneDoes() =
        assertEquals(ModelTimeZone.UTC, ModelLoader.load(claimModelValue("\"\"")).timeZone)

    @Test fun explicitNullAndWhitespaceAreInvalidConfiguration() {
        for ((label, json, expectedId) in listOf(
            Triple("null", "null", null),
            Triple("whitespace", "\"   \\t\"", "   \t"),
        )) {
            val e = assertFailsWith<InvalidModelTimeZoneException> { ModelLoader.load(claimModelValue(json)) }
            val msg = e.message ?: ""
            assertEquals(expectedId, e.timeZoneId)
            assertTrue("timeZone" in msg && "invalid" in msg, "$label is an explicit invalid config value: $msg")
        }
    }

    @Test fun outsideSetIsRefusedWithoutGuessingWhetherJavaAcceptsIt() {
        for (id in listOf("America/New_York", "Europe/Berln")) {
            val e = assertFailsWith<UnsupportedModelTimeZoneException> { ModelLoader.load(claimModel(id)) }
            val msg = e.message ?: ""
            assertEquals(id, e.timeZoneId)
            assertTrue(id in msg, "the refusal names the zone: $msg")
            assertTrue("UTC" in msg && "Europe/Berlin" in msg, "the refusal names the supported set: $msg")
            assertTrue("kernel-legal" !in msg && "kernel accepts" !in msg,
                "common code stays neutral about the host Java id domain: $msg")
        }
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

    @Test fun fallBackAmbiguousHourResolvesToSummerTime() = assertTrue(
        fires("DifferenceInMinutes(ReportedAt, SettledAt) == 60", "2024-10-27T01:30:00", "2024-10-27T02:30:00"),
        "the repeated 02:30 parses at its earlier offset, CEST → 60 minutes (KF665)")

    @Test fun aGapValueIsMovedForwardByTheGap() {
        val def = ModelDef(fields, listOf(RuleDef("/Claim/R", "/Claim", "DifferenceInMinutes(ReportedAt, SettledAt) == 0", "LATE", "../ReportedAt")),
            timeZone = ModelTimeZone.EUROPE_BERLIN)
        val doc = def.newDocument().group("/Claim", 1)
            .field(RA, "2024-03-31T02:30:00", 1, 1).field(SA, "2024-03-31T03:30:00", 1, 1)
        val codes = DmInterpreter(def).validateFull(doc).map { it.code }
        assertEquals(listOf("LATE"), codes, "02:30 in the gap is the instant of 03:30 and draws no formal code (KF665)")
    }

    @Test fun aGapValueUnderUtcIsAPlainValidDateTime() = assertTrue(
        fires("DifferenceInMinutes(ReportedAt, SettledAt) == 60", "2024-03-31T02:30:00", "2024-03-31T03:30:00", tz = ModelTimeZone.UTC),
        "under UTC there is no gap: a plain 60-minute pair")

    @Test fun chainedAddKeepsItsInstantIdentity() = assertTrue(
        fires("DifferenceInMinutes(AddHours(ReportedAt, 2), SettledAt) == -60", "2024-10-27T01:30:00", "2024-10-27T02:30:00"),
        "AddHours lands on the standard side of the ambiguous hour, 60 real minutes after the stored, summer-time value")

    @Test fun addDaysIsTimePreservingAndAGapLandingMovesForward() {
        assertTrue(fires("AddDays(ReportedAt, 1) == [SettledAt]", "2024-06-15T22:00:00", "2024-06-16T22:00:00"),
            "a wall-day add keeps the clock time")
        // Into the gap from either side, the landing moves forward by the gap: the 03:30 instant (KF665).
        assertTrue(fires("DifferenceInMinutes(AddDays(ReportedAt, 1), SettledAt) == 0", "2024-03-30T02:30:00", "2024-03-31T03:30:00"),
            "a forward step into the gap lands one gap later")
        assertTrue(fires("DifferenceInMinutes(AddDays(ReportedAt, -1), SettledAt) == 0", "2024-04-01T02:30:00", "2024-03-31T03:30:00"),
            "a backward step into the gap lands one gap later too")
    }

    @Test fun differenceInDaysCountsCompleteCalendarSteps() {
        assertTrue(fires("DifferenceInDays(ReportedAt, SettledAt) == 0", "2024-06-15T23:00:00", "2024-06-16T01:00:00"),
            "the next same-clock calendar landing is still after the endpoint")
        assertTrue(fires("DifferenceInDays(ReportedAt, SettledAt) == 1", "2024-03-30T12:00:00", "2024-03-31T12:00:00"),
            "one model-zone calendar-day landing reaches the endpoint")
    }

    private companion object {
        const val RA = "/Claim/ReportedAt"
        const val SA = "/Claim/SettledAt"
    }
}
