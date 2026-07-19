package io.github.mbackschat.a12.dm.interpreter.model

/**
 * The legal character set a field value's characters are validated against — the effective legal charset the
 * kernel's `FormalChecker` enforces via `metaData.getLegalCodepoints` (IF43/IF120). A value containing a code
 * point (or a code-point sequence) this set rejects is a formal error (`ZeichenNichtImZeichensatz`), exactly as
 * the kernel reports.
 *
 * **Two sources, one effective charset.** (1) The DM-JSON **model** can declare it via
 * `content.modelConfig.supportedCharacters` — the kernel bakes that list into the generated code's legal
 * codepoints (`ExternalDocumentModelService` → `CodeGeneratorParameters`), so it is a real *runtime* constraint;
 * [ModelLoader] reads it into [io.github.mbackschat.a12.dm.interpreter.model.ModelDef.legalCharset] via
 * [ofSupportedCharacters] (IF120). (2) A consumer running the kernel with a project charset the DM-JSON does NOT
 * carry — e.g. **DIN 91379** (the EU public-administration set) — supplies the matching [LegalCharset] (via
 * [of]/[ofRanges]) as the interpreter's SPI. The model-declared slot takes precedence; the SPI is the fallback.
 *
 * **Singletons/ranges vs. atomic graphemes (IF151).** A `supportedCharacters` entry is one of three kinds
 * (kernel `CharsetUtil.parse`): a `"X-Y"` **range** (`length == 3 && charAt(1) == '-'`), a single-code-point
 * **singleton**, or a legal multi-code-point **grapheme** (a combining sequence such as decomposed `e`+combining
 * acute). Singletons/ranges are membership-tested per code point; a grapheme is stored ATOMICALLY in a trie and
 * matched as a **complete configured sequence** — the kernel's `LegalCharTester.pruefeZeichensatz` does a
 * longest-trie-prefix walk (consume the whole sequence iff the stop node is terminal), then falls back to a
 * singleton/range check on the one leading code point. So a configured decomposed `é` admits the FULL sequence
 * but NOT the bare base, the bare combining mark, the reversed order, or a repeated component (unless those are
 * independently allowed). A grapheme's individual code points never enter the range set.
 *
 * **Default [BMP].** When neither source restricts, the kernel's runtime default (`noCharsetDefined`) accepts any
 * value carrying no surrogate code unit — i.e. the Basic Multilingual Plane minus the surrogate block; any
 * supplementary-plane character (an emoji, U+1F600, …) is illegal, all BMP chars are legal.
 *
 * **Performance:** [matches] is on the formal-error HOT path (once per valued field). [BMP] is an inline
 * surrogate scan with no per-code-point callback; a range-only custom charset is an O(1) BMP-bitset read per
 * code point; only a charset that actually declares graphemes pays the trie walk (`CharsetPerfTest`).
 */
