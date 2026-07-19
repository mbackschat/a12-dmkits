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
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTime

/**
 * Perf guard for the [LegalCharset] SPI (IF43) — a CUSTOM charset must be ~as fast as the [LegalCharset.BMP]
 * default. `allows` runs per code point of every valued field (the formal-error hot path), so it must be an
 * inlined O(1) bitset read, NOT a per-code-point virtual call: the first SPI cut used a SAM `fun interface`,
 * whose per-code-point virtual dispatch blew up the full perf suite (a 16-min CPU burn). This times `validateFull`
 * over a large string-heavy document under the BMP default vs a DIN-91379-style range charset and asserts the
 * custom charset is within a small factor of BMP — a virtual-call regression is 10×+ and trips this. JVM-only
 * (reliable timing; the ratio cancels machine speed). Pure engine, no kernel.
 */
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
        for (i in 1..N) d.field(s, "Auftragsbestätigung Nr. $i — Île-de-France", 1, i, 1)   // all within DIN
        return d
    }

    private fun model(rows: Int): ModelDef =
        ModelDef(listOf(FieldDef(s, Kind.STRING)), emptyList(), declaredReps = mapOf("/C" to rows))

    /** A document whose values carry configured DECOMPOSED graphemes, so every row exercises the trie path. */
    private fun graphemeDoc(model: ModelDef, rows: Int): Document {
        val d = model.newDocument()
        for (i in 1..rows) d.field(s, "Café Résumé nr $i", 1, i, 1)   // decomposed é ×3, rest ASCII
        return d
    }

    @Test fun customCharsetIsWithinAFactorOfBmp() {
        val d = doc()
        fun run(c: LegalCharset) = DmInterpreter(def, legalCharset = c).validate(d)
        repeat(WARM) { run(LegalCharset.BMP); run(din) }                       // warm both paths
        val bmp = measureTime { repeat(REPEATS) { run(LegalCharset.BMP) } }
        val custom = measureTime { repeat(REPEATS) { run(din) } }
        assertTrue(custom < bmp * FACTOR + 50.milliseconds,
            "custom charset ($custom) must be within ${FACTOR}× the BMP default ($bmp) — a per-codepoint virtual call regressed")
    }

    @Test fun bmpAndGraphemePathsStayBelowTheFourfoldGrowthCatastropheFloor() {
        val bmpGrowth = medianWithinPairGrowth(LegalCharset.BMP)
        val graphemeGrowth = medianWithinPairGrowth(dinWithGraphemes)
        assertTrue(bmpGrowth < GROWTH_CEILING,
            "BMP median within-pair N/4N growth $bmpGrowth must stay below the ${GROWTH_CEILING}× catastrophe floor")
        assertTrue(graphemeGrowth < GROWTH_CEILING,
            "grapheme median within-pair N/4N growth $graphemeGrowth must stay below the ${GROWTH_CEILING}× catastrophe floor")
    }

    private fun medianWithinPairGrowth(charset: LegalCharset): Double {
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
        val ratios = List(REPEATS) {
            val nNanos = measureTime { runN() }.inWholeNanoseconds.coerceAtLeast(1)
            val fourNNanos = measureTime { runFourN() }.inWholeNanoseconds
            fourNNanos.toDouble() / nNanos
        }.sorted()
        return ratios[ratios.size / 2]
    }

    private fun assertClean(outcome: ValidationOutcome, label: String) {
        assertEquals(emptyList<Any>(), outcome.messages, "$label: no formal messages")
        assertEquals(emptyList<Any>(), outcome.unsupported, "$label: no unsupported constructs")
    }

    private companion object {
        const val N = 50_000
        const val GROWTH_N = 8_000
        const val WARM = 3
        const val REPEATS = 5
        const val FACTOR = 6                       // generous; a virtual-call blowup is 10×+ (+ 50 ms slack for tiny timings)
        const val GROWTH_CEILING = 9.0             // ratified catastrophe floor, not an NFR-certified target
    }
}
