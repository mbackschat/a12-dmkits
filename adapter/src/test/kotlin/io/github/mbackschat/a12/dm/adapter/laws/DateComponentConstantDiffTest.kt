package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — SPEC-2026-07-27-11's POSITIONAL RUNTIME matrix: an admitted quoted `Date(...)` component
 * constant is a fixed present Number input, and each position decodes as the kernel decodes it.
 *
 *  - Day has no width gate, so `"0001"` is the 1st (leading zeros are not a width error);
 *  - the four-part form composes `Century × 100 + ShortYear`, and a `"00"` Short-Year stays PRESENT (it is a
 *    fixed zero, not an omission) → `19`/`00` is 1900;
 *  - the digit repertoire is **per UTF-16 code unit** (`StringUtils.isNumeric` → `Character.isDigit(char)`),
 *    so BMP non-ASCII digits decode — Arabic-Indic `"١٠"` is the 10th — while a supplementary-plane digit is
 *    refused at admission (locked in `rulekit.validate.laws.DateComponentConstantLawsTest`).
 *
 * Each row carries a negative control so it cannot pass by the comparison going UNKNOWN. Tri-checked:
 * dynamic-Groovy (normative), static-Java, interpreter. JVM/Node half: `interpreter DateComponentConstantTest`.
 */
class DateComponentConstantDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")

    /** groovy-dynamic == java-static == interpreter, with the kernel outcome pinned by [fires]. */
    private fun triCheck(construction: String, orderDate: String, fires: Boolean, msg: String) {
        val cond = "FieldFilled(CustomerName) And [OrderDate] == $construction"
        val model = RuntimeLaws.withRule(order, "/Order", "R", "../CustomerName", CODE, cond)
        val doc = RuntimeLaws.doc().group("/Order", 1)
            .field(NAME, "anyone", 1, 1).field(DATE, orderDate, 1, 1)
        val groovy = RuntimeLaws.messages(model, doc).filter { it.code() == CODE }.map { it.code() }.sorted()
        val engine = RuntimeLaws.kernelJavaEngine(model)
        val javaStatic = engine.messages(engine.instance(doc)).filter { it.code() == CODE }
            .map { it.code() }.sorted()
        val def = ModelLoader.load(model)
        val iDoc = def.newDocument().group("/Order", 1)
            .field(NAME, "anyone", 1, 1).field(DATE, orderDate, 1, 1)
        val interp = DmInterpreter(def).validateFull(iDoc).filter { it.code == CODE }.map { it.code }.sorted()
        val clue = "$msg — $construction vs $orderDate"
        assertEquals(fires, groovy.isNotEmpty(), "$clue — the kernel outcome moved (groovy=$groovy)")
        assertEquals(groovy, javaStatic, "$clue (java-static≠groovy) groovy=$groovy java=$javaStatic")
        assertEquals(groovy, interp, "$clue (interp≠groovy) groovy=$groovy interp=$interp")
    }

    private fun triCheckTime(construction: String, pickupTime: String, fires: Boolean, msg: String) {
        val cond = "FieldFilled(CustomerName) And [PickupTime] == $construction"
        val model = RuntimeLaws.withRule(order, "/Order", "R", "../CustomerName", CODE, cond)
        val doc = RuntimeLaws.doc().group("/Order", 1)
            .field(NAME, "anyone", 1, 1).field(TIME, pickupTime, 1, 1)
        val groovy = RuntimeLaws.messages(model, doc).filter { it.code() == CODE }.map { it.code() }.sorted()
        val engine = RuntimeLaws.kernelJavaEngine(model)
        val javaStatic = engine.messages(engine.instance(doc)).filter { it.code() == CODE }
            .map { it.code() }.sorted()
        val def = ModelLoader.load(model)
        val iDoc = def.newDocument().group("/Order", 1)
            .field(NAME, "anyone", 1, 1).field(TIME, pickupTime, 1, 1)
        val interp = DmInterpreter(def).validateFull(iDoc).filter { it.code == CODE }.map { it.code }.sorted()
        val clue = "$msg — $construction vs $pickupTime"
        assertEquals(fires, groovy.isNotEmpty(), "$clue — the kernel outcome moved (groovy=$groovy)")
        assertEquals(groovy, javaStatic, "$clue (java-static≠groovy) groovy=$groovy java=$javaStatic")
        assertEquals(groovy, interp, "$clue (interp≠groovy) groovy=$groovy interp=$interp")
    }

    @Test fun theDayPositionHasNoWidthGate() {
        triCheck("""Date("0001", "1", "1963")""", "1963-01-01", true, "leading zeros still mean the 1st")
        triCheck("""Date("0001", "1", "1963")""", "1963-01-02", false, "and not the 2nd")
    }

    @Test fun aOneDigitMonthDecodes() {
        triCheck("""Date("15", "9", "1963")""", "1963-09-15", true, "a one-digit month is September")
        triCheck("""Date("15", "9", "1963")""", "1963-10-15", false, "and not October")
    }

    @Test fun theFourPartFormComposesCenturyAndShortYear() {
        triCheck("""Date("01", "01", "19", "63")""", "1963-01-01", true, "19 × 100 + 63")
        triCheck("""Date("01", "01", "19", "63")""", "1900-01-01", false, "and not the century alone")
    }

    @Test fun aFixedZeroShortYearStaysPresent() {
        triCheck("""Date("01", "01", "19", "00")""", "1900-01-01", true, "\"00\" is a fixed zero, not an omission")
        triCheck("""Date("01", "01", "19", "00")""", "1963-01-01", false, "negative control")
    }

    @Test fun aBmpNonAsciiDigitSequenceDecodes() {
        triCheck("""Date("١٠", "1", "1963")""", "1963-01-10", true,
            "Arabic-Indic digits are Character.isDigit per code unit, so they decode to 10")
        triCheck("""Date("١٠", "1", "1963")""", "1963-01-01", false, "and not to 1")
    }

    @Test fun omittedTimeComponentsDefaultToZero() {
        triCheckTime("Time()", "00:00:00", true, "all omitted means midnight")
        triCheckTime("Time()", "00:00:01", false, "midnight has zero seconds")
        triCheckTime("""Time("10", "30")""", "10:30:00", true, "omitted seconds means zero")
        triCheckTime("""Time("10", "30")""", "10:30:01", false, "omitted seconds is not one")
    }

    private companion object {
        const val CODE = "DCC"
        const val NAME = "/Order/CustomerName"
        const val DATE = "/Order/OrderDate"
        const val TIME = "/Order/PickupTime"
    }
}
