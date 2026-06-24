package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — the date/time CONSTRUCTION family over bounded-integer fields (`BirthDay` 1–31, `BirthMonth`
 * 1–12, `BirthYear` 1900–2100) + the `PickupTime` time field:
 *  - `Date(day, month, year)` — a real construction extracts (year/month/day); an UNREAL one (31,2,…) is
 *    non-evaluable (the extractors see no value, kernel-probed).
 *  - `Valid`/`Invalid(Date(...))` — Valid iff real-and-all-present; Invalid its complement (unreal OR an empty
 *    part), with OMISSION polarity when an empty part is fillable, VALUE when present-but-unreal; a malformed
 *    part makes both quiet (suppressed). Locks both firing AND polarity.
 *  - `Time("h","m","s")` — composes a time, verified by equality to a TIME field and via `HoursFromTime`.
 * Fully deterministic — no clock. Kernel-probed.
 */
class DateConstructionDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val fields = listOf(
        FieldDef(BD, Kind.NUMBER), FieldDef(BM, Kind.NUMBER), FieldDef(BY, Kind.NUMBER),
        FieldDef(PT, Kind.TIME, format = "HH:mm:ss"),
    )

    /** `<cond>` (rule [code], error field [errorRel]) over the given birth parts + PickupTime — kernel and
     *  interpreter must agree on BOTH firing and (when fired) the message polarity. */
    private fun agree(cond: String, errorRel: String, d: String?, m: String?, y: String?, pickup: String, msg: String) {
        val kModel = RuntimeLaws.withRule(order, "/Order", "DC", errorRel, "DC", cond)
        val def = ModelDef(fields, listOf(RuleDef("/Order/DC", "/Order", cond, "DC", errorRel)))
        fun set(doc: RuntimeLaws.Doc): RuntimeLaws.Doc {
            doc.group("/Order", 1).field(PT, pickup, 1, 1)
            if (d != null) doc.field(BD, d, 1, 1) else doc.emptyField(BD, 1, 1)
            if (m != null) doc.field(BM, m, 1, 1) else doc.emptyField(BM, 1, 1)
            if (y != null) doc.field(BY, y, 1, 1) else doc.emptyField(BY, 1, 1)
            return doc
        }
        val iDoc = Document(def.kinds, def.declaredReps, def.unsignedFields, def.rangeConfig, def.dateFormats,
            def.scales, def.dateTimeFormats).field(PT, pickup, 1, 1)
        if (d != null) iDoc.field(BD, d, 1, 1) else iDoc.emptyField(BD, 1, 1)
        if (m != null) iDoc.field(BM, m, 1, 1) else iDoc.emptyField(BM, 1, 1)
        if (y != null) iDoc.field(BY, y, 1, 1) else iDoc.emptyField(BY, 1, 1)

        val kType = RuntimeLaws.typeOf(kModel, "DC", set(RuntimeLaws.doc())).map { it.name }.orElse(null)
        val iType = DmInterpreter(def).validate(iDoc).messages.firstOrNull { it.code == "DC" }
            ?.type?.let { if (it == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR" }
        assertEquals(kType, iType, "$msg  (kernel=$kType interp=$iType)")
    }

    // ---- Date(...) value via extractors ----
    @Test fun realDateExtracts() =
        agree("FieldFilled(BirthYear) And YearFromDate(Date(BirthDay, BirthMonth, BirthYear)) == 2024", "../BirthYear",
            "15", "6", "2024", "10:30:50", "real Date(15,6,2024) → year 2024")
    @Test fun unrealDateIsNonEvaluable() =
        agree("FieldFilled(BirthYear) And YearFromDate(Date(BirthDay, BirthMonth, BirthYear)) == 2024", "../BirthYear",
            "31", "2", "2024", "10:30:50", "unreal Date(31,2,2024) → no value → no fire")

    // ---- Valid / Invalid ----
    @Test fun validOnReal() =
        agree("FieldFilled(BirthYear) And Valid(Date(BirthDay, BirthMonth, BirthYear))", "../BirthYear",
            "15", "6", "2024", "10:30:50", "Valid fires VALUE on a real date")
    @Test fun validNotOnUnreal() =
        agree("FieldFilled(BirthYear) And Valid(Date(BirthDay, BirthMonth, BirthYear))", "../BirthYear",
            "31", "2", "2024", "10:30:50", "Valid does not fire on an unreal date")
    @Test fun invalidOnUnrealIsValueError() =
        agree("FieldFilled(BirthYear) And Invalid(Date(BirthDay, BirthMonth, BirthYear))", "../BirthYear",
            "31", "2", "2024", "10:30:50", "Invalid fires VALUE on a present-but-unreal date")
    @Test fun invalidOnEmptyPartIsOmission() =
        agree("FieldFilled(BirthYear) And Invalid(Date(BirthDay, BirthMonth, BirthYear))", "../BirthYear",
            null, "6", "2024", "10:30:50", "Invalid fires OMISSION when a part is empty (fillable)")
    @Test fun invalidNotOnReal() =
        agree("FieldFilled(BirthYear) And Invalid(Date(BirthDay, BirthMonth, BirthYear))", "../BirthYear",
            "15", "6", "2024", "10:30:50", "Invalid does not fire on a real date")
    @Test fun malformedPartSuppressesInvalid() =
        agree("FieldFilled(BirthYear) And Invalid(Date(BirthDay, BirthMonth, BirthYear))", "../BirthYear",
            "abc", "6", "2024", "10:30:50", "a malformed part suppresses Invalid (formal error fires instead)")
    @Test fun invalidFiresOnFullyEmptyRow() =
        agree("Invalid(Date(BirthDay, BirthMonth, BirthYear))", "../BirthYear",
            null, null, null, "10:30:50", "Invalid fires OMISSION on an all-empty row (unspecified parts)")

    // ---- Time(...) ----
    @Test fun timeEqualsField() =
        agree("FieldFilled(PickupTime) And [PickupTime] == Time(\"10\", \"30\", \"50\")", "../PickupTime",
            "15", "6", "2024", "10:30:50", "Time(\"10\",\"30\",\"50\") equals a 10:30:50 PickupTime")
    @Test fun timeNotEqualsField() =
        agree("FieldFilled(PickupTime) And [PickupTime] == Time(\"10\", \"30\", \"50\")", "../PickupTime",
            "15", "6", "2024", "09:00:00", "a different PickupTime does not equal the composed time")
    @Test fun hoursFromComposedTime() =
        agree("FieldFilled(PickupTime) And HoursFromTime(Time(\"10\", \"30\", \"50\")) == 10", "../PickupTime",
            "15", "6", "2024", "10:30:50", "HoursFromTime of the composed time is 10")

    private companion object {
        const val BD = "/Order/BirthDay"
        const val BM = "/Order/BirthMonth"
        const val BY = "/Order/BirthYear"
        const val PT = "/Order/PickupTime"
    }
}
