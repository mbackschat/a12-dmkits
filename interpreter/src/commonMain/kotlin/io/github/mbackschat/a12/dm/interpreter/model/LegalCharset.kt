package io.github.mbackschat.a12.dm.interpreter.model

/**
 * The legal character set a field value's characters are validated against — the interpreter's API surface for the
 * kernel's project-level restricted charset (`metaData.getLegalCodepoints`, IF43). A value containing a code point
 * this set rejects is a formal error (`ZeichenNichtImZeichensatz`), exactly as the kernel's `FormalChecker` reports.
 *
 * **Default [BMP].** A DM-JSON model carries NO charset (it's a codegen/programming-language config, not a model
 * property), so the kernel's runtime default — and the differential — is the Basic Multilingual Plane
 * (`0x0000-0xFFFF`); any supplementary-plane character (an emoji, U+1F600, …) is illegal. A consumer that runs
 * the kernel with a NARROWER project charset — e.g. **DIN 91379** (the EU public-administration set) — supplies
 * the matching [LegalCharset] here so the interpreter enforces the same restriction (a `DmInterpreter` constructor
 * parameter). The set is consulted per Unicode code point.
 */
fun interface LegalCharset {
    /** Whether the Unicode [codepoint] is legal (allowed in a field value). */
    fun allows(codepoint: Int): Boolean

    companion object {
        /** The kernel's default when no programming-language charset is configured: the BMP, `0x0000-0xFFFF`. */
        val BMP: LegalCharset = LegalCharset { it in 0x0000..0xFFFF }

        /** A charset from explicit inclusive code-point [ranges] (e.g. a DIN-91379 allow-list, or ASCII `0..0x7F`). */
        fun ofRanges(vararg ranges: IntRange): LegalCharset = LegalCharset { cp -> ranges.any { cp in it } }
    }
}
