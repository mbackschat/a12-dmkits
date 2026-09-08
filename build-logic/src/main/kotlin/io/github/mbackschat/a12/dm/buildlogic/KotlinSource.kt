package io.github.mbackschat.a12.dm.buildlogic

/**
 * Counting a token in Kotlin source, over CODE only.
 *
 * A raw text count fails in both directions, and a single-owner rule needs neither: a mention of the symbol
 * in prose fails a correct tree, and commenting the real call out leaves the count unchanged while the
 * invariant it guards is gone. Both are reachable on the estate's own sources.
 *
 * What this models: line comments, block comments with Kotlin's NESTING rule, escaped characters, character
 * literals, and both string forms — a raw string ends at the LAST of a run of three or more quotes, which is
 * what makes `"""x""""` terminate correctly.
 */
object KotlinSource {

    fun countInCode(source: String, token: String): Int = code(source).windowed(token.length)
        .count { it == token }

    /** The source with every comment and literal blanked to spaces, so offsets are preserved. */
    fun code(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        var blockDepth = 0
        while (i < source.length) {
            val c = source[i]
            when {
                blockDepth > 0 -> {
                    if (source.startsWith("/*", i)) { blockDepth++; out.append("  "); i += 2; continue }
                    if (source.startsWith("*/", i)) { blockDepth--; out.append("  "); i += 2; continue }
                    out.append(if (c == '\n') '\n' else ' '); i++
                }
                source.startsWith("/*", i) -> { blockDepth = 1; out.append("  "); i += 2 }
                source.startsWith("//", i) -> {
                    while (i < source.length && source[i] != '\n') { out.append(' '); i++ }
                }
                source.startsWith("\"\"\"", i) -> i = blankRawString(source, i, out)
                c == '"' -> i = blankQuoted(source, i, out, '"')
                c == '\'' -> i = blankQuoted(source, i, out, '\'')
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    /** A raw string ends at the LAST quote of a closing run, so `"""x""""` is `x"` and not an unterminated one. */
    private fun blankRawString(source: String, start: Int, out: StringBuilder): Int {
        out.append("   ")
        var i = start + 3
        while (i < source.length) {
            if (source.startsWith("\"\"\"", i)) {
                var run = i
                while (run < source.length && source[run] == '"') run++
                repeat(run - i) { out.append(' ') }
                return run
            }
            out.append(if (source[i] == '\n') '\n' else ' '); i++
        }
        return i
    }

    private fun blankQuoted(source: String, start: Int, out: StringBuilder, quote: Char): Int {
        out.append(' ')
        var i = start + 1
        while (i < source.length) {
            val c = source[i]
            if (c == '\\' && i + 1 < source.length) { out.append("  "); i += 2; continue }
            out.append(if (c == '\n') '\n' else ' ')
            i++
            if (c == quote || c == '\n') return i
        }
        return i
    }
}
