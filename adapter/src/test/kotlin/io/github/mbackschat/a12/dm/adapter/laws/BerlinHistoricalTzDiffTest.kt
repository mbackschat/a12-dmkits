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
 * Differential — Europe/Berlin's **complete history** (IG83): the pinned transition table (`DateMath`, from the
 * kernel's own `java.util.TimeZone` step function) must reproduce the REAL kernel across the historical
 * transitions the pre-2026 1996+-only rule got wrong. Each arm crosses a genuine historical transition, so the
 * real-hour count is offset-sensitive and would DIVERGE under the old rule (recorded per arm):
 *
 *  - **1980 fall-back was the last Sunday of SEPTEMBER**, not October — the old EU-October rule kept CEST, so
 *    it counted one hour too few across 1980-09-28.
 *  - **1945/47 double-summer CEMT (+3 h)** — an offset the two-offset rule could not represent at all.
 *  - **pre-1916 is flat CET** (the kernel path drops the LMT) with NO DST — a same-era pair is plain wall
 *    arithmetic, and the first-ever DST spring (1916) is a real transition.
 *
 * Companion kernel-free lock (JVM + JS): `interpreter … BerlinHistoricalTzTest`. Modern-regime cases:
 * [DstTimeZoneDiffTest] / [WallDayArithmeticDiffTest].
 */
class BerlinHistoricalTzDiffTest {

    private val berlinOrder = RuntimeLaws.model("models/order.dm.json")
    private val fields = listOf(
        FieldDef(SA, Kind.DATE_TIME, format = "yyyy-MM-dd'T'HH:mm:ss"),
        FieldDef(CA, Kind.DATE_TIME, format = "yyyy-MM-dd'T'HH:mm:ss"),
    )

    /** Kernel (dynamic-Groovy) and interpreter must agree on whether [cond] fires over the two datetime values. */
    private fun agree(cond: String, sa: String, ca: String, msg: String) {
        val k = RuntimeLaws.fires(RuntimeLaws.withRule(berlinOrder, "/Order", "R", "../ScheduledAt", "TA", cond), "TA",
            RuntimeLaws.doc().group("/Order", 1).field(SA, sa, 1, 1).field(CA, ca, 1, 1))
        val def = ModelDef(fields, listOf(RuleDef("/Order/R", "/Order", cond, "TA", "../ScheduledAt")),
            timeZone = ModelTimeZone.EUROPE_BERLIN)
        val iDoc = def.newDocument().field(SA, sa, 1, 1).field(CA, ca, 1, 1)
        val i = DmInterpreter(def).validate(iDoc).messages.any { it.code == "TA" }
        assertEquals(k, i, "$msg (sa=$sa ca=$ca) [k=$k i=$i]")
    }

    // ---- historical transitions the old 1996+-only rule got wrong (each arm FIRES: value = the kernel truth) --
    @Test fun sept1980FallBackCountsThreeRealHours() = agree(
        "DifferenceInHours(ScheduledAt, CompletedAt) == 3",
        "1980-09-28T01:30:00", "1980-09-28T03:30:00",
        "DST ended 1980-09-28 (last Sunday of September) — 01:30 CEST → 03:30 CET is 3 real hours (the EU-October rule counted 2)")

    @Test fun midsummer1945CrossesIntoCemt() = agree(
        "DifferenceInHours(ScheduledAt, CompletedAt) == 47",
        "1945-05-23T12:00:00", "1945-05-25T12:00:00",
        "1945-05-24 sprang forward CEST → CEMT (+3 h), so 48 wall hours are 47 real")

    @Test fun the1947CemtWindowLosesAnHourToo() = agree(
        "DifferenceInHours(ScheduledAt, CompletedAt) == 47",
        "1947-05-10T12:00:00", "1947-05-12T12:00:00",
        "1947-05-11 sprang forward into CEMT — a second double-summer window")

    @Test fun the1916FirstEverDstSpringIsAResolvedTransition() = agree(
        "DifferenceInHours(ScheduledAt, CompletedAt) == 2",
        "1916-04-30T22:30:00", "1916-05-01T01:30:00",
        "the first-ever DST spring (1916-04-30, CET → CEST) — pre-1916 CET confirmed, 3 wall hours are 2 real")

    @Test fun pre1916SameEraIsPlainWallArithmetic() = agree(
        "DifferenceInHours(ScheduledAt, CompletedAt) == 48",
        "1850-06-01T00:00:00", "1850-06-03T00:00:00",
        "pre-1916 has NO DST — a same-era pair is plain wall arithmetic (the old rule invented summer time here)")

    // ---- a historical spring gap is a FORMAL error, exactly like a modern one ------------------------------
    @Test fun the1945SpringGapIsAFormalError() {
        val gap = "1945-05-24T02:30:00"
        agree("DifferenceInMinutes(ScheduledAt, CompletedAt) == 0", gap, "1945-05-24T03:30:00",
            "a 1945 gap operand suppresses the rule, like any malformed datetime")
        val def = ModelDef(fields, emptyList(), timeZone = ModelTimeZone.EUROPE_BERLIN)
        val codes = DmInterpreter(def).validate(def.newDocument().field(SA, gap, 1, 1)).messages.map { it.code }
        assertTrue("datumFormatFalsch" in codes,
            "the 1945 spring-gap wall time (CEST→CEMT) is datumFormatFalsch, got $codes")
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
