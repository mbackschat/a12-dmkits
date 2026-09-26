package io.github.mbackschat.a12.dm.consistency

/**
 * A declared String pattern in the subset this checker evaluates exactly as Java's `String.matches` does, on
 * both of its targets.
 *
 * A12 tests a constant index key with `value.matches(pattern)`, a whole-value Java match
 * (`IStringType.isValueApplicable`, source-read; [KERNEL-FINDINGS KF481]). The checker also runs as JavaScript,
 * whose regex parts ways with Java's in places, so rather than hand a pattern to either engine it recognizes a
 * subset and matches it itself. An atom is a letter, a digit, a space, `_` or `-`, or a bracket class of letters,
 * digits, spaces and `_` and of ranges between them, optionally negated. Each atom is optionally quantified by
 * `?`, `*`, `+`, `{n}`, `{n,}` or `{n,m}`. Everything else is outside it: `.` (Java's excludes U+0085, which
 * JavaScript's admits), escapes, groups, alternation, anchors, class unions and intersections, and lazy or
 * possessive quantifiers. The boundary rows are `adapter.laws.CheckerIndexKeyConstantAgreementTest`'s.
 */
internal class KeyPattern private constructor(private val atoms: List<Atom>) {

    /** One atom: which code points it takes, and how many times. */
    private class Atom(val admits: (Int) -> Boolean, val min: Int, val max: Int)

    /**
     * Whether the whole of [value] matches. Java matches code points, so a surrogate pair is one character to a
     * negated class and so is a lone surrogate.
     */
    fun matches(value: String): Boolean = matchesFrom(value.javaCodePoints(), 0, 0)

    private fun matchesFrom(value: List<Int>, at: Int, next: Int): Boolean {
        if (next == atoms.size) {
            return at == value.size
        }
        val atom = atoms[next]
        var taken = 0
        while (taken < atom.max && at + taken < value.size && atom.admits(value[at + taken])) {
            taken++
        }
        return (taken downTo atom.min).any { count -> matchesFrom(value, at + count, next + 1) }
    }

    companion object {
        /** [pattern] as a [KeyPattern], or null where it is outside the subset. */
        fun parse(pattern: String): KeyPattern? {
            val atoms = mutableListOf<Atom>()
            var at = 0
            while (at < pattern.length) {
                val char = pattern[at]
                val (admits, afterAtom) = when {
                    char == '[' -> classAt(pattern, at) ?: return null
                    char.isClassMember() || char == '-' -> Pair({ c: Int -> c == char.code }, at + 1)
                    else -> return null
                }
                val (min, max, afterQuantifier) = quantifierAt(pattern, afterAtom) ?: return null
                atoms += Atom(admits, min, max)
                at = afterQuantifier
            }
            return KeyPattern(atoms)
        }

        /** The characters both a bare atom and a class member may be. */
        private fun Char.isClassMember(): Boolean =
            this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' || this == '_' || this == ' '

        /** The bracket class opening at [open], and where it ends, or null outside the subset. */
        private fun classAt(pattern: String, open: Int): Pair<(Int) -> Boolean, Int>? {
            var at = open + 1
            val negated = pattern.getOrNull(at) == '^'
            if (negated) {
                at++
            }
            val ranges = mutableListOf<IntRange>()
            while (at < pattern.length && pattern[at] != ']') {
                val first = pattern[at].takeIf { it.isClassMember() } ?: return null
                if (pattern.getOrNull(at + 1) == '-') {
                    val last = pattern.getOrNull(at + 2)?.takeIf { it.isClassMember() } ?: return null
                    if (last < first) {
                        return null
                    }
                    ranges += first.code..last.code
                    at += 3
                } else {
                    ranges += first.code..first.code
                    at++
                }
            }
            if (at >= pattern.length || ranges.isEmpty()) {
                return null
            }
            return Pair({ c: Int -> ranges.any { c in it } != negated }, at + 1)
        }

        /** The quantifier at [at] as (min, max, end), (1, 1, [at]) where there is none, or null outside the subset. */
        private fun quantifierAt(pattern: String, at: Int): Triple<Int, Int, Int>? = when (pattern.getOrNull(at)) {
            '?' -> Triple(0, 1, at + 1)
            '*' -> Triple(0, Int.MAX_VALUE, at + 1)
            '+' -> Triple(1, Int.MAX_VALUE, at + 1)
            '{' -> {
                val close = pattern.indexOf('}', at)
                val bounds = if (close < 0) null else pattern.substring(at + 1, close).split(',')
                val min = bounds?.firstOrNull()?.toCountOrNull()
                val max = when {
                    bounds == null || min == null -> null
                    bounds.size == 1 -> min
                    bounds.size == 2 && bounds[1].isEmpty() -> Int.MAX_VALUE
                    bounds.size == 2 -> bounds[1].toCountOrNull()?.takeIf { it >= min }
                    else -> null
                }
                if (min == null || max == null) null else Triple(min, max, close + 1)
            }
            else -> Triple(1, 1, at)
        }

        private fun String.toCountOrNull(): Int? = takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toIntOrNull()
    }
}
