package io.github.mbackschat.a12.dm.regex

/**
 * Closed Java-Pattern-to-ES2018 translation used by the JS interpreter and portable checker.
 *
 * The model validator admits more Java syntax than a JavaScript RegExp understands. Keeping parsing, translation,
 * and unsupported classification in this one model prevents a legal pattern from leaking through to a host
 * SyntaxError. The JVM actual still compiles the original source with java.util.regex.Pattern.
 */
internal object JavaPatternToJs {
    private const val H = "\\t\\x20\\xA0\\u1680\\u180E\\u2000-\\u200A\\u202F\\u205F\\u3000"
    private const val V = "\\n\\x0B\\f\\r\\x85\\u2028\\u2029"
    private const val S = "\\t\\n\\x0B\\f\\r\\x20"
    // DG102-E: CRLF is one final terminator; its interior is not an end-anchor position.
    private const val JAVA_FINAL_ANCHOR = "(?:(?![\\s\\S])|(?=\\r\\n(?![\\s\\S]))|(?=[\\r\\u0085\\u2028\\u2029](?![\\s\\S]))|(?<!\\r)(?=\\n(?![\\s\\S])))"
    private const val DOT_TERMINATORS = "\\n\\r\\u0085\\u2028\\u2029"

    data class BackReferenceGuard(val capture: Int, val marker: Int)
    data class Translation(
        val source: String,
        val flags: String = "u",
        val backReferenceGuards: List<BackReferenceGuard> = emptyList(),
        val hasUnverifiedMapping: Boolean = false,
        val certifiedValueDomain: ValueDomain = ValueDomain.ANY,
        val globalUnicodeCase: Boolean = false,
    )

    enum class ValueDomain { ANY, LATIN1, ASCII, PRINTABLE_ASCII }

    /** Translate a kernel-legal Java pattern. Unsupported legal syntax fails here, before host compilation. */
    fun compile(java: String): Translation = Parser(java, forConsistency = false).translate()

    /** DG102 certifies the JVM consistency verdict; it does not change the interpreter's runtime dialect. */
    fun compileForConsistency(java: String): Translation = Parser(java, forConsistency = true).translate()

    /** Kept as the small source-level seam used by translation tests. */
    fun translate(java: String): String = compile(java).source

    /**
     * True only when the shared parser cannot represent the source. This deliberately invokes the same parser as
     * translation; there is no second heuristic inventory that can drift from the compiler.
     */
    fun containsResidual(java: String): Boolean =
        try {
            compile(java)
            false
        } catch (_: UnsupportedJavaPattern) {
            true
        }

    class UnsupportedJavaPattern(message: String) : IllegalArgumentException(message)

    private data class Flags(
        val unixLines: Boolean = false,
        val caseInsensitive: Boolean = false,
        val comments: Boolean = false,
        val multiline: Boolean = false,
        val dotAll: Boolean = false,
        val unicodeCase: Boolean = false,
        val unicodeClasses: Boolean = false,
    ) {
        fun with(letter: Char, enabled: Boolean): Flags = when (letter) {
            'd' -> copy(unixLines = enabled)
            'i' -> copy(caseInsensitive = enabled)
            'x' -> copy(comments = enabled)
            'm' -> copy(multiline = enabled)
            's' -> copy(dotAll = enabled)
            'u' -> copy(unicodeCase = enabled)
            'U' -> copy(unicodeClasses = enabled, unicodeCase = if (enabled) true else unicodeCase)
            else -> throw UnsupportedJavaPattern("unsupported inline flag: $letter")
        }
    }

    private class Parser(private val source: String, private val forConsistency: Boolean) {
        private var index = 0
        private var javaCaptureCount = 0
        private var jsCaptureCount = 0
        private val captureMap = mutableMapOf<Int, Int>()
        private val guards = mutableListOf<BackReferenceGuard>()
        private var hasUnverifiedMapping = false
        private var certifiedValueDomain = ValueDomain.ANY
        private var globalUnicodeCase = false

        private fun requireDomain(domain: ValueDomain) {
            if (forConsistency && domain.ordinal > certifiedValueDomain.ordinal) certifiedValueDomain = domain
        }

