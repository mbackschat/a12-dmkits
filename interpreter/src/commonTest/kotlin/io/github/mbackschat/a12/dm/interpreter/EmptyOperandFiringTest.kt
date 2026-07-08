package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multiplatform (JVM + JS) lock for IF73 — the PURE interpreter: `Length` / the date-time extractors / the date
 * diffs must return the kernel's fillable **0** on an EMPTY (not malformed) operand and FIRE. The values here are
 * the ones the kernel tri-differential locks in `adapter.laws.EmptyOperandFiringDiffTest`; this proves the
 * kernel-free build reproduces them (incl. `Length` grow-only vs the extractors/diffs symmetric fill polarity).
 * Own-domain (an order document; `Sibling` is a filled number so the row is content-bearing, matching the
 * kernel-law setup — a comparison over an empty field is suppressed unless the row carries content).
 */
class EmptyOperandFiringTest {

    private fun model(cond: String, errRel: String) = ModelDef(
        fields = listOf(
            FieldDef(SIB, Kind.NUMBER, scale = 0),
            FieldDef(STR, Kind.STRING),
            FieldDef(D1, Kind.DATE, format = "yyyy-MM-dd"),
            FieldDef(D2, Kind.DATE, format = "yyyy-MM-dd"),
            FieldDef(TM, Kind.TIME, format = "HH:mm:ss"),
            FieldDef(DT1, Kind.DATE_TIME, format = "yyyy-MM-dd'T'HH:mm:ss"),
            FieldDef(DT2, Kind.DATE_TIME, format = "yyyy-MM-dd'T'HH:mm:ss"),
        ),
        rules = listOf(RuleDef("/Doc/R", "/Doc", cond, "EF", errRel)),
    )

    private fun doc(m: ModelDef, filled: Map<String, String>): Document = m.newDocument().also { d ->
        d.field(SIB, "1", 1, 1)                                  // a filled sibling → the /Doc row is content-bearing
        filled.forEach { (p, v) -> d.field(p, v, 1, 1) }
    }

    private fun fires(cond: String, errRel: String, filled: Map<String, String> = emptyMap()): Int {
        val m = model(cond, errRel)
        return DmInterpreter(m).validateFull(doc(m, filled)).count { it.code == "EF" }
    }

    private fun polarity(cond: String, errRel: String, filled: Map<String, String> = emptyMap()): String? {
        val m = model(cond, errRel)
        return DmInterpreter(m).validateFull(doc(m, filled)).firstOrNull { it.code == "EF" }
            ?.let { if (it.type == MsgType.OMISSION) "OMISSION" else "VALUE" }
    }

    // ---- Length — empty string → 0 (grow-only) --------------------------------------------------------------

    @Test fun lengthEmptyFires() = assertEquals(1, fires("Length($STR) < 5", "../Str"))
    @Test fun lengthFilledNoFire() = assertEquals(0, fires("Length($STR) < 5", "../Str", mapOf(STR to "ABCDEF")))
    @Test fun lengthEmptyOmission() = assertEquals("OMISSION", polarity("Length($STR) < 5", "../Str"))
    /** grow-only: `Length(empty) > n` cannot be cleared by filling-shorter → VALUE (0 can't shrink). */
    @Test fun lengthEmptyGreaterIsValue() = assertEquals("VALUE", polarity("Length($STR) >= 0", "../Str"))

    // ---- date extractors — empty date → 0 (symmetric) -------------------------------------------------------

    @Test fun yearEmptyFires() = assertEquals(1, fires("YearFromDate($D1) < 3000", "../D1"))
    @Test fun monthEmptyFires() = assertEquals(1, fires("MonthFromDate($D1) < 3", "../D1"))
    @Test fun dayEmptyFires() = assertEquals(1, fires("DayFromDate($D1) < 3", "../D1"))
    @Test fun quarterEmptyFires() = assertEquals(1, fires("QuarterFromDate($D1) < 3", "../D1"))
    @Test fun yearMalformedSuppressed() = assertEquals(0, fires("YearFromDate($D1) < 3000", "../D1", mapOf(D1 to "not-a-date")))
    @Test fun yearFilledReal() = assertEquals(0, fires("YearFromDate($D1) < 2000", "../D1", mapOf(D1 to "2026-01-01")))
    @Test fun yearEmptyOmission() = assertEquals("OMISSION", polarity("YearFromDate($D1) < 3000", "../D1"))

    // ---- time extractors — empty time → 0 (symmetric) -------------------------------------------------------

    @Test fun hoursEmptyFires() = assertEquals(1, fires("HoursFromTime($TM) < 3", "../Tm"))
    @Test fun minutesEmptyFires() = assertEquals(1, fires("MinutesFromTime($TM) < 3", "../Tm"))
    @Test fun secondsEmptyFires() = assertEquals(1, fires("SecondsFromTime($TM) < 3", "../Tm"))

    // ---- date diffs — either operand empty → 0 (symmetric); either malformed → suppress ---------------------

    @Test fun diffDaysOneEmptyFires() = assertEquals(1, fires("DifferenceInDays($D1, $D2) < 3", "../D1", mapOf(D2 to "2026-06-01")))
    @Test fun diffMonthsOneEmptyFires() = assertEquals(1, fires("DifferenceInMonths($D1, $D2) < 3", "../D1", mapOf(D2 to "2026-06-01")))
    @Test fun diffYearsOneEmptyFires() = assertEquals(1, fires("DifferenceInYears($D1, $D2) < 3", "../D1", mapOf(D2 to "2030-06-01")))
    @Test fun diffDaysBothFilledReal() = assertEquals(0, fires("DifferenceInDays($D1, $D2) < 3", "../D1", mapOf(D1 to "2026-01-01", D2 to "2026-06-01")))
    @Test fun diffDaysOneMalformedSuppressed() = assertEquals(0, fires("DifferenceInDays($D1, $D2) < 3", "../D1", mapOf(D1 to "bad", D2 to "2026-06-01")))
    @Test fun diffDaysOneEmptyOmission() = assertEquals("OMISSION", polarity("DifferenceInDays($D1, $D2) < 3", "../D1", mapOf(D2 to "2026-06-01")))
    @Test fun diffHoursOneEmptyFires() = assertEquals(1, fires("DifferenceInHours($DT1, $DT2) < 3", "../Dt1", mapOf(DT2 to "2026-06-01T10:00:00")))
    @Test fun diffMinutesOneEmptyFires() = assertEquals(1, fires("DifferenceInMinutes($DT1, $DT2) < 3", "../Dt1", mapOf(DT2 to "2026-06-01T10:00:00")))
    @Test fun diffSecondsOneEmptyFires() = assertEquals(1, fires("DifferenceInSeconds($DT1, $DT2) < 3", "../Dt1", mapOf(DT2 to "2026-06-01T10:00:00")))

    private companion object {
        const val SIB = "/Doc/Sibling"
        const val STR = "/Doc/Str"
        const val D1 = "/Doc/D1"
        const val D2 = "/Doc/D2"
        const val TM = "/Doc/Tm"
        const val DT1 = "/Doc/Dt1"
        const val DT2 = "/Doc/Dt2"
    }
}
