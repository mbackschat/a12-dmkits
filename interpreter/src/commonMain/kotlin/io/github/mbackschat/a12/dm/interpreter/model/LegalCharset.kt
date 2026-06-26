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
 * the matching [LegalCharset] (via [ofRanges]) so the interpreter enforces the same restriction.
 *
 * **Performance:** [allows] is on the formal-error HOT path (per code point of every valued field). This is a
 * `final class` (NOT a SAM `fun interface`), so the call is monomorphic and JIT-inlined; a custom charset
 * precomputes a **BMP membership bitset** ONCE, making the per-code-point check an O(1) array read — a custom
 * charset is as fast as the [BMP] default (which `FormalError` additionally fast-paths to a surrogate scan).
 */
class LegalCharset private constructor(
    /** True iff this is the unrestricted-BMP default — lets the hot path skip the bitset entirely. */
    internal val isBmp: Boolean,
    /** The allowed code-point ranges (for the rare supplementary-plane check + building [bmpAllowed]). */
    private val ranges: List<IntRange>,
) {
    /** O(1) BMP membership (`0x0000-0xFFFF`), precomputed once; null for [BMP] (handled without a 64 KB array). */
    private val bmpAllowed: BooleanArray? =
        if (isBmp) null
        else BooleanArray(0x10000).also { a -> for (r in ranges) for (cp in maxOf(r.first, 0)..minOf(r.last, 0xFFFF)) a[cp] = true }

    /** Whether the Unicode [codepoint] is legal. O(1) for a BMP code point (the hot case); the rare
     *  supplementary-plane code point falls back to a range scan. */
    fun allows(codepoint: Int): Boolean = when {
        isBmp -> codepoint in 0x0000..0xFFFF
        codepoint < 0x10000 -> codepoint >= 0 && bmpAllowed!![codepoint]
        else -> ranges.any { codepoint in it }
    }

    companion object {
        /** The kernel's default when no programming-language charset is configured: the BMP, `0x0000-0xFFFF`. */
        val BMP: LegalCharset = LegalCharset(isBmp = true, ranges = listOf(0x0000..0xFFFF))

        /** A charset from explicit inclusive code-point [ranges] (e.g. a DIN-91379 allow-list, or ASCII `0..0x7F`). */
        fun ofRanges(vararg ranges: IntRange): LegalCharset = LegalCharset(isBmp = false, ranges = ranges.toList())
    }
}
