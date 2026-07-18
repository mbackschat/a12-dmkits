package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.ModelTimeZone
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Differential — the **model-timezone family** (IG62): a `Europe/Berlin` model (`content.modelConfig.timeZone`,
 * as `order.dm.json` declares) applies the zone to every DATE_TIME parse, so datetime arithmetic is DST-aware.
 * Kernel-probed truths this file locks (KERNEL-FINDINGS §6):
 *
 *  - `DifferenceIn{Hours,Minutes,Seconds}` subtract zone-parsed **instants** — a pair straddling the
 *    spring-forward gap spans 1 real hour per 2 wall hours ([springForwardDiffCountsRealHours]), a fall-back
 *    pair 3 per 2 ([fallBackDiffCountsRealHours]).
 *  - An **ambiguous** fall-back wall time (`02:30` occurs twice) parses as **standard time** (CET) — the
 *    `GregorianCalendar` resolution ([ambiguousWallTimeResolvesToStandardTime]).
 *  - A **nonexistent** spring-gap wall time (`2024-03-31T02:30:00`) is the formal error `datumFormatFalsch` —
 *    the kernel's non-lenient zone-set parse rejects it; a referencing rule is suppressed
 *    ([gapWallTimeIsAFormalError]).
 *  - `Add{Hours,…}` is **instant arithmetic**, and a chained result keeps its exact instant: adding 1 h to
 *    `01:30` CEST lands ON the ambiguous hour as the *daylight* instant, which is NOT the instant the stored
 *    `"02:30:00"` re-parses to (standard) — 60 minutes apart ([chainedAddKeepsItsInstantIdentity]).
 *  - Under the default `UTC` everything stays the naive 86 400 s/day arithmetic ([utcModelStaysNaive]).
 */
class DstTimeZoneDiffTest {

    private val berlinOrder = RuntimeLaws.model("models/order.dm.json")
    private val utcOrder = berlinOrder.replace("\"timeZone\": \"Europe/Berlin\"", "\"timeZone\": \"UTC\"")

    private val fields = listOf(
        FieldDef(SA, Kind.DATE_TIME, format = "yyyy-MM-dd'T'HH:mm:ss"),
        FieldDef(CA, Kind.DATE_TIME, format = "yyyy-MM-dd'T'HH:mm:ss"),
    )
    private val m = BoundModel.ofJson(berlinOrder)
    private fun render(c: Condition): String = DslRenderer().render(c)

    /** Kernel and interpreter must agree on `cond` firing over ScheduledAt=[sa], CompletedAt=[ca]. */
    private fun agree(cond: String, sa: String, ca: String, msg: String,
                      model: String = berlinOrder, tz: ModelTimeZone = ModelTimeZone.EUROPE_BERLIN) {
        val k = RuntimeLaws.fires(RuntimeLaws.withRule(model, "/Order", "R", "../ScheduledAt", "TA", cond), "TA",
            RuntimeLaws.doc().group("/Order", 1).field(SA, sa, 1, 1).field(CA, ca, 1, 1))
        val def = ModelDef(fields, listOf(RuleDef("/Order/R", "/Order", cond, "TA", "../ScheduledAt")), timeZone = tz)
        val iDoc = def.newDocument().field(SA, sa, 1, 1).field(CA, ca, 1, 1)
        val i = DmInterpreter(def).validate(iDoc).messages.any { it.code == "TA" }
        assertEquals(k, i, "$msg (sa=$sa ca=$ca) [k=$k i=$i]")
    }

    // ---- instant-based diffs across the two transitions --------------------------------------------------
    @Test fun springForwardDiffCountsRealHours() = agree(
        render(m.dateTime(SA).filled().and(Dsl.differenceInHours(m.dateTime(SA), m.dateTime(CA)).eq(1))),
        "2024-03-31T01:30:00", "2024-03-31T03:30:00", "01:30 CET → 03:30 CEST spans 1 real hour, not wall 2")

