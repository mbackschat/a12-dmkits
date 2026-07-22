package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Differential — `MinValue`/`MaxValue` (field-list/star) and `Min`/`Max` (operand-list) over **TIME** and
 * **DATETIME** operands (SPEC-2026-07-21-07). The kernel folds these through the same `DateCombiner` as DATE
 * ([DateAggregateDiffTest]): Time by its decoded time-of-day, DateTime by its resolved instant (never a
 * rendered label), skipping check-relevant valueless inputs, folding all-empty to no value, propagating a
 * non-relevant input, and carrying symmetric `nichtAngegeben` missingness. The interpreter already shares one
 * `dateLike` temporal fold; this class LOCKS that the fold matches both kernel strategies for Time and DateTime,
 * including the equal-looking `Europe/Berlin` fall-back overlap where two labels denote distinct instants.
 *
 * Aggregates are the raw stored form and compared against **fields** (not literals) so no literal-format gate is
 * exercised here (that gate is SPEC-2026-07-22-01). The comparison's LHS field is reused as the rule's error
 * field (the kernel requires it referenced, `MVK_ERROR_FIELD_NOT_REFERENCED`). Typed TIME/DATETIME star and
 * operand-list authoring/read-back are locked separately by `TimeDateTimeAggregateReadTest`,
 * `TemporalOperandMinMaxConditionTest`, and `TemporalOperandMinMaxReadTest`.
 */
class TemporalExtremaDiffTest {

    private val schedule = RuntimeLaws.model("models/schedule.dm.json")

    private data class Slot(val time: String? = null, val stamp: String? = null, val date: String? = null)

    private fun kernelDoc(root: Map<String, String>, slots: List<Slot>): RuntimeLaws.Doc {
        val d = RuntimeLaws.doc().group(ROOT, 1)
        for ((f, v) in root) d.field("$ROOT/$f", v, 1, 1)
        slots.forEachIndexed { i, s ->
            val r = i + 1
            d.group(SLOTS, 1, r).field("$SLOTS/SlotName", "S$r", 1, r, 1)
            s.time?.let { d.field("$SLOTS/SlotTime", it, 1, r, 1) }
            s.stamp?.let { d.field("$SLOTS/SlotStamp", it, 1, r, 1) }
            s.date?.let { d.field("$SLOTS/SlotDate", it, 1, r, 1) }
        }
        return d
    }

    private fun interpDoc(model: String, root: Map<String, String>, slots: List<Slot>): Document {
        val doc = ModelLoader.load(model).newDocument().group(ROOT, 1)
        for ((f, v) in root) doc.field("$ROOT/$f", v, 1, 1)
        slots.forEachIndexed { i, s ->
            val r = i + 1
            doc.group(SLOTS, 1, r).field("$SLOTS/SlotName", "S$r", 1, r, 1)
            s.time?.let { doc.field("$SLOTS/SlotTime", it, 1, r, 1) }
            s.stamp?.let { doc.field("$SLOTS/SlotStamp", it, 1, r, 1) }
            s.date?.let { doc.field("$SLOTS/SlotDate", it, 1, r, 1) }
        }
        return doc
    }

    /** Fired-code parity for rule [cond] (error field [errRel], relative to /Schedule), and NO degrade. */
    private fun agree(cond: String, errRel: String, root: Map<String, String>, slots: List<Slot>) {
        val model = RuntimeLaws.withRule(schedule, ROOT, "TX", errRel, "TX", cond)
        val kernel = RuntimeLaws.firedCodes(model, kernelDoc(root, slots))
        val interp = DmInterpreter(ModelLoader.load(model))
        val outcome = interp.validate(interpDoc(model, root, slots))
        assertTrue(outcome.unsupported.isEmpty()) { "degraded, not evaluated: ${outcome.unsupported}" }
        assertEquals(kernel, outcome.messages.map { it.code }.toSet(), "cond=`$cond` root=$root slots=$slots")
    }

