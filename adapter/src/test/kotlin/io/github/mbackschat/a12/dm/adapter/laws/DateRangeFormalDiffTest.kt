package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — the **formal check for `DATE_RANGE` (the `datumBereich*` family) and calendar-invalid `DATE`s**
 * (KERNEL-FINDINGS §6 / IF12). Probe-verified kernel behavior, now matched:
 *  - a value that does not CONTAIN its separator → **`datumBereichTrennerFehlt`**;
 *  - the separator is present but the split's SHAPE is wrong — more than two parts, or an empty endpoint
 *    (`splitStringBySeparator` preserves empty tokens) → **`datumBereichFormatFalsch`**, decided before any
 *    endpoint is parsed (SPEC-2026-08-18-01);
 *  - a well-shaped split whose side is not a valid date for the format (incl. calendar-invalid like `31.02`) →
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

    /**
     * The three SPLIT discriminators, which the classifier used to collapse: every non-two-part split reported
     * a missing separator, so a value visibly carrying its separator drew the wrong public code. Containment is
     * what `trennerFehlt` tests; shape is a format question.
     */
    @Test
    fun dateRangeSplitShapeMatchesKernel() {
        assertRangeFormalAgrees("01.06.2021-30.06.2021-31.07.2021")   // extra separator → formatFalsch
        assertRangeFormalAgrees("-30.06.2021")                        // empty start endpoint → formatFalsch
        assertRangeFormalAgrees("01.06.2021-")                        // empty end endpoint → formatFalsch
        assertRangeFormalAgrees("-")                                  // both endpoints empty → formatFalsch
    }

    /**
     * The one declaration that reaches the kernel's SPLIT-IN-THE-MIDDLE branch. `DateUtil.splitStringBySeparator`
     * halves the value when the declared separator is empty, and exactly one `(format, separator)` pair makes
     * that reachable: `MM` + `""` (the allowlist keys on the concatenation `format + separator + format`, so
     * `MMMM` is admitted while every other format's doubled form is refused — measured 2026-08-19). Splitting
     * such a value on the empty string instead shreds it per character and reports a well-formed month pair as
     * malformed, so the branch needs its own row rather than an argument that it cannot occur.
     */
    @Test
    fun anEmptySeparatorSplitsInTheMiddleLikeTheKernel() {
        val pairModel = DocumentModelDraft.named("month-pair").locale("en_US")
            .rootGroup("Season") { season ->
                season.dateRangeField("MonthPair") { it.format("MM").separator("") }
            }.build().toJson()
        val path = "/Season/MonthPair"
        val pairDef = ModelDef(
            listOf(FieldDef(path, Kind.DATE_RANGE, format = "MM", rangeSeparator = "")), emptyList())

        for (value in listOf("0609", "0606", "0906", "06", "06x9", "1309")) {
            val kDoc = RuntimeLaws.doc().group("/Season", 1).field(path, value, 1, 1)
            val iDoc = pairDef.newDocument().group("/Season", 1).field(path, value, 1, 1)
            val kSigs = RuntimeLaws.messages(pairModel, kDoc).map { "${it.code()}|${it.field()}" }.sorted()
            val iSigs = DmInterpreter(pairDef).validateFull(iDoc).map { "${it.code}|${it.errorPath}" }.sorted()
            assertEquals(kSigs, iSigs, "empty-separator range formal signatures must match the kernel ($value)")
        }
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
