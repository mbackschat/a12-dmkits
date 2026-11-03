package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.ModelTimeZone
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Differential — representative Europe/Berlin historical discriminators (IF155). The dynamic-Groovy arms
 * cover pre-1916/1916, 1945, 1947, and 1980 behavior; the generated-Java arm tri-checks the 1980 transition
 * plus a summer control. Each transition arm is offset-sensitive and would DIVERGE under the old 1996+-only
 * rule (recorded per arm):
 *
 *  - **1980 fall-back was the last Sunday of SEPTEMBER**, not October — the old EU-October rule kept CEST, so
 *    it counted one hour too few across 1980-09-28.
 *  - **1945/47 double-summer CEMT (+3 h)** — an offset the two-offset rule could not represent at all.
 *  - **before 1893-04-01 Berlin is on local mean time** (+00:53:28, which kernel 31.1.1's `java.time` keeps), then
 *    flat CET with NO DST to 1916 — a same-era pair is plain wall arithmetic, and the first-ever DST spring (1916)
 *    is a real transition (KF665).
 *
 * The exhaustive both-strategy guard is [BerlinTransitionOracleDiffTest]: immediately before, at, and after
 * every one of the 62 pinned transitions. The independent table/mutation and kernel-free JVM+JS lock is
 * `interpreter … BerlinHistoricalTzTest`. Modern-regime cases: [DstTimeZoneDiffTest] /
 * [WallDayArithmeticDiffTest].
 */
class BerlinHistoricalTzDiffTest {

    private val berlinOrder = RuntimeLaws.model("models/order.dm.json")
    private val fields = listOf(
        FieldDef(SA, Kind.DATE_TIME, format = "yyyy-MM-dd'T'HH:mm:ss"),
        FieldDef(CA, Kind.DATE_TIME, format = "yyyy-MM-dd'T'HH:mm:ss"),
    )

    /**
     * Kernel (dynamic-Groovy) and interpreter must agree on whether [cond] fires over the two datetime values;
     * returns the kernel's verdict after asserting the interpreter's equals it.
     */
    private fun agree(cond: String, sa: String, ca: String, msg: String): Boolean {
        val k = RuntimeLaws.fires(RuntimeLaws.withRule(berlinOrder, "/Order", "R", "../ScheduledAt", "TA", cond), "TA",
            RuntimeLaws.doc().group("/Order", 1).field(SA, sa, 1, 1).field(CA, ca, 1, 1))
        val def = ModelDef(fields, listOf(RuleDef("/Order/R", "/Order", cond, "TA", "../ScheduledAt")),
            timeZone = ModelTimeZone.EUROPE_BERLIN)
        val iDoc = def.newDocument().field(SA, sa, 1, 1).field(CA, ca, 1, 1)
        val i = DmInterpreter(def).validate(iDoc).messages.any { it.code == "TA" }
        assertEquals(k, i, "$msg (sa=$sa ca=$ca) [k=$k i=$i]")
        return k
    }

    // ---- historical transitions the old 1996+-only rule got wrong (each arm FIRES: value = the kernel truth) --
    @Test fun sept1980FallBackCountsThreeRealHours() = assertTrue(agree(
        "DifferenceInHours(ScheduledAt, CompletedAt) == 3",
        "1980-09-28T01:30:00", "1980-09-28T03:30:00",
        "DST ended 1980-09-28 (last Sunday of September) — 01:30 CEST → 03:30 CET is 3 real hours (the EU-October rule counted 2)"))

    @Test fun midsummer1945CrossesIntoCemt() = assertTrue(agree(
        "DifferenceInHours(ScheduledAt, CompletedAt) == 47",
        "1945-05-23T12:00:00", "1945-05-25T12:00:00",
        "1945-05-24 sprang forward CEST → CEMT (+3 h), so 48 wall hours are 47 real"))

    @Test fun the1947CemtWindowLosesAnHourToo() = assertTrue(agree(
        "DifferenceInHours(ScheduledAt, CompletedAt) == 47",
        "1947-05-10T12:00:00", "1947-05-12T12:00:00",
        "1947-05-11 sprang forward into CEMT — a second double-summer window"))

    @Test fun the1916FirstEverDstSpringIsAResolvedTransition() = assertTrue(agree(
        "DifferenceInHours(ScheduledAt, CompletedAt) == 2",
        "1916-04-30T22:30:00", "1916-05-01T01:30:00",
        "the first-ever DST spring (1916-04-30, CET → CEST) — pre-1916 CET confirmed, 3 wall hours are 2 real"))

    @Test fun the1893SwitchFromLocalMeanTimeShortensTheNight() {
        assertTrue(agree("DifferenceInMinutes(ScheduledAt, CompletedAt) == 113",
            "1893-03-31T23:00:00", "1893-04-01T01:00:00",
            "LMT +00:53:28 → CET at midnight: two wall hours are 113 real minutes"))
    }

    @Test fun pre1916SameEraIsPlainWallArithmetic() = assertTrue(agree(
        "DifferenceInHours(ScheduledAt, CompletedAt) == 48",
        "1850-06-01T00:00:00", "1850-06-03T00:00:00",
        "pre-1916 has NO DST — a same-era pair is plain wall arithmetic (the old rule invented summer time here)"))

    // ---- a historical spring gap resolves forward by the gap, exactly like a modern one (KF665) --------------
    @Test fun the1945SpringGapResolvesForwardByTheGap() {
        val gap = "1945-05-24T02:30:00"
        assertTrue(agree("DifferenceInMinutes(ScheduledAt, CompletedAt) == 0", gap, "1945-05-24T03:30:00",
            "a 1945 gap wall time is the instant one hour later, so it is no minutes from 03:30"))
        val def = ModelDef(fields, emptyList(), timeZone = ModelTimeZone.EUROPE_BERLIN)
        val codes = DmInterpreter(def).validate(def.newDocument().field(SA, gap, 1, 1)).messages.map { it.code }
        assertTrue("datumFormatFalsch" !in codes,
            "the 1945 spring-gap wall time (CEST→CEMT) is no format error on kernel 31.1.1, got $codes")
    }

    // ---- tri-check one historical case against generated Java (the second kernel strategy) -----------------
    @Test fun historicalBerlinTriChecksGeneratedJava() {
        val cond = "DifferenceInHours(ScheduledAt, CompletedAt) == 3"
        val model = RuntimeLaws.withRule(berlinOrder, "/Order", "R", "../ScheduledAt", "TA", cond)
        val def = ModelDef(fields, listOf(RuleDef("/Order/R", "/Order", cond, "TA", "../ScheduledAt")),
            timeZone = ModelTimeZone.EUROPE_BERLIN)
        val javaEngine = RuntimeLaws.kernelJavaEngine(model)
        listOf(
            Triple("1980-09-28T01:30:00", "1980-09-28T03:30:00", true),   // Sept-end fall-back → 3 real hours
            Triple("1980-06-15T01:30:00", "1980-06-15T05:30:00", false),  // plain summer day → 4 real hours, != 3
        ).forEach { (sa, ca, expected) ->
            val kDoc = RuntimeLaws.doc().group("/Order", 1).field(SA, sa, 1, 1).field(CA, ca, 1, 1)
            val iDoc = def.newDocument().group("/Order", 1).field(SA, sa, 1, 1).field(CA, ca, 1, 1)
            val groovy = RuntimeLaws.messages(model, kDoc).any { it.code() == "TA" }
            val javaStatic = javaEngine.messages(javaEngine.instance(kDoc)).any { it.code() == "TA" }
            val interpreter = DmInterpreter(def).validateFull(iDoc).any { it.code == "TA" }
            assertEquals(expected, groovy, "historical Berlin $sa → $ca discriminator")
            assertEquals(groovy, javaStatic, "historical Berlin $sa → $ca — Groovy-dynamic vs generated Java")
            assertEquals(groovy, interpreter, "historical Berlin $sa → $ca — Groovy-dynamic vs interpreter")
        }
    }

    private companion object {
        const val SA = "/Order/ScheduledAt"
        const val CA = "/Order/CompletedAt"
    }
}
