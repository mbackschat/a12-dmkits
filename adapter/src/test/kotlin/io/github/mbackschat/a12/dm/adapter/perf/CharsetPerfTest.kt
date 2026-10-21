package io.github.mbackschat.a12.dm.adapter.perf

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.LegalCharset
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.ValidationOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Perf guard for the [LegalCharset] SPI (IF43) — a CUSTOM charset must be ~as fast as the [LegalCharset.BMP]
 * default. `allows` runs per code point of every valued field (the formal-error hot path), so it must be an
 * inlined O(1) bitset read, NOT a per-code-point virtual call: the first SPI cut used a SAM `fun interface`,
 * whose per-code-point virtual dispatch blew up the full perf suite (a 16-min CPU burn). This times `validateFull`
 * over a large string-heavy document under the BMP default vs a DIN-91379-style range charset and asserts the
 * custom charset is within a small factor of BMP — a virtual-call regression is 10×+ and trips this. JVM-only,
 * current-thread CPU time (algorithmic work without another Gradle fork's preemption). Pure engine, no kernel.
 */
@Tag("perf")
class CharsetPerfTest {

    private val s = "/C/S"
    private val def = model(N)
    private val din = LegalCharset.ofRanges(0x20..0x7E, 0x00A0..0x024F, 0x1E00..0x1EFF, 0x2000..0x206F)
    // A DIN-style charset that ALSO declares combining graphemes (IF151) — the atomic-trie membership path.
    private val dinWithGraphemes = LegalCharset.of(
        ranges = listOf(0x20..0x7E, 0x00A0..0x024F, 0x1E00..0x1EFF, 0x2000..0x206F),
        graphemes = listOf("é", "á", "ö"))   // decomposed é, á, ö

    private fun doc(): Document {
        val d = def.newDocument()
        for (i in 1..N) d.field(s, "Auftragsbestätigung Nr. $i — Île-de-France", i, 1)   // all within DIN
        return d
    }

    private fun model(rows: Int): ModelDef =
        ModelDef(listOf(FieldDef(s, Kind.STRING)), emptyList(), declaredReps = mapOf("/C" to rows))

    /** A document whose values carry configured DECOMPOSED graphemes, so every row exercises the trie path. */
    private fun graphemeDoc(model: ModelDef, rows: Int): Document {
        val d = model.newDocument()
        for (i in 1..rows) d.field(s, "Café Résumé nr $i", i, 1)   // decomposed é ×3, rest ASCII
        return d
    }

    @Test fun customCharsetIsWithinAFactorOfBmp() {
        val d = doc()
        fun run(c: LegalCharset) = DmInterpreter(def, legalCharset = c).validate(d)
        repeat(WARM) { run(LegalCharset.BMP); run(din) }                       // warm both paths
        val bmpMs = PerfHarness.measureCurrentThreadCpu(warmup = 0, iters = 1) {
            repeat(REPEATS) { run(LegalCharset.BMP) }
        }.medianMs
        val customMs = PerfHarness.measureCurrentThreadCpu(warmup = 0, iters = 1) {
            repeat(REPEATS) { run(din) }
        }.medianMs
        assertTrue(customMs < bmpMs * FACTOR + 50.0,
            "custom charset (${customMs}ms CPU) must be within ${FACTOR}× the BMP default (${bmpMs}ms CPU) — " +
                "a per-codepoint virtual call regressed")
    }

    @Test fun theBmpPathStaysBelowTheFourfoldGrowthCatastropheFloor() {
        // Re-measured before failing: routine tier, so the NIGHTLY runs this on a hosted runner, where a
        // contended slice inflates a ratio by a few percent. (Until 2026-08-13 this class was untagged and the
        // reason read "runs on every build including shared CI"; the retry matters just as much now, only the
        // shared machine is the runner rather than the push lane.) A virtual-call blowup is ~16x and breaches
        // every attempt, so the floor is unchanged and no false green is admitted.
        assertCharsetGrowthLane("BMP", LegalCharset.BMP)
    }

    /**
     * A SECOND test method, not a second call inside the first (split 2026-08-13). Two lanes in one method meant
     * the first one's failure aborted before the second recorded anything, so the grapheme lane could not be
     * armed at all while the BMP lane was still unanchored — visible as `charset grapheme: 0 rows` in a run that
     * recorded everything else.
     */
    @Test fun theGraphemePathStaysBelowTheFourfoldGrowthCatastropheFloor() {
        assertCharsetGrowthLane("grapheme", dinWithGraphemes)
    }

    /** One charset's N/4N lane: exact-semantics preflight, warm, then the shared growth floor + anchor. */
    private fun assertCharsetGrowthLane(label: String, charset: LegalCharset) {
        val nModel = model(GROWTH_N)
        val fourNModel = model(GROWTH_N * 4)
        val nEngine = DmInterpreter(nModel, legalCharset = charset)
        val fourNEngine = DmInterpreter(fourNModel, legalCharset = charset)
        val nDoc = graphemeDoc(nModel, GROWTH_N)
        val fourNDoc = graphemeDoc(fourNModel, GROWTH_N * 4)
        fun runN(): ValidationOutcome = nEngine.validate(nDoc)
        fun runFourN(): ValidationOutcome = fourNEngine.validate(fourNDoc)

        assertClean(runN(), "N semantic preflight")
        assertClean(runFourN(), "4N semantic preflight")
        repeat(WARM) { runN(); runFourN() }
        PerfHarness.assertSubQuadraticLane(
            label = "charset $label",
            small = GROWTH_N,
            identityAt = { rows ->
                PerfHarness.fingerprintedLabel("charset-$label", "$charset|graphemeDoc|rows=$rows")
            },
            culprit = "the per-code-point membership test regressing to a virtual call",
            batch = NOISE_BATCH,
        ) { rows ->
            val run = if (rows == GROWTH_N) ::runN else ::runFourN
            PerfHarness.measureCurrentThreadCpu(warmup = 0, iters = REPEATS) {
                repeat(NOISE_BATCH) { run() }
            }
        }
    }

    private fun assertClean(outcome: ValidationOutcome, label: String) {
        assertEquals(emptyList<Any>(), outcome.messages, "$label: no formal messages")
        assertEquals(emptyList<Any>(), outcome.unsupported, "$label: no unsupported constructs")
    }

    private companion object {
        const val N = 50_000
        /** See `PerfGateTest.NOISE_BATCH`: BMP measured 1.15 ms at n=8000, deep in the noisy band. */
        const val NOISE_BATCH = 8

        const val GROWTH_N = 8_000
        const val WARM = 3
        const val REPEATS = 5
        const val FACTOR = 6                       // generous; a virtual-call blowup is 10×+ (+ 50 ms slack for tiny timings)
    }
}