        fun translate(): Translation {
            val parsed = sequence(Flags(), nested = false)
            if (index != source.length) throw UnsupportedJavaPattern("unexpected pattern suffix at $index")
            if (globalUnicodeCase && source.any { it.code > 0xff }) hasUnverifiedMapping = true
            return Translation(
                parsed.text,
                backReferenceGuards = guards.toList(),
                hasUnverifiedMapping = hasUnverifiedMapping,
                certifiedValueDomain = certifiedValueDomain,
                globalUnicodeCase = globalUnicodeCase,
            )
        }

        private data class Sequence(val text: String, val flags: Flags)

        private fun sequence(initial: Flags, nested: Boolean): Sequence {
            var flags = initial
            val out = StringBuilder()
            while (index < source.length) {
                val c = source[index]
                if (c == ')' && nested) {
                    index++
                    return Sequence(out.toString(), flags)
                }
                if (flags.comments && c.isWhitespace()) {
                    index++
                    continue
                }
                if (flags.comments && c == '#') {
                    while (index < source.length && source[index] != '\n' && source[index] != '\r') index++
                    continue
                }
                when (c) {
                    '\\' -> out.append(escape(flags))
                    '[' -> out.append(characterClass(flags))
                    '(' -> {
                        val group = group(flags)
                        out.append(group.first)
                        flags = group.second
                    }
                    '.' -> {
                        index++
                        out.append(
                            when {
                                flags.dotAll -> "[\\s\\S]"
                                flags.unixLines -> "[^\\n]"
                                else -> "[^$DOT_TERMINATORS]"
                            },
                        )
                    }
                    '^' -> {
                        if (!forConsistency || flags.multiline) hasUnverifiedMapping = true
                        index++
                        out.append(if (flags.multiline) {
                            if (flags.unixLines) "(?:^|(?<=\\n))" else "(?:^|(?<=[$DOT_TERMINATORS]))"
                        } else "^")
                    }
                    '$' -> {
                        if (!forConsistency || flags.multiline) hasUnverifiedMapping = true
                        index++
                        out.append(if (forConsistency && !flags.multiline) {
                            JAVA_FINAL_ANCHOR
                        } else if (flags.multiline) {
                            if (flags.unixLines) "(?:$|(?=\\n))" else "(?:$|(?=[$DOT_TERMINATORS]))"
                        } else "$")
                    }
                    else -> {
                        index++
                        appendLiteral(out, c, flags)
                    }
                }
            }
            if (nested) throw UnsupportedJavaPattern("unclosed group")
            return Sequence(out.toString(), flags)
        }

        /** Returns emitted source and the enclosing sequence's possibly updated global flags. */
        private fun group(enclosing: Flags): Pair<String, Flags> {
            val groupStart = index
            index++ // (
            if (index >= source.length || source[index] != '?') {
                javaCaptureCount++
                jsCaptureCount++
                captureMap[javaCaptureCount] = jsCaptureCount
                val body = sequence(enclosing, nested = true)
                return "(${body.text})" to enclosing
            }
            index++ // ?
            when (source.getOrNull(index)) {
                ':' -> {
                    index++
                    val body = sequence(enclosing, nested = true)
                    return "(?:${body.text})" to enclosing
                }
                '=' -> {
                    index++
                    val body = sequence(enclosing, nested = true)
                    return "(?=${body.text})" to enclosing
                }
                '!' -> {
                    index++
                    val body = sequence(enclosing, nested = true)
                    return "(?!${body.text})" to enclosing
                }
                '<', '>' -> throw UnsupportedJavaPattern("kernel-forbidden group reached translation")
            }

            var modified = enclosing
            var enabled = true
            var sawFlag = false
            var sawUnicodeCase = false
            var sawCaseFlag = false
            while (index < source.length) {
                val c = source[index]
                if (c == '-') {
                    enabled = false
                    index++
                    continue
                }
                if (c !in FLAG_LETTERS) break
                sawFlag = true
                if (c == 'u') sawUnicodeCase = true
                else if (c == 'i') sawCaseFlag = true
                else hasUnverifiedMapping = true
                modified = modified.with(c, enabled)
                index++
            }
            if (!sawFlag) throw UnsupportedJavaPattern("unsupported (? group at ${index - 2}")
            return when (source.getOrNull(index)) {
                ')' -> {
                    index++
                    if (forConsistency && groupStart == 0 && modified.caseInsensitive && modified.unicodeCase && sawUnicodeCase) {
                        globalUnicodeCase = true
                        requireDomain(ValueDomain.LATIN1)
                    } else if (sawUnicodeCase || (globalUnicodeCase && sawCaseFlag)) {
                        hasUnverifiedMapping = true
                    }
                    "" to modified
                }
                ':' -> {
                    index++
                    val wholeUnicodeScope = forConsistency && groupStart == 0 &&
                        modified.caseInsensitive && modified.unicodeCase && sawUnicodeCase
                    if (wholeUnicodeScope) {
                        globalUnicodeCase = true
                        requireDomain(ValueDomain.LATIN1)
                    } else if (sawUnicodeCase || (globalUnicodeCase && sawCaseFlag)) hasUnverifiedMapping = true
                    val body = sequence(modified, nested = true)
                    if (wholeUnicodeScope && index != source.length) hasUnverifiedMapping = true
                    "(?:${body.text})" to enclosing
                }
                else -> throw UnsupportedJavaPattern("malformed inline flag group")
            }
        }