    @Test fun fallBackDiffCountsRealHours() = agree(
        render(m.dateTime(SA).filled().and(Dsl.differenceInHours(m.dateTime(SA), m.dateTime(CA)).eq(3))),
        "2024-10-27T01:30:00", "2024-10-27T03:30:00", "01:30 CEST → 03:30 CET spans 3 real hours, not wall 2")

    @Test fun ambiguousWallTimeResolvesToStandardTime() = agree(
        render(m.dateTime(SA).filled().and(Dsl.differenceInMinutes(m.dateTime(SA), m.dateTime(CA)).eq(120))),
        "2024-10-27T01:30:00", "2024-10-27T02:30:00", "the repeated 02:30 parses as CET (standard) → 120 min")

    // ---- the spring gap is a FORMAL error, suppressing the referencing rule ------------------------------
    @Test fun gapWallTimeIsAFormalError() {
        val gap = "2024-03-31T02:30:00"
        // Kernel: the value draws datumFormatFalsch and the rule does not fire (probe P1/P3).
        agree(render(m.dateTime(SA).filled().and(Dsl.differenceInMinutes(m.dateTime(SA), m.dateTime(CA)).eq(0))),
            gap, "2024-03-31T03:30:00", "a gap operand suppresses the rule")
        // Interpreter side must also EMIT the formal code itself.
        val def = ModelDef(fields, emptyList(), timeZone = ModelTimeZone.EUROPE_BERLIN)
        val codes = DmInterpreter(def).validate(def.newDocument().field(SA, gap, 1, 1)).messages.map { it.code }
        assertTrue("datumFormatFalsch" in codes,
            "a Europe/Berlin DATE_TIME in the spring gap must be datumFormatFalsch, got $codes")
    }

    // ---- chained Add keeps the exact instant (the kernel's VkDate identity, probe P5) ---------------------
    @Test fun chainedAddKeepsItsInstantIdentity() {
        // AddHours(01:30 CEST, 1) = the DAYLIGHT instant of the ambiguous hour; the stored "02:30:00" re-parses
        // as STANDARD — 60 real minutes later. Equality is false, the diff is 60.
        agree(render(m.dateTime(SA).filled()
                .and(Dsl.differenceInMinutes(Dsl.addHours(m.dateTime(SA), 1), m.dateTime(CA)).eq(60))),
            "2024-10-27T01:30:00", "2024-10-27T02:30:00", "AddHours lands on the daylight side: 60 min to the stored value")
        agree("AddHours(ScheduledAt, 1) == [CompletedAt]",
            "2024-10-27T01:30:00", "2024-10-27T02:30:00", "the equal-looking wall times are different instants")
    }

    @Test fun addAcrossTheGapLandsOnTheRolledWallTime() = agree(
        "AddHours(ScheduledAt, 1) == [CompletedAt]",
        "2024-03-31T01:30:00", "2024-03-31T03:30:00", "01:30 CET + 1 h = 03:30 CEST (the same instant)")

    // ---- controls -----------------------------------------------------------------------------------------
    @Test fun utcModelStaysNaive() = agree(
        render(m.dateTime(SA).filled().and(Dsl.differenceInHours(m.dateTime(SA), m.dateTime(CA)).eq(2))),
        "2024-03-31T01:30:00", "2024-03-31T03:30:00", "a UTC model has no DST: 2 plain hours",
        model = utcOrder, tz = ModelTimeZone.UTC)

    @Test fun ambiguousExtractionReadsWallFields() = agree(
        render(m.dateTime(SA).filled().and(Dsl.hoursFromTime(Dsl.timeFromDateTime(m.dateTime(SA))).eq(2))),
        "2024-10-27T02:30:00", "2024-10-27T12:00:00", "extraction reads the wall clock: hour 2 either way")

    @Test fun nonStraddlingBerlinPairIsUnchanged() = agree(
        render(m.dateTime(SA).filled().and(Dsl.differenceInHours(m.dateTime(SA), m.dateTime(CA)).eq(5))),
        "2024-06-15T22:00:00", "2024-06-16T03:30:00", "no transition crossed → plain arithmetic")

    private companion object {
        const val SA = "/Order/ScheduledAt"
        const val CA = "/Order/CompletedAt"
    }
}