class LegalCharset private constructor(
    /** True iff this is the unrestricted-BMP default — lets the hot path skip the bitset/trie entirely. */
    internal val isBmp: Boolean,
    /** The allowed single-code-point ranges (singletons folded in as `[cp..cp]`). */
    private val ranges: List<IntRange>,
    /** The allowed atomic multi-code-point graphemes, each a code-point sequence (empty when none declared). */
    private val graphemes: List<List<Int>>,
) {
    /** O(1) BMP membership (`0x0000-0xFFFF`), precomputed once; null for [BMP] (handled without a 64 KB array). */
    private val bmpAllowed: BooleanArray? =
        if (isBmp) null
        else BooleanArray(0x10000).also { a -> for (r in ranges) for (cp in maxOf(r.first, 0)..minOf(r.last, 0xFFFF)) a[cp] = true }

    /** The atomic-grapheme trie (null when no grapheme is declared — the fast per-code-point path is kept). */
    private val trie: TrieNode? = if (graphemes.isEmpty()) null else TrieNode().also { root -> graphemes.forEach { root.insert(it) } }

    /** Whether the single Unicode [codepoint] is legal by the singleton/range set. O(1) for a BMP code point
     *  (the hot case); a rare supplementary-plane code point falls back to a range scan. */
    fun allows(codepoint: Int): Boolean = when {
        isBmp -> codepoint in 0x0000..0xFFFF
        codepoint < 0x10000 -> codepoint >= 0 && bmpAllowed!![codepoint]
        else -> ranges.any { codepoint in it }
    }

    /**
     * Whether every code point / configured grapheme in [s] is legal — the kernel's per-value charset check.
     * [BMP] is the inline surrogate fast path; a range-only charset walks code points against [allows]; a
     * grapheme-bearing charset does the atomic longest-trie-prefix walk with the singleton/range fallback.
     */
    fun matches(s: String): Boolean {
        if (isBmp) return s.none { it.isHighSurrogate() || it.isLowSurrogate() }
        var i = 0
        while (i < s.length) {
            // Atomic grapheme first: walk the LONGEST trie prefix from i; consume the whole sequence iff the stop
            // node is terminal (the kernel's no-backtrack walk — safe because configured graphemes never overlap
            // and are short). Root is never terminal (singletons never enter the trie), so a first code point with
            // no trie child falls straight through to the range check.
            val root = trie
            if (root != null) {
                var node: TrieNode = root
                var j = i
                while (j < s.length) {
                    val (cp, next) = codePointAt(s, j)
                    node = node.child(cp) ?: break
                    j = next
                }
                if (node.terminal) { i = j; continue }
            }
            // Singleton/range fallback on the ONE leading code point (kernel `rangeSearch`), advance by it.
            val (cp, next) = codePointAt(s, i)
            if (!allows(cp)) return false
            i = next
        }
        return true
    }

    /** A minimal code-point trie for atomic-grapheme membership — one child per code point, [terminal] on the
     *  last code point of each inserted grapheme (an original, kernel-mechanism reimplementation). */
    private class TrieNode {
        private val children = HashMap<Int, TrieNode>()
        var terminal = false
            private set

        fun child(cp: Int): TrieNode? = children[cp]

        fun insert(codepoints: List<Int>) {
            var node = this
            for (cp in codepoints) node = node.children.getOrPut(cp) { TrieNode() }
            node.terminal = true
        }
    }

    companion object {
        /** The kernel's default when no programming-language charset is configured: the BMP, `0x0000-0xFFFF`. */
        val BMP: LegalCharset = LegalCharset(isBmp = true, ranges = listOf(0x0000..0xFFFF), graphemes = emptyList())

        /** A charset from explicit inclusive code-point [ranges] (e.g. an ASCII `0..0x7F`) — no graphemes. */
        fun ofRanges(vararg ranges: IntRange): LegalCharset = LegalCharset(isBmp = false, ranges = ranges.toList(), graphemes = emptyList())

        /** A charset from explicit [ranges] AND atomic multi-code-point [graphemes] (e.g. a DIN-91379 allow-list
         *  with combining sequences) — the consumer SPI's general form. Each grapheme string is stored atomically. */
        fun of(ranges: List<IntRange> = emptyList(), graphemes: List<String> = emptyList()): LegalCharset =
            LegalCharset(isBmp = false, ranges = ranges, graphemes = graphemes.map { codePointsOf(it) })

        /**
         * Build the model's legal charset from a DM-JSON `modelConfig.supportedCharacters` list (IF120/IF151). Each
         * entry is a `"X-Y"` **range** (`length == 3 && charAt(1) == '-'` ⇒ `[X..Y]`), a single-code-point
         * **singleton**, or a legal multi-code-point **grapheme** (a combining sequence — stored ATOMICALLY in the
         * trie, NOT flattened into independent code points). An **empty/absent** list → `null` (the caller uses the
         * SPI or [BMP]).
         */
        fun ofSupportedCharacters(entries: List<String>): LegalCharset? {
            if (entries.isEmpty()) return null
            val ranges = mutableListOf<IntRange>()
            val graphemes = mutableListOf<List<Int>>()
            for (entry in entries) {
                if (entry.length == 3 && entry[1] == '-') {
                    ranges += entry[0].code..entry[2].code
                } else {
                    val cps = codePointsOf(entry)
                    if (cps.size == 1) ranges += cps[0]..cps[0]   // singleton
                    else graphemes += cps                          // atomic multi-code-point grapheme
                }
            }
            return LegalCharset(isBmp = false, ranges = ranges, graphemes = graphemes)
        }

        /** Decompose [s] into its Unicode code points (combining UTF-16 surrogate pairs) — cross-platform
         *  (`String.codePoints()` is JVM-only). */
        private fun codePointsOf(s: String): List<Int> {
            val out = ArrayList<Int>(s.length)
            var i = 0
            while (i < s.length) {
                val (cp, next) = codePointAt(s, i)
                out += cp
                i = next
            }
            return out
        }

        /** The code point at [i] and the index just past it — combines a high+low surrogate pair. */
        private fun codePointAt(s: String, i: Int): Pair<Int, Int> {
            val c = s[i]
            return if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate())
                (0x10000 + ((c.code - 0xD800) shl 10) + (s[i + 1].code - 0xDC00)) to (i + 2)
            else c.code to (i + 1)
        }
    }
}
