package io.github.mbackschat.a12.dm.adapter.perf

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.LegalCharset
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
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
    private val def = ModelDef(listOf(FieldDef(s, Kind.STRING)), emptyList(), declaredReps = mapOf("/C" to N))
    private val din = LegalCharset.ofRanges(0x20..0x7E, 0x00A0..0x024F, 0x1E00..0x1EFF, 0x2000..0x206F)

    private fun doc(): Document {
        val d = Document(def.kinds, def.declaredReps)
        for (i in 1..N) d.field(s, "Auftragsbestätigung Nr. $i — Île-de-France", 1, i, 1)   // all within DIN
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

    private companion object {
        const val N = 50_000
        const val WARM = 3
        const val REPEATS = 5
        const val FACTOR = 6                       // generous; a virtual-call blowup is 10×+ (+ 50 ms slack for tiny timings)
    }
}
