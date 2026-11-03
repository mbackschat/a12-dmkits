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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — the **model-timezone family** (IG62): a `Europe/Berlin` model (`content.modelConfig.timeZone`,
 * as `order.dm.json` declares) applies the zone to every DATE_TIME parse, so datetime arithmetic is DST-aware.
 * Kernel-probed truths this file locks (KERNEL-FINDINGS §6):
 *
 *  - `DifferenceIn{Hours,Minutes,Seconds}` subtract zone-parsed **instants** — a pair straddling the
 *    spring-forward gap spans 1 real hour per 2 wall hours ([springForwardDiffCountsRealHours]), a fall-back
 *    pair 3 per 2 ([fallBackDiffCountsRealHours]).
 *  - An **ambiguous** fall-back wall time (`02:30` occurs twice) parses at its **earlier, summer-time** offset
 *    (CEST) on kernel 31.1.1; 30.8.1's `GregorianCalendar` took standard time (KERNEL-FINDINGS KF665,
 *    [ambiguousWallTimeResolvesToSummerTime]).
 *  - A **nonexistent** spring-gap wall time (`2024-03-31T02:30:00`) is a value moved forward by the gap, the instant
 *    of `03:30` CEST, and draws no formal code; 30.8.1 rejected it as `datumFormatFalsch` (KF665,
 *    [gapWallTimeMovesForwardByTheGap]).
 *  - `Add{Hours,…}` is **instant arithmetic**, and a chained result keeps its exact instant: adding 2 h to
 *    `01:30` CEST lands ON the ambiguous hour as the *standard-time* instant, which is NOT the instant the stored
 *    `"02:30:00"` re-parses to (summer time) — 60 minutes apart ([chainedAddKeepsItsInstantIdentity]).
 *  - Under the default `UTC`, wall clock equals instant time for the sub-day operations
 *    ([utcModelStaysNaive]); day differences retain their separately locked calendar-step rule.
 */
class DstTimeZoneDiffTest {

    private val berlinOrder = RuntimeLaws.model("models/order.dm.json")
    private val utcOrder = berlinOrder.replace("\"timeZone\": \"Europe/Berlin\"", "\"timeZone\": \"UTC\"")

    private val fields = listOf(
        FieldDef(SA, Kind.DATE_TIME, format = "yyyy-MM-dd'T'HH:mm:ss"),
        FieldDef(CA, Kind.DATE_TIME, format = "yyyy-MM-dd'T'HH:mm:ss"),
    )
    private val m = BoundModel.ofJson(berlinOrder)
    private fun render(c: Condition): String = RuntimeLaws.render(c)

    /**
     * Kernel and interpreter must agree on `cond` firing over ScheduledAt=[sa], CompletedAt=[ca]; a given [fires]
     * also pins the kernel's answer, so the agreement cannot hold vacuously.
     */
    private fun agree(cond: String, sa: String, ca: String, msg: String,
                      model: String = berlinOrder, tz: ModelTimeZone = ModelTimeZone.EUROPE_BERLIN,
                      fires: Boolean? = null) {
        val k = RuntimeLaws.fires(RuntimeLaws.withRule(model, "/Order", "R", "../ScheduledAt", "TA", cond), "TA",
            RuntimeLaws.doc().group("/Order", 1).field(SA, sa, 1, 1).field(CA, ca, 1, 1))
        val def = ModelDef(fields, listOf(RuleDef("/Order/R", "/Order", cond, "TA", "../ScheduledAt")), timeZone = tz)
        val iDoc = def.newDocument().field(SA, sa, 1, 1).field(CA, ca, 1, 1)
        val i = DmInterpreter(def).validate(iDoc).messages.any { it.code == "TA" }
        assertEquals(k, i, "$msg (sa=$sa ca=$ca) [k=$k i=$i]")
        if (fires != null) assertEquals(fires, k, "$msg — the kernel's answer (sa=$sa ca=$ca)")
    }

    // ---- instant-based diffs across the two transitions --------------------------------------------------
    @Test fun springForwardDiffCountsRealHours() = agree(
        render(m.dateTime(SA).filled().and(Dsl.differenceInHours(m.dateTime(SA), m.dateTime(CA)).eq(1))),
        "2024-03-31T01:30:00", "2024-03-31T03:30:00", "01:30 CET → 03:30 CEST spans 1 real hour, not wall 2")

