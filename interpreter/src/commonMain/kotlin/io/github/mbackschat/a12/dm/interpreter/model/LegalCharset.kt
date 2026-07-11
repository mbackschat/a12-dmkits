package io.github.mbackschat.a12.dm.interpreter.model

/**
 * The legal character set a field value's characters are validated against — the effective legal charset the
 * kernel's `FormalChecker` enforces via `metaData.getLegalCodepoints` (IF43/IF120). A value containing a code
 * point this set rejects is a formal error (`ZeichenNichtImZeichensatz`), exactly as the kernel reports.
 *
 * **Two sources, one effective charset.** (1) The DM-JSON **model** can declare it via
 * `content.modelConfig.supportedCharacters` — the kernel bakes that list into the generated code's legal
 * codepoints (`ExternalDocumentModelService` → `CodeGeneratorParameters`), so it is a real *runtime* constraint;
 * [ModelLoader] reads it into [io.github.mbackschat.a12.dm.interpreter.model.ModelDef.legalCharset] via
 * [ofSupportedCharacters] (IF120). (2) A consumer running the kernel with a project charset the DM-JSON does NOT
 * carry — e.g. **DIN 91379** (the EU public-administration set) — supplies the matching [LegalCharset] (via
 * [ofRanges]) as the interpreter's SPI. The model-declared slot takes precedence; the SPI is the fallback when
 * the model declares none.
 *
 * **Default [BMP].** When neither source restricts (an empty/absent `supportedCharacters` and no SPI), the
 * kernel's runtime default — and the differential — is the Basic Multilingual Plane (`0x0000-0xFFFF`); any
 * supplementary-plane character (an emoji, U+1F600, …) is illegal, all BMP chars are legal.
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

        /**
         * Build the model's legal charset from a DM-JSON `modelConfig.supportedCharacters` list (IF120). Each entry
         * is either a single character or an inclusive `"X-Y"` range — the kernel's rule (`CharsetUtil.parse`) is
         * `length == 3 && charAt(1) == '-'` ⇒ the range `[X..Y]`, else each of the entry's characters is a singleton.
         * An **empty/absent** list means "no model restriction" (the kernel's default) → `null` (the caller uses the
         * SPI or [BMP]). (A supplementary-plane or combined-grapheme entry — the rare A12K-3445 case, absent from real
         * charsets — is approximated by its UTF-16 units; realistic sets are BMP singletons + ranges.)
         */
        fun ofSupportedCharacters(entries: List<String>): LegalCharset? {
            if (entries.isEmpty()) return null
            val ranges = mutableListOf<IntRange>()
            for (entry in entries) {
                if (entry.length == 3 && entry[1] == '-') ranges += entry[0].code..entry[2].code
                else for (ch in entry) ranges += ch.code..ch.code
            }
            return LegalCharset(isBmp = false, ranges = ranges)
        }
    }
}