        private fun escape(flags: Flags): String {
            index++ // slash
            if (index >= source.length) throw UnsupportedJavaPattern("trailing escape")
            val e = source[index++]
            if (e in '1'..'9') {
                var number = e.digitToInt()
                while (index < source.length && source[index].isDigit()) {
                    val candidate = number * 10 + source[index].digitToInt()
                    if (candidate > javaCaptureCount) break
                    number = candidate
                    index++
                }
                val capture = captureMap[number]
                    ?: throw UnsupportedJavaPattern("forward or missing backreference: $number")
                jsCaptureCount++
                val marker = jsCaptureCount
                guards += BackReferenceGuard(capture, marker)
                return "()\\$capture"
            }
            return when (e) {
                'n', 'r', 't', 'f' -> {
                    if (!forConsistency) hasUnverifiedMapping = true
                    "\\$e"
                }
                'R' -> "(?:\\r\\n|[$V])"
                'h' -> "[$H]"
                'H' -> "[^$H]"
                'v' -> "[$V]"
                'V' -> "[^$V]"
                's' -> if (flags.unicodeClasses) "\\p{White_Space}" else "[$S]"
                'S' -> if (flags.unicodeClasses) "\\P{White_Space}" else "[^$S]"
                'd', 'D', 'w', 'W' -> if (flags.unicodeClasses) {
                    when (e) {
                        'd' -> "\\p{Nd}"
                        'D' -> "\\P{Nd}"
                        'w' -> "[\\p{Alphabetic}\\p{M}\\p{Nd}\\p{Pc}\\u200C\\u200D]"
                        else -> "[^\\p{Alphabetic}\\p{M}\\p{Nd}\\p{Pc}\\u200C\\u200D]"
                    }
                } else "\\$e"
                'x' -> if (source.getOrNull(index) == '{') {
                    index++
                    val hex = certifiedCodePoint(takeUntil('}'))
                    scalarEscape(hex, "\\u{$hex}", flags)
                } else {
                    val hex = certifiedCodePoint(take(2))
                    scalarEscape(hex, "\\x$hex", flags)
                }
                'u' -> {
                    val hex = certifiedCodePoint(take(4))
                    scalarEscape(hex, "\\u$hex", flags)
                }
                'N' -> {
                    if (source.getOrNull(index) != '{') throw UnsupportedJavaPattern("malformed named character")
                    index++
                    if (flags.caseInsensitive && !globalUnicodeCase) hasUnverifiedMapping = true
                    namedCharacter(takeUntil('}'))
                }
                'X' -> {
                    requireDomain(ValueDomain.PRINTABLE_ASCII)
                    "(?:\\P{M}\\p{M}*)"
                }
                'b' -> if (source.startsWith("{g}", index)) {
                    index += 3
                    hasUnverifiedMapping = true
                    "(?:^|$|(?=\\P{M}))"
                } else {
                    requireDomain(ValueDomain.ASCII)
                    "\\b"
                }
                'P', 'p' -> {
                    if (source.getOrNull(index) != '{') throw UnsupportedJavaPattern("malformed property escape")
                    index++
                    val property = takeUntil('}')
                    if (property == "L" && !flags.caseInsensitive) requireDomain(ValueDomain.ASCII)
                    else hasUnverifiedMapping = true
                    "\\$e{$property}"
                }
                else -> {
                    hasUnverifiedMapping = true
                    "\\$e"
                }
            }
        }