    /** Fired-polarity parity (kernel MessageType vs interpreter [MsgType]) for a fired [cond]. */
    private fun agreeType(cond: String, errRel: String, root: Map<String, String>, slots: List<Slot>) {
        val model = RuntimeLaws.withRule(schedule, ROOT, "TX", errRel, "TX", cond)
        val kernel = RuntimeLaws.typeOf(model, "TX", kernelDoc(root, slots)).map { it.name }.orElse(null)
        val interp = DmInterpreter(ModelLoader.load(model))
        val msg = interp.validate(interpDoc(model, root, slots)).messages.firstOrNull { it.code == "TX" }
        val interpType = msg?.let { if (it.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR" }
        assertEquals(kernel, interpType, "cond=`$cond` root=$root slots=$slots")
    }

    /** Tri-check (Groovy-dynamic, generated Java, interpreter) fired-code parity. */
    private fun agreeTri(cond: String, errRel: String, root: Map<String, String>, slots: List<Slot>) {
        val model = RuntimeLaws.withRule(schedule, ROOT, "TX", errRel, "TX", cond)
        val groovy = RuntimeLaws.firedCodes(model, kernelDoc(root, slots))
        val engine = RuntimeLaws.kernelJavaEngine(model)
        val javaStatic = engine.firedCodesSorted(engine.instance(kernelDoc(root, slots))).toSet()
        val interp = DmInterpreter(ModelLoader.load(model))
        val outcome = interp.validate(interpDoc(model, root, slots))
        assertTrue(outcome.unsupported.isEmpty()) { "degraded: ${outcome.unsupported}" }
        assertEquals(groovy, outcome.messages.map { it.code }.toSet(), "interp≠groovy | cond=`$cond`")
        assertEquals(groovy, javaStatic, "java-static≠groovy | cond=`$cond`")
    }

    // ── 1. TIME star min/max distinguishes a one-SECOND separation ─────────────────────────────────────────
    @Test fun timeStarMinMaxAtOneSecondSeparation() {
        val slots = listOf(Slot(time = "10:00:01"), Slot(time = "10:00:02"))
        agree("[$WSTART] < MinValue($SLOTS*/SlotTime)", "../WindowStart", mapOf("WindowStart" to "10:00:00"), slots) // fires
        agree("[$WSTART] < MinValue($SLOTS*/SlotTime)", "../WindowStart", mapOf("WindowStart" to "10:00:01"), slots) // no
        agree("[$WEND] > MaxValue($SLOTS*/SlotTime)", "../WindowEnd", mapOf("WindowEnd" to "10:00:03"), slots)       // fires
        agree("[$WEND] > MaxValue($SLOTS*/SlotTime)", "../WindowEnd", mapOf("WindowEnd" to "10:00:02"), slots)       // no
    }

    // ── 2. DATETIME star min/max compares resolved instants ────────────────────────────────────────────────
    @Test fun dateTimeStarMinMaxByInstant() {
        val slots = listOf(Slot(stamp = "2024-06-15T02:00:00"), Slot(stamp = "2024-06-15T03:00:00"))
        agree("[$CIA] < MinValue($SLOTS*/SlotStamp)", "../CheckInA", mapOf("CheckInA" to "2024-06-15T01:00:00"), slots) // fires
        agree("[$CIA] < MinValue($SLOTS*/SlotStamp)", "../CheckInA", mapOf("CheckInA" to "2024-06-15T02:30:00"), slots) // no
        agree("[$CIA] > MaxValue($SLOTS*/SlotStamp)", "../CheckInA", mapOf("CheckInA" to "2024-06-15T04:00:00"), slots) // fires
    }

    // ── 3. all-empty TIME/DATETIME star folds to no value → the comparison does not fire ───────────────────
    @Test fun allEmptyTemporalStarDoesNotFire() {
        agree("[$WSTART] < MinValue($SLOTS*/SlotTime)", "../WindowStart", mapOf("WindowStart" to "10:00:00"), listOf(Slot(), Slot()))
        agree("[$CIA] < MinValue($SLOTS*/SlotStamp)", "../CheckInA", mapOf("CheckInA" to "2024-06-15T01:00:00"), listOf(Slot(), Slot()))
        agree("[$WSTART] < MinValue($SLOTS*/SlotTime)", "../WindowStart", mapOf("WindowStart" to "10:00:00"), emptyList())
    }

    // ── 4. a filled temporal star with an omitted tail / empty cell fires OMISSION (symmetric nichtAngegeben) ─
    @Test fun firedTemporalStarPolarityIsSymmetricNichtAngegeben() {
        val slots = listOf(Slot(time = "10:00:01"), Slot(time = "10:00:02")) // 2 of 50 declared → omitted tail
        agreeType("[$WSTART] < MinValue($SLOTS*/SlotTime)", "../WindowStart", mapOf("WindowStart" to "10:00:00"), slots)
        // cross-direction: the number-star directional rule would say VALUE (a min can't grow), the symmetric
        // temporal rule says OMISSION because a tail is nichtAngegeben
        agreeType("[$WSTART] > MinValue($SLOTS*/SlotTime)", "../WindowStart", mapOf("WindowStart" to "10:00:05"), slots)
        // an empty cell among the filled rows → nichtAngegeben → OMISSION
        agreeType("[$WEND] > MaxValue($SLOTS*/SlotTime)", "../WindowEnd", mapOf("WindowEnd" to "10:00:03"),
            listOf(Slot(time = "10:00:01"), Slot()))
    }

    // ── 5. a malformed temporal cell is non-relevant → poisons → the rule is suppressed ────────────────────
    @Test fun malformedTemporalCellSuppresses() {
        agree("[$WSTART] < MinValue($SLOTS*/SlotTime)", "../WindowStart", mapOf("WindowStart" to "10:00:00"),
            listOf(Slot(time = "not-a-time"), Slot(time = "10:00:02")))
    }

    // ── 6. operand-list Min/Max over root scalar TIME / DATETIME fields ────────────────────────────────────
    @Test fun operandListMinMaxOverScalarTemporals() {
        // Min over two times, compared to a third time field
        agree("Min([$WSTART], [$WEND]) < [$EARLIEST_T]", "../EarliestSlotTime",
            mapOf("WindowStart" to "09:00:00", "WindowEnd" to "17:00:00", "EarliestSlotTime" to "10:00:00"), emptyList())
        agree("Max([$WSTART], [$WEND]) > [$EARLIEST_T]", "../EarliestSlotTime",
            mapOf("WindowStart" to "09:00:00", "WindowEnd" to "17:00:00", "EarliestSlotTime" to "10:00:00"), emptyList())
        // a valueless operand is SKIPPED, the filled side wins
        agree("Min([$WSTART], [$WEND]) < [$EARLIEST_T]", "../EarliestSlotTime",
            mapOf("WindowStart" to "09:00:00", "EarliestSlotTime" to "10:00:00"), emptyList())
        // both empty → no value → does not fire
        agree("Min([$WSTART], [$WEND]) < [$EARLIEST_T]", "../EarliestSlotTime", mapOf("EarliestSlotTime" to "10:00:00"), emptyList())
        // DATETIME operand-list mirror
        agree("Min([$CIA], [$CIB]) < [$EARLIEST_S]", "../EarliestSlotStamp",
            mapOf("CheckInA" to "2024-06-15T09:00:00", "CheckInB" to "2024-06-15T17:00:00",
                "EarliestSlotStamp" to "2024-06-15T10:00:00"), emptyList())
    }

    // ── 7. neighboring DATE control still folds (regression) ───────────────────────────────────────────────
    @Test fun neighboringDateStarStillFolds() {
        val slots = listOf(Slot(date = "2026-02-01"), Slot(date = "2026-03-01"))
        agree("[$PDATE] < MinValue($SLOTS*/SlotDate)", "../PlannedDate", mapOf("PlannedDate" to "2026-01-10"), slots) // fires
    }

    // ── 8. the equal-looking Europe/Berlin fall-back overlap: extrema fold by INSTANT, not label ───────────
    //   AddHours(01:30 CEST, 1) = the DAYLIGHT 02:30 instant; stored "02:30:00" re-parses STANDARD (CET) — 60
    //   min later. Min selects the earlier (daylight) instant, so `Min(...) < [CheckInB]` fires only if the fold
    //   compares instants; a label-based fold would see two equal "02:30" and not fire.
    @Test fun berlinOverlapExtremaSelectsByInstant() {
        val root = mapOf("CheckInA" to "2024-10-27T01:30:00", "CheckInB" to "2024-10-27T02:30:00")
        agree("Min(AddHours(CheckInA, 1), [$CIB]) < [$CIB]", "../CheckInB", root, emptyList())
        agree("Max(AddHours(CheckInA, 1), [$CIB]) == [$CIB]", "../CheckInB", root, emptyList())
    }

    // ── 9. codegen parity: both kernel strategies share the temporal DateCombiner ──────────────────────────
    @Test fun codegenFoldsTemporalAggregatesIdentically() {
        agreeTri("[$WSTART] < MinValue($SLOTS*/SlotTime)", "../WindowStart", mapOf("WindowStart" to "10:00:00"),
            listOf(Slot(time = "10:00:01"), Slot(time = "10:00:02")))
        agreeTri("[$CIA] < MinValue($SLOTS*/SlotStamp)", "../CheckInA", mapOf("CheckInA" to "2024-06-15T01:00:00"),
            listOf(Slot(stamp = "2024-06-15T02:00:00"), Slot(stamp = "2024-06-15T03:00:00")))
    }

    private companion object {
        const val ROOT = "/Schedule"
        const val SLOTS = "/Schedule/Slots"
        const val WSTART = "/Schedule/WindowStart"
        const val WEND = "/Schedule/WindowEnd"
        const val CIA = "/Schedule/CheckInA"
        const val CIB = "/Schedule/CheckInB"
        const val PDATE = "/Schedule/PlannedDate"
        const val EARLIEST_T = "/Schedule/EarliestSlotTime"
        const val EARLIEST_S = "/Schedule/EarliestSlotStamp"
    }
}
