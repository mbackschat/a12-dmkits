package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — the **formal check for `DATE_RANGE` (the `datumBereich*` family) and calendar-invalid `DATE`s**
 * (KERNEL-FINDINGS §6 / IF12). Probe-verified kernel behavior, now matched:
 *  - a range that doesn't split into two on its separator → **`datumBereichTrennerFehlt`**;
 *  - split, but a side is not a valid date for the format (incl. calendar-invalid like `31.02`) →
 *    **`datumBereichFormatFalsch`**;
 *  - two valid dates but start AFTER end → **`datumBereichNichtGueltig`** (start == end is allowed);
 *  - a calendar-invalid plain `DATE` (`2021-02-31`, a day past the month's length) → **`datumFormatFalsch`**
 *    (the interpreter previously ACCEPTED such dates — a day-of-month gap the fuzz missed by capping days at 28).
 */
class DateRangeFormalDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val OD = "/Order/OrderDate"
    private val CW = "/Order/CoverageWindow"
    private val def = ModelDef(listOf(
        FieldDef(OD, Kind.DATE),
        FieldDef(CW, Kind.DATE_RANGE, format = "dd.MM.yyyy", rangeSeparator = "-"),
    ), emptyList())

    private fun assertRangeFormalAgrees(value: String) = assertFormalAgrees(CW, value)
    private fun assertDateFormalAgrees(value: String) = assertFormalAgrees(OD, value)

    private fun assertFormalAgrees(path: String, value: String) {
        val kDoc = RuntimeLaws.doc().group("/Order", 1).field(path, value, 1, 1)
        val iDoc = def.newDocument()
            .group("/Order", 1).field(path, value, 1, 1)
        val kSigs = RuntimeLaws.messages(order, kDoc).map { "${it.code()}|${it.type().name}|${it.field()}" }.sorted()
        val iSigs = DmInterpreter(def).validateFull(iDoc)
            .map { "${it.code}|${if (it.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR"}|${it.errorPath}" }.sorted()
        assertEquals(kSigs, iSigs, "formal-error signatures must match the kernel ($path = \"$value\")")
    }

    @Test
    fun dateRangeFormalCodesMatchKernel() {
        assertRangeFormalAgrees("01.06.2021-30.06.2021")   // valid → no error
        assertRangeFormalAgrees("01.06.2021-01.06.2021")   // start == end → valid
        assertRangeFormalAgrees("garbage")                 // no separator → trennerFehlt
        assertRangeFormalAgrees("01.06.2021")              // no separator → trennerFehlt
        assertRangeFormalAgrees("01.06.2021-notadate")     // bad date → formatFalsch
        assertRangeFormalAgrees("31.02.2021-30.06.2021")   // calendar-invalid date → formatFalsch
        assertRangeFormalAgrees("30.06.2021-01.06.2021")   // start after end → nichtGueltig
    }

    @Test
    fun calendarInvalidPlainDateMatchesKernel() {
        assertDateFormalAgrees("2021-06-15")   // valid → no error
        assertDateFormalAgrees("2021-02-31")   // Feb 31 → datumFormatFalsch (was wrongly accepted)
        assertDateFormalAgrees("2021-04-31")   // Apr has 30 days → datumFormatFalsch
        assertDateFormalAgrees("2023-02-29")   // 2023 not a leap year → datumFormatFalsch
        assertDateFormalAgrees("2024-02-29")   // 2024 IS a leap year → valid
    }
}