        private fun characterClass(flags: Flags): String {
            index++ // [
            val negated = source.getOrNull(index) == '^'
            if (negated) index++
            val raw = StringBuilder()
            if (source.getOrNull(index) == ']') {
                raw.append("\\]")
                index++
            }
            var closed = false
            while (index < source.length) {
                val c = source[index++]
                if (c == '\\') {
                    if (index >= source.length) throw UnsupportedJavaPattern("trailing class escape")
                    raw.append('\\').append(source[index++])
                } else if (c == ']') {
                    closed = true
                    break
                } else {
                    raw.append(c)
                }
            }
            if (!closed) throw UnsupportedJavaPattern("unclosed character class")
            val body = raw.toString()
            // IF164/DG102: both consumers use Java class algebra; certification stays caller-specific.
            val terms = classTerms(body)
            if (terms.isEmpty()) throw UnsupportedJavaPattern("intersection without a represented operand")
            if (terms.size == 1 && !hasClassComplement(terms[0])) {
                return "[" + (if (negated) "^" else "") + classBody(terms[0], flags) + "]"
            }
            val common = BooleanArray(129) { it < 128 }
            var finite = true
            val predicates = terms.map { term ->
                if (hasClassComplement(term)) {
                    finite = false
                    classUnion(term, flags)
                } else {
                    val members = BooleanArray(129)
                    val translated = classBody(term, flags, members)
                    if (members[128]) finite = false
                    for (code in 0..127) common[code] = common[code] && members[code]
                    "[$translated]"
                }
            }
            // IF164 cost gate: serialize finite sets compactly; lookaheads add avoidable per-row work.
            if (finite) return finiteClass(common, negated)
            val predicate = predicates.dropLast(1).joinToString("") { "(?=$it)" } + predicates.last()
            return if (negated) "(?:(?!$predicate)[\\s\\S])" else "(?:$predicate)"
        }

        private fun hasClassComplement(raw: String): Boolean {
            var i = 0
            while (i < raw.length) {
                if (raw[i++] == '\\' && i < raw.length) {
                    if (raw[i++] in "SDWHV") return true
                }
            }
            return false
        }

        /** DG102-F3: an operator starts only between tokens, never inside an escaped literal. */
        private fun classTerms(raw: String): List<String> {
            val terms = mutableListOf<String>()
            var start = 0
            var i = 0
            while (i < raw.length) {
                when {
                    raw[i] == '\\' -> i += 2
                    raw.startsWith("&&", i) -> {
                        if (i > start) terms += raw.substring(start, i)
                        i += 2
                        start = i
                    }
                    else -> i++
                }
            }
            if (start < raw.length) terms += raw.substring(start)
            return terms
        }

        /** DG102-E: union complete one-character predicates before applying the enclosing class negation. */
        private fun classUnion(raw: String, flags: Flags): String {
            val members = mutableListOf<String>()
            var start = 0
            var i = 0
            while (i < raw.length) {
                if (raw[i] != '\\' || i + 1 >= raw.length) {
                    i++
                    continue
                }
                val positive = if (raw[i + 1] in "SDWHV") predefinedClass(raw[i + 1].lowercaseChar(), flags) else null
                if (positive != null) {
                    if (i > start) members += "[${classBody(raw.substring(start, i), flags, followedBySet = true)}]"
                    members += "[^$positive]"
                    start = i + 2
                }
                i += 2
            }
            if (start < raw.length) members += "[${classBody(raw.substring(start), flags)}]"
            if (members.isEmpty()) throw UnsupportedJavaPattern("empty character class")
            return members.joinToString("|", "(?:", ")")
        }

