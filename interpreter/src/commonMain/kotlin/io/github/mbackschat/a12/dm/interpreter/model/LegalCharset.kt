package io.github.mbackschat.a12.dm.interpreter.model

import kotlin.jvm.JvmInline

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
    private val trie: TrieNode? = if (graphemes.isEmpty()) null else MutableTrieNode()
        .also { root -> graphemes.forEach(root::insert) }
        .freeze()

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
    fun matches(s: String): Boolean = scan(s).isAllowed

    /** Deterministic operation count for the multiplatform growth guard. It observes the exact production scan
     * without a timing threshold or a per-code-point callback. */
    internal fun matchWork(s: String): Int = scan(s).work

    private fun scan(s: String): MatchScan {
        if (isBmp) {
            var work = 0
            for (c in s) {
                work++
                if (c.isHighSurrogate() || c.isLowSurrogate()) return MatchScan.pack(false, work)
            }
            return MatchScan.pack(true, work)
        }
        var i = 0
        var work = 0
        while (i < s.length) {
            // Atomic grapheme first: walk the longest trie prefix from i. The validated definition boundary admits
            // legal shared/terminal prefixes but excludes the pinned kernel's ambiguous-overlap shape and bounds
            // every entry to three UTF-16 units. Root is never terminal, so every accepted transition advances.
            val root = trie
            if (root != null) {
                var node: TrieNode = root
                var j = i
                while (j < s.length) {
                    val decoded = decodeCodePoint(s, j)
                    val cp = decoded.codePoint
                    val next = decoded.nextIndex
                    work++
                    node = node.child(cp) ?: break
                    j = next
                }
                if (node.terminal) { i = j; continue }
            }
            // Singleton/range fallback on the ONE leading code point (kernel `rangeSearch`), advance by it.
            val decoded = decodeCodePoint(s, i)
            work++
            if (!allows(decoded.codePoint)) return MatchScan.pack(false, work)
            i = decoded.nextIndex
        }
        return MatchScan.pack(true, work)
    }

    /** Mutable only while the charset is prepared; [freeze] turns boxed construction keys into primitive sorted
     * arrays so the per-code-point lookup allocates nothing. */
    private class MutableTrieNode {
        private val children = HashMap<Int, MutableTrieNode>()
        var terminal = false
            private set

        fun insert(codepoints: List<Int>) {
            require(codepoints.isNotEmpty()) { "a legal-charset trie transition must consume at least one code point" }
            var node = this
            for (cp in codepoints) node = node.children.getOrPut(cp) { MutableTrieNode() }
            node.terminal = true
        }

        fun freeze(): TrieNode {
            val sorted = children.entries.sortedBy { it.key }
            return TrieNode(
                childCodepoints = IntArray(sorted.size) { sorted[it].key },
                children = Array(sorted.size) { sorted[it].value.freeze() },
                terminal = terminal,
            )
        }
    }

    /** Immutable hot-path node. [childCodepoints] avoids boxing an `Int` for every lookup. */
    private class TrieNode(
        private val childCodepoints: IntArray,
        private val children: Array<TrieNode>,
        val terminal: Boolean,
    ) {
        fun child(cp: Int): TrieNode? {
            var low = 0
            var high = childCodepoints.lastIndex
            while (low <= high) {
                val middle = (low + high) ushr 1
                val candidate = childCodepoints[middle]
                when {
                    candidate < cp -> low = middle + 1
                    candidate > cp -> high = middle - 1
                    else -> return children[middle]
                }
            }
            return null
        }
    }

    companion object {
        /** The kernel's default when no programming-language charset is configured: the BMP, `0x0000-0xFFFF`. */
        val BMP: LegalCharset = LegalCharset(isBmp = true, ranges = listOf(0x0000..0xFFFF), graphemes = emptyList())

        /** A charset from explicit inclusive code-point [ranges] (e.g. an ASCII `0..0x7F`) — no graphemes. */
        fun ofRanges(vararg ranges: IntRange): LegalCharset = validated(ranges.toList(), emptyList())

        /** A charset from explicit [ranges] AND atomic multi-code-point [graphemes] (e.g. a DIN-91379 allow-list
         *  with combining sequences) — the consumer SPI's general form. Each grapheme string is stored atomically. */
        fun of(ranges: List<IntRange> = emptyList(), graphemes: List<String> = emptyList()): LegalCharset =
            validated(ranges, graphemes.mapIndexed { index, value -> parseGrapheme(value, index) })

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
            val graphemes = mutableListOf<ParsedGrapheme>()
            for ((index, entry) in entries.withIndex()) {
                requireNoSurrogate(entry, index)
                if (entry.length == 3 && entry[1] == '-') {
                    require(entry[0] <= entry[2]) {
                        "invalid legal charset range at index $index: start '${entry[0]}' is after end '${entry[2]}'"
                    }
                    ranges += entry[0].code..entry[2].code
                } else when (entry.length) {
                    0 -> invalidDefinition(index, "entry is empty")
                    1 -> ranges += entry[0].code..entry[0].code
                    else -> graphemes += parseGrapheme(entry, index)
                }
            }
            return validated(ranges, graphemes)
        }

        private fun validated(ranges: List<IntRange>, graphemes: List<ParsedGrapheme>): LegalCharset {
            if (ranges.isEmpty() && graphemes.isEmpty()) return BMP
            ranges.forEachIndexed(::validateRange)
            validateOverlap(ranges, graphemes)
            return LegalCharset(
                isBmp = false,
                ranges = ranges.toList(),
                graphemes = graphemes.map { it.codepoints },
            )
        }

        private fun parseGrapheme(value: String, index: Int): ParsedGrapheme {
            if (value.isEmpty()) invalidDefinition(index, "entry is empty")
            requireNoSurrogate(value, index)
            if (value.length > MAX_GRAPHEME_LENGTH) {
                invalidDefinition(index, "entry is ${value.length} UTF-16 units; maximum is $MAX_GRAPHEME_LENGTH")
            }
            if (value.length == 1) {
                invalidDefinition(index, "a grapheme must contain more than one UTF-16 unit; use a singleton range")
            }
            val clusters = graphemeClustersOf(value)
            if (clusters.size >= value.length) {
                invalidDefinition(index, "multi-unit entry must contain a combined grapheme")
            }
            return ParsedGrapheme(index, value, codePointsOf(value), clusters)
        }

        private fun validateRange(index: Int, range: IntRange) {
            require(!range.isEmpty()) {
                "invalid legal charset range at index $index: start ${range.first} is after end ${range.last}"
            }
            require(range.first in BMP_CODEPOINTS && range.last in BMP_CODEPOINTS) {
                "invalid legal charset range at index $index: endpoints must be in the BMP"
            }
            require(!range.first.toChar().isSurrogate() && !range.last.toChar().isSurrogate()) {
                "invalid legal charset range at index $index: an endpoint is a surrogate"
            }
        }

        private fun validateOverlap(ranges: List<IntRange>, graphemes: List<ParsedGrapheme>) {
            val definitions = graphemes.map { it.value }.toSet()
            for (grapheme in graphemes) {
                if (grapheme.clusters.size <= 1) continue
                val prefixIsRepresentable = grapheme.clusters.dropLast(1).all { cluster ->
                    if (cluster.length > 1) cluster in definitions
                    else ranges.any { cluster[0].code in it }
                }
                if (!prefixIsRepresentable) continue
                val suffix = grapheme.clusters.last()
                val conflicting = definitions.firstOrNull {
                    it.length != suffix.length && it.startsWith(suffix) && it != grapheme.value
                }
                if (conflicting != null) {
                    invalidDefinition(
                        grapheme.index,
                        "entry '${grapheme.value}' has suffix '$suffix' overlapping '$conflicting'",
                    )
                }
            }
        }

        private fun requireNoSurrogate(value: String, index: Int) {
            if (value.any(Char::isSurrogate)) invalidDefinition(index, "entry contains a surrogate")
        }

        private fun invalidDefinition(index: Int, detail: String): Nothing =
            throw IllegalArgumentException("invalid legal charset definition at index $index: $detail")

        /** Decompose [s] into its Unicode code points (combining UTF-16 surrogate pairs) — cross-platform. */
        private fun codePointsOf(s: String): List<Int> {
            val out = ArrayList<Int>(s.length)
            var i = 0
            while (i < s.length) {
                val decoded = decodeCodePoint(s, i)
                out += decoded.codePoint
                i = decoded.nextIndex
            }
            return out
        }

        /** The code point at [i] and the index just past it, packed in one allocation-free value. */
        private fun decodeCodePoint(s: String, i: Int): DecodedCodePoint {
            val c = s[i]
            return if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
                DecodedCodePoint.pack(
                    0x10000 + ((c.code - 0xD800) shl 10) + (s[i + 1].code - 0xDC00),
                    i + 2,
                )
            } else {
                DecodedCodePoint.pack(c.code, i + 1)
            }
        }

        private const val MAX_GRAPHEME_LENGTH = 3
        private val BMP_CODEPOINTS = 0x0000..0xFFFF
    }

    private data class ParsedGrapheme(
        val index: Int,
        val value: String,
        val codepoints: List<Int>,
        val clusters: List<String>,
    )

    @JvmInline
    private value class MatchScan(private val packed: Long) {
        val isAllowed: Boolean get() = packed and 1L != 0L
        val work: Int get() = (packed ushr 1).toInt()

        companion object {
            fun pack(isAllowed: Boolean, work: Int): MatchScan =
                MatchScan((work.toLong() shl 1) or if (isAllowed) 1L else 0L)
        }
    }

    @JvmInline
    private value class DecodedCodePoint(private val packed: Long) {
        val codePoint: Int get() = packed.toInt()
        val nextIndex: Int get() = (packed ushr 32).toInt()

        companion object {
            fun pack(codePoint: Int, nextIndex: Int): DecodedCodePoint =
                DecodedCodePoint((nextIndex.toLong() shl 32) or codePoint.toLong())
        }
    }
}
