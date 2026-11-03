package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
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
     * An EMPTY declared separator, through the interpreter's loader: kernel 31.1.1 reads it as `/`, because its
     * expand copy omits an empty member (KERNEL-FINDINGS KF658), so `0609` lacks its separator and `06/09` is a pair.
     *
     * On kernel 30.8.1 this row locked the split-in-the-middle branch of `DateUtil.splitStringBySeparator`, which
     * `MM` + `""` reached, and found IF306 there: the loader folded `""` into null. The branch is still in the
     * kernel's runtime class and in the interpreter's evaluator, but no stored model reaches it on 31.1.1, so the
     * hand-built [ModelDef] route that exercised it alone has no kernel counterpart left to agree with.
     */
    @Test
    fun anEmptySeparatorReadsAsTheSlashLikeTheKernel() {
        val pairModel = DocumentModelDraft.named("month-pair").locale("en_US")
            .rootGroup("Season") { season ->
                season.dateRangeField("MonthPair") { it.format("MM").separator("") }
            }.build().toJson()
        val path = "/Season/MonthPair"
        val loadedDef = ModelLoader.load(pairModel)
        val observed = listOf("0609", "06/09", "09/06", "06", "06x9", "13/09").associateWith { value ->
            val kDoc = RuntimeLaws.doc().group("/Season", 1).field(path, value, 1, 1)
            val kSigs = RuntimeLaws.messages(pairModel, kDoc).map { it.code() }.sorted()
            val iDoc = loadedDef.newDocument().group("/Season", 1).field(path, value, 1, 1)
            kSigs to DmInterpreter(loadedDef).validateFull(iDoc).map { it.code }.sorted()
        }
        assertEquals(observed.mapValues { (_, sigs) -> sigs.first to sigs.first }, observed, "(value → kernel, interpreter)")
        assertEquals(linkedMapOf(
            "0609" to listOf(MISSING), "06/09" to emptyList(), "09/06" to listOf("datumBereichNichtGueltig"),
            "06" to listOf(MISSING), "06x9" to listOf(MISSING), "13/09" to listOf("datumBereichFormatFalsch"),
        ), observed.mapValues { it.value.first }, "the kernel's own answer")
    }

    private companion object {
        const val MISSING = "datumBereichTrennerFehlt"
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