        private fun classBody(raw: String, flags: Flags, members: BooleanArray? = null, followedBySet: Boolean = false): String {
            val out = StringBuilder()
            var i = 0
            while (i < raw.length) {
                val width = classLiteralWidth(raw, i)
                if (width > 0) {
                    if (width > 2) hasUnverifiedMapping = true
                    val next = i + width
                    if (followedBySet && next + 1 == raw.length && raw[next] == '-') {
                        throw UnsupportedJavaPattern("range with a set-valued endpoint")
                    }
                    val endWidth = if (raw.getOrNull(next) == '-') classLiteralWidth(raw, next + 1) else 0
                    if (endWidth > 0) {
                        if (width > 1 || endWidth > 1) hasUnverifiedMapping = true
                        appendClassRange(out, classLiteralValue(raw, i, width), classLiteralValue(raw, next + 1, endWidth), flags)
                        includeAscii(members, classLiteralValue(raw, i, width), classLiteralValue(raw, next + 1, endWidth), flags)
                        i = next + 1 + endWidth
                    } else {
                        appendLiteral(out, classLiteralValue(raw, i, width), flags, inClass = true)
                        includeAscii(members, classLiteralValue(raw, i, width), classLiteralValue(raw, i, width), flags)
                        i = next
                    }
                    continue
                }
                val e = raw[i + 1]
                if (members != null) members[128] = true
                if (forConsistency && e == 'x' && raw.getOrNull(i + 2) == '{') {
                    val end = raw.indexOf('}', i + 3)
                    if (end < 0) throw UnsupportedJavaPattern("unclosed class code point")
                    val hex = certifiedCodePoint(raw.substring(i + 3, end))
                    val codePoint = hex.toIntOrNull(16)
                    if (hex.isEmpty() || codePoint == null || codePoint > 0x10ffff || codePoint in 0xd800..0xdfff) {
                        throw UnsupportedJavaPattern("unrepresented class code point")
                    }
                    if (flags.caseInsensitive && !globalUnicodeCase) hasUnverifiedMapping = true
                    out.append("\\u{").append(hex).append('}')
                    i = end + 1
                    continue
                }
                if (forConsistency && e == 'N' && raw.getOrNull(i + 2) == '{') {
                    val end = raw.indexOf('}', i + 3)
                    if (end < 0) throw UnsupportedJavaPattern("unclosed class character name")
                    if (flags.caseInsensitive && !globalUnicodeCase) hasUnverifiedMapping = true
                    out.append(namedCharacter(raw.substring(i + 3, end)))
                    i = end + 1
                    continue
                }
                out.append(
                    when (e) {
                        'h', 'v', 's', 'd', 'w' -> predefinedClass(e, flags)
                        else -> {
                            hasUnverifiedMapping = true
                            "\\$e"
                        }
                    },
                )
                i += 2
            }
            return out.toString()
        }

        private fun predefinedClass(letter: Char, flags: Flags): String = when (letter) {
            'h' -> H
            'v' -> V
            's' -> if (flags.unicodeClasses) "\\p{White_Space}" else S
            'd' -> if (flags.unicodeClasses) "\\p{Nd}" else "0-9"
            'w' -> if (flags.unicodeClasses) "\\p{Alphabetic}\\p{M}\\p{Nd}\\p{Pc}\\u200C\\u200D" else "a-zA-Z_0-9"
            else -> error("not a predefined character class: $letter")
        }

        private fun includeAscii(members: BooleanArray?, first: Char, last: Char, flags: Flags) {
            if (members == null) return
            if (first.code > 127 || last.code > 127) members[128] = true
            for (code in first.code..minOf(last.code, 127)) {
                members[code] = true
                if (flags.caseInsensitive) when (code) {
                    in 65..90 -> members[code + 32] = true
                    in 97..122 -> members[code - 32] = true
                }
            }
        }

        private fun finiteClass(members: BooleanArray, negated: Boolean): String {
            val out = StringBuilder()
            var code = 0
            while (code < 128) {
                if (!members[code]) { code++; continue }
                val first = code
                while (code < 127 && members[code + 1]) code++
                out.append(classLiteral(first.toChar()))
                if (code > first) out.append('-').append(classLiteral(code.toChar()))
                code++
            }
            if (out.isEmpty()) return if (negated) "[\\s\\S]" else "(?:(?!))"
            return "[" + (if (negated) "^" else "") + out + "]"
        }

        private fun classLiteralWidth(raw: String, at: Int): Int {
            if (at >= raw.length) return 0
            if (raw[at] != '\\') return 1
            val escaped = raw.getOrNull(at + 1) ?: return 0
            if (escaped in "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~" || escaped in "tnrfbae") return 2
            val digits = when (escaped) { 'x' -> 2; 'u' -> 4; else -> return 0 }
            if (at + 2 + digits > raw.length) return 0
            for (i in at + 2 until at + 2 + digits) if (raw[i].digitToIntOrNull(16) == null) return 0
            return digits + 2
        }