    @Test fun fallBackDiffCountsRealHours() = agree(
        render(m.dateTime(SA).filled().and(Dsl.differenceInHours(m.dateTime(SA), m.dateTime(CA)).eq(3))),
        "2024-10-27T01:30:00", "2024-10-27T03:30:00", "01:30 CEST → 03:30 CET spans 3 real hours, not wall 2")

    @Test fun ambiguousWallTimeResolvesToSummerTime() = agree(
        render(m.dateTime(SA).filled().and(Dsl.differenceInMinutes(m.dateTime(SA), m.dateTime(CA)).eq(60))),
        "2024-10-27T01:30:00", "2024-10-27T02:30:00", "the repeated 02:30 parses as CEST (earlier) → 60 min",
        fires = true)

    // ---- the spring gap is a value, moved forward by the gap ----------------------------------------------
    @Test fun gapWallTimeMovesForwardByTheGap() {
        val gap = "2024-03-31T02:30:00"
        agree(render(m.dateTime(SA).filled().and(Dsl.differenceInMinutes(m.dateTime(SA), m.dateTime(CA)).eq(0))),
            gap, "2024-03-31T03:30:00", "02:30 in the gap is the instant of 03:30 CEST", fires = true)
        val def = ModelDef(fields, emptyList(), timeZone = ModelTimeZone.EUROPE_BERLIN)
        val codes = DmInterpreter(def).validate(def.newDocument().field(SA, gap, 1, 1)).messages.map { it.code }
        assertEquals(emptyList<String>(), codes, "a Europe/Berlin DATE_TIME in the spring gap draws no formal code")
    }

    // ---- chained Add keeps the exact instant (the kernel's VkDate identity, probe P5) ---------------------
    @Test fun chainedAddKeepsItsInstantIdentity() {
        // AddHours(01:30 CEST, 2) = the STANDARD instant of the ambiguous hour; the stored "02:30:00" re-parses
        // as DAYLIGHT — 60 real minutes earlier. Equality is false, the diff is -60.
        agree(render(m.dateTime(SA).filled()
                .and(Dsl.differenceInMinutes(Dsl.addHours(m.dateTime(SA), 2), m.dateTime(CA)).eq(-60))),
            "2024-10-27T01:30:00", "2024-10-27T02:30:00", "AddHours lands on the standard side: -60 min to the stored value",
            fires = true)
        agree("AddHours(ScheduledAt, 2) == [CompletedAt]",
            "2024-10-27T01:30:00", "2024-10-27T02:30:00", "the equal-looking wall times are different instants",
            fires = false)
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

    @Test
    fun modelTimeZoneTriChecksGeneratedJava() {
        val cond = render(m.dateTime(SA).filled().and(Dsl.differenceInHours(m.dateTime(SA), m.dateTime(CA)).eq(1)))
        val model = RuntimeLaws.withRule(berlinOrder, "/Order", "R", "../ScheduledAt", "TA", cond)
        val def = ModelDef(
            fields,
            listOf(RuleDef("/Order/R", "/Order", cond, "TA", "../ScheduledAt")),
            timeZone = ModelTimeZone.EUROPE_BERLIN,
        )
        val javaEngine = RuntimeLaws.kernelJavaEngine(model)

        listOf(
            Triple("2024-03-31T01:30:00", "2024-03-31T03:30:00", true),
            Triple("2024-06-15T01:30:00", "2024-06-15T03:30:00", false),
        ).forEach { (sa, ca, expected) ->
            val kDoc = RuntimeLaws.doc().group("/Order", 1)
                .field(SA, sa, 1, 1).field(CA, ca, 1, 1)
            val iDoc = def.newDocument().group("/Order", 1)
                .field(SA, sa, 1, 1).field(CA, ca, 1, 1)
            val groovy = RuntimeLaws.messages(model, kDoc).any { it.code() == "TA" }
            val javaStatic = javaEngine.messages(javaEngine.instance(kDoc)).any { it.code() == "TA" }
            val interpreter = DmInterpreter(def).validateFull(iDoc).any { it.code == "TA" }

            assertEquals(expected, groovy, "Berlin $sa → $ca discriminator")
            assertEquals(groovy, javaStatic, "Berlin $sa → $ca — Groovy-dynamic vs generated Java")
            assertEquals(groovy, interpreter, "Berlin $sa → $ca — Groovy-dynamic vs interpreter")
        }
    }

    private companion object {
        const val SA = "/Order/ScheduledAt"
        const val CA = "/Order/CompletedAt"
    }
}
