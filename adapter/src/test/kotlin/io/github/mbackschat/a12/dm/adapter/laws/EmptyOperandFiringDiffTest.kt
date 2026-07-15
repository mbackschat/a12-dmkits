package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — the value functions that must return the kernel's fillable **0** on an EMPTY (not malformed)
 * operand and FIRE (IF73). `Length` over an empty string (`BigDecimalUtils.laenge` — 0, grow-only), the
 * date/time extractors (`getDatumsTeil` — 0, symmetric) and the date diffs (`getDatumsDiff` — 0 when EITHER
 * operand is empty, symmetric) all coerce an unspecified operand to 0, unlike a direct comparison on an empty
 * field (which is suppressed). A MALFORMED operand stays non-evaluable (`nichtPruefungsrelevant` →
 * `NICHT_PRUEF_REL_ZAHL` → suppressed). The row is content-bearing (a filled `CustomerName`), matching the
 * kernel-law `EmptyValueLawsTest`. Own-domain (an insurance/e-commerce order).
 */
class EmptyOperandFiringDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val m = BoundModel.ofJson(order)
    private fun render(c: Condition): String = DslRenderer().render(c)

    /** Inject [cond] at /Order (error field [errRel] — referenced in the condition), fill CustomerName + the
     *  given [filled] cells, leave [empty] present-but-valueless; return kernel/interpreter fire counts. */
    private fun run(errRel: String, code: String, cond: String,
                    filled: Map<String, String>, empty: List<String>): Pair<Int, Int> {
        val model = RuntimeLaws.withRule(order, "/Order", "R_$code", errRel, code, cond)
        val kDoc = RuntimeLaws.doc().group("/Order", 1).field(CN, "Acme", 1, 1)
        filled.forEach { (p, v) -> kDoc.field(p, v, 1, 1) }
        empty.forEach { p -> kDoc.emptyField(p, 1, 1) }
        val kernel = RuntimeLaws.messages(model, kDoc).count { it.code() == code }
        val def = ModelLoader.load(model)
        val iDoc = Document(def.kinds, def.declaredReps, def.unsignedFields, def.rangeConfig, def.dateFormats, def.scales)
            .field(CN, "Acme", 1, 1)
        filled.forEach { (p, v) -> iDoc.field(p, v, 1, 1) }
        empty.forEach { p -> iDoc.emptyField(p, 1, 1) }
        val interp = DmInterpreter(def).validateFull(iDoc).count { it.code == code }
        return kernel to interp
    }

    private fun agree(errRel: String, code: String, cond: String,
                      filled: Map<String, String> = emptyMap(), empty: List<String> = emptyList()): Int {
        val (k, i) = run(errRel, code, cond, filled, empty)
        assertEquals(k, i, "$cond — kernel=$k interp=$i")
        return k
    }

    /** TRI-checked polarity: groovy-dynamic == java-static == interpreter (the java-static leg makes the
     *  "Kernel tri-checked" claim below true — a strategy-divergence-prone polarity witness needs BOTH
     *  kernel strategies, feedback F3). */
    private fun agreePolarity(errRel: String, code: String, cond: String,
                              filled: Map<String, String> = emptyMap(), empty: List<String> = emptyList()): String? {
        val model = RuntimeLaws.withRule(order, "/Order", "R_$code", errRel, code, cond)
        val kDoc = RuntimeLaws.doc().group("/Order", 1).field(CN, "Acme", 1, 1)
        filled.forEach { (p, v) -> kDoc.field(p, v, 1, 1) }
        empty.forEach { p -> kDoc.emptyField(p, 1, 1) }
        val kernel = RuntimeLaws.typeOf(model, code, kDoc).map { it.name }.orElse(null)
        val engine = RuntimeLaws.kernelJavaEngine(model)
        val javaStatic = engine.messages(engine.instance(kDoc)).firstOrNull { it.code() == code }?.type()?.name
        assertEquals(kernel, javaStatic, "$cond — polarity groovy-dynamic=$kernel java-static=$javaStatic")
        val def = ModelLoader.load(model)
        val iDoc = Document(def.kinds, def.declaredReps, def.unsignedFields, def.rangeConfig, def.dateFormats, def.scales)
            .field(CN, "Acme", 1, 1)
        filled.forEach { (p, v) -> iDoc.field(p, v, 1, 1) }
        empty.forEach { p -> iDoc.emptyField(p, 1, 1) }
        val interp = DmInterpreter(def).validateFull(iDoc).firstOrNull { it.code == code }
            ?.let { if (it.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR" }
        assertEquals(kernel, interp, "$cond — polarity kernel=$kernel interp=$interp")
        return kernel
    }

    // ---- Length over an empty string → 0 (grow-only) --------------------------------------------------------

    @Test fun lengthEmptyStringFires() =
        assertEquals(1, agree("../ProductCode", "LEN", render(Dsl.length(m.str(PC)).lt(5)), empty = listOf(PC)))

    @Test fun lengthFilledStringNoFire() =
        assertEquals(0, agree("../ProductCode", "LN6", render(Dsl.length(m.str(PC)).lt(5)), filled = mapOf(PC to "ABCDEF")))

    @Test fun lengthEmptyPolarityOmission() =
        assertEquals("OMISSION_ERROR", agreePolarity("../ProductCode", "LNP", render(Dsl.length(m.str(PC)).lt(5)), empty = listOf(PC)))

    /** `Length` is GROW-ONLY (`laenge`: `kannKleiner=false`), so a fired `Length(empty) >= 0` (0 ≥ 0, always
     *  fires) is a VALUE error — filling only lengthens, it cannot clear a `>=` — NOT OMISSION as a symmetric
     *  fill would give. This is the case that DISTINGUISHES grow-only from symmetric (the `< 5` direction is
     *  OMISSION either way). Tri-checked: groovy-dynamic == java-static == interpreter ([agreePolarity]). */
    @Test fun lengthEmptyGreaterEqualPolarityValue() =
        assertEquals("VALUE_ERROR", agreePolarity("../ProductCode", "LNV", render(Dsl.length(m.str(PC)).ge(0)), empty = listOf(PC)))

    // ---- date extractors over an empty date → 0 (symmetric) -------------------------------------------------

    @Test fun yearFromEmptyDateFires() =
        assertEquals(1, agree("../OrderDate", "YR", render(Dsl.yearFromDate(m.date(OD)).lt(3000)), empty = listOf(OD)))

    @Test fun monthFromEmptyDateFires() =
        assertEquals(1, agree("../OrderDate", "MO", render(Dsl.monthFromDate(m.date(OD)).lt(3)), empty = listOf(OD)))

    @Test fun dayFromEmptyDateFires() =
        assertEquals(1, agree("../OrderDate", "DY", render(Dsl.dayFromDate(m.date(OD)).lt(3)), empty = listOf(OD)))

    @Test fun quarterFromEmptyDateFires() =
        assertEquals(1, agree("../OrderDate", "QT", render(Dsl.quarterFromDate(m.date(OD)).lt(3)), empty = listOf(OD)))

    @Test fun yearFromMalformedDateSuppressed() =
        assertEquals(0, agree("../OrderDate", "YRM", render(Dsl.yearFromDate(m.date(OD)).lt(3000)), filled = mapOf(OD to "not-a-date")))

    @Test fun yearFromFilledDateReal() =   // a filled date extracts the REAL year 2026, not 0 — 2026 is not < 2000
        assertEquals(0, agree("../OrderDate", "YRF", render(Dsl.yearFromDate(m.date(OD)).lt(2000)), filled = mapOf(OD to "2026-01-01")))

    @Test fun yearFromEmptyPolarityOmission() =
        assertEquals("OMISSION_ERROR", agreePolarity("../OrderDate", "YRP", render(Dsl.yearFromDate(m.date(OD)).lt(3000)), empty = listOf(OD)))

    // ---- time extractors over an empty time → 0 (symmetric) -------------------------------------------------

    @Test fun hoursFromEmptyTimeFires() =
        assertEquals(1, agree("../PickupTime", "HR", render(Dsl.hoursFromTime(m.time(PT)).lt(3)), empty = listOf(PT)))

    @Test fun minutesFromEmptyTimeFires() =
        assertEquals(1, agree("../PickupTime", "MI", render(Dsl.minutesFromTime(m.time(PT)).lt(3)), empty = listOf(PT)))

    @Test fun secondsFromEmptyTimeFires() =
        assertEquals(1, agree("../PickupTime", "SE", render(Dsl.secondsFromTime(m.time(PT)).lt(3)), empty = listOf(PT)))

    // ---- date diffs: EITHER operand empty → 0 (symmetric); either malformed → suppress ----------------------

    @Test fun diffDaysOneEmptyFires() =
        assertEquals(1, agree("../OrderDate", "DD", render(Dsl.differenceInDays(m.date(OD), m.date(DD)).lt(3)),
            filled = mapOf(DD to "2026-06-01"), empty = listOf(OD)))

    @Test fun diffMonthsOneEmptyFires() =
        assertEquals(1, agree("../OrderDate", "DM", render(Dsl.differenceInMonths(m.date(OD), m.date(DD)).lt(3)),
            filled = mapOf(DD to "2026-06-01"), empty = listOf(OD)))

    @Test fun diffYearsOneEmptyFires() =
        assertEquals(1, agree("../OrderDate", "DYR", render(Dsl.differenceInYears(m.date(OD), m.date(DD)).lt(3)),
            filled = mapOf(DD to "2030-06-01"), empty = listOf(OD)))

    @Test fun diffDaysBothFilledReal() =
        assertEquals(0, agree("../OrderDate", "DDF", render(Dsl.differenceInDays(m.date(OD), m.date(DD)).lt(3)),
            filled = mapOf(OD to "2026-01-01", DD to "2026-06-01")))

    @Test fun diffDaysOneMalformedSuppressed() =
        assertEquals(0, agree("../OrderDate", "DDM", render(Dsl.differenceInDays(m.date(OD), m.date(DD)).lt(3)),
            filled = mapOf(OD to "not-a-date", DD to "2026-06-01")))

    @Test fun diffDaysOneEmptyPolarityOmission() =
        assertEquals("OMISSION_ERROR", agreePolarity("../OrderDate", "DDP",
            render(Dsl.differenceInDays(m.date(OD), m.date(DD)).lt(3)), filled = mapOf(DD to "2026-06-01"), empty = listOf(OD)))

    @Test fun diffHoursOneEmptyFires() =
        assertEquals(1, agree("../ScheduledAt", "DH", render(Dsl.differenceInHours(m.dateTime(SA), m.dateTime(CA)).lt(3)),
            filled = mapOf(CA to "2026-06-01T10:00:00"), empty = listOf(SA)))

    @Test fun diffMinutesOneEmptyFires() =
        assertEquals(1, agree("../ScheduledAt", "DMi", render(Dsl.differenceInMinutes(m.dateTime(SA), m.dateTime(CA)).lt(3)),
            filled = mapOf(CA to "2026-06-01T10:00:00"), empty = listOf(SA)))

    @Test fun diffSecondsOneEmptyFires() =
        assertEquals(1, agree("../ScheduledAt", "DS", render(Dsl.differenceInSeconds(m.dateTime(SA), m.dateTime(CA)).lt(3)),
            filled = mapOf(CA to "2026-06-01T10:00:00"), empty = listOf(SA)))

    private companion object {
        const val CN = "/Order/CustomerName"
        const val PC = "/Order/ProductCode"
        const val OD = "/Order/OrderDate"
        const val DD = "/Order/DeliveryDate"
        const val PT = "/Order/PickupTime"
        const val SA = "/Order/ScheduledAt"
        const val CA = "/Order/CompletedAt"
    }
}
