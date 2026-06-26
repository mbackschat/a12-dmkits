package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.LegalCharset
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The [LegalCharset] customization SPI (IF43) — the interpreter exposes the kernel's project-level restricted
 * charset on its API, so a consumer can enforce e.g. DIN 91379, the way the kernel's codegen would. The DEFAULT
 * is [LegalCharset.BMP] (the kernel's DM-JSON runtime default). A value with a code point the configured charset
 * rejects fires `ZeichenNichtImZeichensatz`. Pure engine, JVM **and** JS (cross-platform code-point walk).
 */
class LegalCharsetTest {

    private val def = ModelDef(listOf(FieldDef(S, Kind.STRING)), emptyList())
    private fun charsetFires(value: String, charset: LegalCharset): Boolean =
        DmInterpreter(def, legalCharset = charset).validate(Document(def.kinds).field(S, value, 1, 1))
            .messages.any { it.code == "ZeichenNichtImZeichensatz" }

    @Test fun bmpDefaultAllowsBmpRejectsAstral() {
        assertEquals(false, charsetFires("café™€中", LegalCharset.BMP), "BMP allows é/™/€/CJK")
        assertEquals(true, charsetFires("x😀", LegalCharset.BMP), "BMP rejects a supplementary-plane emoji")
    }

    @Test fun customAsciiCharsetRejectsBmpChars() {
        val ascii = LegalCharset.ofRanges(0x00..0x7F)
        assertEquals(false, charsetFires("abc", ascii), "ASCII allows plain ASCII")
        assertEquals(true, charsetFires("café", ascii), "ASCII (custom) rejects é (U+00E9) that BMP-default allows")
    }

    @Test fun customRangesAreEnforced() {
        // A tiny allow-list (digits + uppercase A–Z) — a DIN-style narrowing.
        val restricted = LegalCharset.ofRanges('0'.code..'9'.code, 'A'.code..'Z'.code)
        assertEquals(false, charsetFires("ABC123", restricted), "value within the allow-list is clean")
        assertEquals(true, charsetFires("abc", restricted), "lowercase outside the allow-list → charset error")
    }

    private companion object {
        const val S = "/C/S"
    }
}