        private fun classLiteralValue(raw: String, at: Int, width: Int): Char {
            if (width == 1) return raw[at]
            if (width > 2) return raw.substring(at + 2, at + width).toInt(16).toChar()
            return when (val escaped = raw[at + 1]) {
                't' -> '\t'; 'n' -> '\n'; 'r' -> '\r'; 'f' -> '\u000c'
                'b' -> '\u0008'; 'a' -> '\u0007'; 'e' -> '\u001b'
                else -> escaped
            }
        }

        private fun appendClassRange(out: StringBuilder, first: Char, last: Char, flags: Flags) {
            out.append(classLiteral(first)).append('-').append(classLiteral(last))
            if (!flags.caseInsensitive) return
            // DG102-F: close the interval's letters, including mixed punctuation/letter ranges.
            appendFoldedRange(out, first, last, 'A', 'Z', 32)
            appendFoldedRange(out, first, last, 'a', 'z', -32)
        }

        private fun appendFoldedRange(out: StringBuilder, first: Char, last: Char, lower: Char, upper: Char, shift: Int) {
            val start = maxOf(first, lower)
            val end = minOf(last, upper)
            if (start <= end) out.append((start.code + shift).toChar()).append('-').append((end.code + shift).toChar())
        }

        private fun appendLiteral(out: StringBuilder, c: Char, flags: Flags, inClass: Boolean = false) {
            if (flags.caseInsensitive && c in 'a'..'z') {
                if (inClass) out.append(c).append(c.uppercaseChar())
                else out.append('[').append(c).append(c.uppercaseChar()).append(']')
            } else if (flags.caseInsensitive && c in 'A'..'Z') {
                if (inClass) out.append(c.lowercaseChar()).append(c)
                else out.append('[').append(c.lowercaseChar()).append(c).append(']')
            } else {
                if (inClass) out.append(classLiteral(c)) else out.append(c)
            }
        }

        /** DG102-F2: ASCII class punctuation denotes characters, never emitted regex structure. */
        private fun classLiteral(c: Char): String = if (c.code <= 0x7f && c !in 'a'..'z' && c !in 'A'..'Z' && c !in '0'..'9' && c != '_') "\\u{${c.code.toString(16)}}" else c.toString()

        private fun take(length: Int): String {
            if (index + length > source.length) throw UnsupportedJavaPattern("short escape")
            val value = source.substring(index, index + length)
            index += length
            return value
        }

        private fun scalarEscape(hex: String, encoded: String, flags: Flags): String {
            if (!flags.caseInsensitive) return encoded
            val code = hex.toIntOrNull(16)
            if (code == null || code !in 'A'.code..'Z'.code && code !in 'a'.code..'z'.code) return encoded
            // DG102-F: escape spelling does not change a decoded letter's effective case flag.
            return buildString { appendLiteral(this, code.toChar(), flags) }
        }

        private fun certifiedCodePoint(hex: String): String {
            if (!forConsistency || !globalUnicodeCase) return hex
            val codePoint = hex.toIntOrNull(16)
            if (codePoint == null || codePoint !in 0..0xff) hasUnverifiedMapping = true
            return hex
        }

        private fun takeUntil(end: Char): String {
            val start = index
            while (index < source.length && source[index] != end) index++
            if (index >= source.length) throw UnsupportedJavaPattern("unclosed braced escape")
            val value = source.substring(start, index)
            index++
            return value
        }

        private fun namedCharacter(name: String): String {
            if (name == "LATIN CAPITAL LETTER A") return "\\u0041"
            if (forConsistency) {
                for ((prefix, base) in listOf("LATIN CAPITAL LETTER " to 'A', "LATIN SMALL LETTER " to 'a')) {
                    if (name.startsWith(prefix) && name.length == prefix.length + 1 && name.last() in 'A'..'Z') {
                        return "\\u{" + (base.code + name.last().code - 'A'.code).toString(16) + "}"
                    }
                }
            }
            throw UnsupportedJavaPattern("unsupported named character: $name")
        }
    }

    private val FLAG_LETTERS = setOf('d', 'i', 'x', 'm', 's', 'u', 'U')
}
