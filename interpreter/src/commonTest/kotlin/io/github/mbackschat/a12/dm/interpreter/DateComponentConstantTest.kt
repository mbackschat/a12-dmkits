package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multiplatform (JVM + JS) lock for SPEC-2026-07-27-11's positional runtime matrix — an admitted quoted
 * `Date(...)` component constant is a fixed present Number input, decoded per position: Day has no width gate
 * (`"0001"` is the 1st), the four-part form composes `Century × 100 + ShortYear` with a `"00"` Short-Year
 * staying PRESENT, and the digit repertoire is per UTF-16 code unit, so BMP non-ASCII digits decode.
 *
 * The BMP row is the host-sensitive one: it depends on the platform's decimal decoding accepting a non-ASCII
 * digit, which is exactly the kind of thing that can differ between JVM and Kotlin/JS. Kernel-verified on both
 * strategies in `adapter.laws.DateComponentConstantDiffTest`; admission in
 * `rulekit.validate.laws.DateComponentConstantLawsTest`.
 */
class DateComponentConstantTest {

    private fun fires(construction: String, orderDate: String): Boolean {
        val def = ModelDef(
            fields = listOf(FieldDef(NAME, Kind.STRING), FieldDef(DATE, Kind.DATE, format = "yyyy-MM-dd")),
            rules = listOf(RuleDef("/Order/R", "/Order",
                "FieldFilled($NAME) And [$DATE] == $construction", "DCC", "../CustomerName")),
        )
        val doc = def.newDocument().group("/Order", 1)
            .field(NAME, "anyone", 1, 1).field(DATE, orderDate, 1, 1)
        return DmInterpreter(def).validateFull(doc).any { it.code == "DCC" }
    }

    private fun firesTime(construction: String, pickupTime: String): Boolean {
        val time = "/Order/PickupTime"
        val def = ModelDef(
            fields = listOf(FieldDef(NAME, Kind.STRING), FieldDef(time, Kind.TIME, format = "HH:mm:ss")),
            rules = listOf(RuleDef("/Order/R", "/Order",
                "FieldFilled($NAME) And [$time] == $construction", "DCC", "../CustomerName")),
        )
        val doc = def.newDocument().group("/Order", 1)
            .field(NAME, "anyone", 1, 1).field(time, pickupTime, 1, 1)
        return DmInterpreter(def).validateFull(doc).any { it.code == "DCC" }
    }

    @Test fun theDayPositionHasNoWidthGate() {
        assertEquals(true, fires("""Date("0001", "1", "1963")""", "1963-01-01"))
        assertEquals(false, fires("""Date("0001", "1", "1963")""", "1963-01-02"))
    }

    @Test fun theFourPartFormComposesCenturyAndShortYear() {
        assertEquals(true, fires("""Date("01", "01", "19", "63")""", "1963-01-01"))
        assertEquals(false, fires("""Date("01", "01", "19", "63")""", "1900-01-01"))
    }

    @Test fun aFixedZeroShortYearStaysPresent() {
        assertEquals(true, fires("""Date("01", "01", "19", "00")""", "1900-01-01"),
            "\"00\" is a fixed zero, not an omission")
    }

    @Test fun aBmpNonAsciiDigitSequenceDecodesOnBothHosts() {
        assertEquals(true, fires("""Date("١٠", "1", "1963")""", "1963-01-10"),
            "Arabic-Indic digits decode to 10 — the host-sensitive row")
        assertEquals(false, fires("""Date("١٠", "1", "1963")""", "1963-01-01"))
    }

    @Test fun omittedTimeComponentsDefaultToZero() {
        assertEquals(true, firesTime("Time()", "00:00:00"))
        assertEquals(false, firesTime("Time()", "00:00:01"))
        assertEquals(true, firesTime("""Time("10", "30")""", "10:30:00"))
        assertEquals(false, firesTime("""Time("10", "30")""", "10:30:01"))
    }

    private companion object {
        const val NAME = "/Order/CustomerName"
        const val DATE = "/Order/OrderDate"
    }
}
