package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.LegalCharset
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Multiplatform (JVM + JS) lock for IF151 — a legal multi-code-point **grapheme** in the charset is matched
 * ATOMICALLY, not flattened into independent code points. The charset allows `A-Z` plus the decomposed grapheme
 * "é" (base `e` U+0065 + combining acute U+0301); the lowercase base `e` is NOT independently allowed. So
 * the full decomposed sequence is admitted, but the bare base, the bare combining mark, the reversed order, and a
 * repeated component are all rejected — the pre-IF151 flatten wrongly admitted the base and the mark on their own.
 * Both the consumer SPI ([LegalCharset.of]) and the model-slot parse ([LegalCharset.ofSupportedCharacters]) are
 * exercised. Code points are spelled with `\u` escapes so the DECOMPOSED form is unambiguous.
 */
class LegalCharsetGraphemeTest {

    private val def = ModelDef(listOf(FieldDef(S, Kind.STRING)), emptyList())
    private fun charsetFires(value: String, charset: LegalCharset): Boolean =
        DmInterpreter(def, legalCharset = charset).validate(Document(def.kinds).field(S, value, 1, 1))
            .messages.any { it.code == "ZeichenNichtImZeichensatz" }

    // A-Z plus the DECOMPOSED grapheme é (e + combining acute). Two equivalent sources: the SPI and the DM-JSON slot.
    private val spi = LegalCharset.of(ranges = listOf('A'.code..'Z'.code), graphemes = listOf(E_ACUTE))
    private val modelSlot = LegalCharset.ofSupportedCharacters(listOf("A-Z", E_ACUTE))!!

    private fun assertBoth(value: String, rejected: Boolean, msg: String) {
        assertEquals(rejected, charsetFires(value, spi), "$msg (SPI)")
        assertEquals(rejected, charsetFires(value, modelSlot), "$msg (model slot)")
    }

    @Test fun fullDecomposedSequenceIsAccepted() = assertBoth(E_ACUTE, rejected = false, "the configured decomposed é is admitted whole")
    @Test fun bareBaseIsRejected() = assertBoth("e", rejected = true, "the bare base e is NOT admitted on its own")
    @Test fun bareCombiningMarkIsRejected() = assertBoth(ACUTE, rejected = true, "the bare combining acute is NOT admitted on its own")
    @Test fun reversedOrderIsRejected() = assertBoth(ACUTE + "e", rejected = true, "the reversed sequence is NOT the configured grapheme")
    @Test fun repeatedComponentIsRejected() = assertBoth(E_ACUTE + ACUTE, rejected = true, "é followed by a stray combining mark is rejected")

    @Test fun graphemeComposesWithAllowedRanges() {
        assertBoth("A$E_ACUTE", rejected = false, "an allowed range char then the grapheme is clean")
        assertBoth("${E_ACUTE}Z", rejected = false, "the grapheme then an allowed range char is clean")
        assertBoth("A${E_ACUTE}Z$E_ACUTE", rejected = false, "interleaved ranges and graphemes are clean")
    }

    @Test fun anAllowedGraphemeDoesNotLeakItsBaseElsewhere() {
        // The lowercase base 'e' appears ONLY inside the configured grapheme — a lone 'e' anywhere is still illegal.
        assertTrue(charsetFires("Ae", spi), "a lone base after a range char is still illegal")
        assertFalse(charsetFires("A$E_ACUTE", spi), "but the full grapheme after a range char is fine")
    }

    private companion object {
        const val S = "/C/S"
        const val ACUTE = "́"          // combining acute accent
        const val E_ACUTE = "é"       // DECOMPOSED é = base e (U+0065) + combining acute (U+0301)
    }
}
