package io.github.mbackschat.a12.dm.predicates

/**
 * A12's admission blacklist for an authored regular expression — the one live statement of it.
 *
 * The kernel puts a pattern through two stages ([KERNEL-FINDINGS KF165]): it must compile as a Java regular
 * expression, and its SOURCE TEXT must avoid a finite blacklist. Only the second is portable, and it is what
 * this predicate answers; the first belongs to one regex engine and every caller leaves it to A12.
 *
 * **The gate reads TEXT, not the expression the text spells**, and reproducing that exactly is the whole
 * point. `a\*+` is refused because the source carries `*` immediately before `+`, though it means an escaped
 * star with a quantifier; `a\+\+` and `\[a\[b` are admitted because neither spells a listed adjacency. An
 * implementation that consumed escapes while scanning would read the pattern instead of the text — which is
 * what `KernelPatternPolicy` did alone until the grid in `rulekit.validate.laws.DeclaredPatternGateLawsTest`
 * was measured, admitting `a\*+` and refusing `\P{L}`, which A12 takes.
 *
 * @return a short description of the listed construct, or null when the text carries none.
 */
fun kernelPatternBlacklist(pattern: String): String? {
    for (index in pattern.indices) {
        val here = pattern[index]
        val next = pattern.getOrNull(index + 1)
        if (next == '+' && here in QUANTIFIABLE) {
            return "`$here+`, a possessive quantifier"
        }
        if (here == '\\' && next != null && next in LISTED_ESCAPES) {
            return "the escape `\\$next`"
        }
        if (here == '(' && next == '?' && pattern.getOrNull(index + 2) in GROUP_PREFIXES) {
            return "`(?${pattern[index + 2]}`, a lookbehind, a named capture group or an atomic group"
        }
        if (here == '[' && !pattern.escapedAt(index) && pattern.opensAgainBefore(index)) {
            return "a second unescaped `[` before the first one closes"
        }
    }
    return null
}

/** The escapes A12 refuses. Uppercase `P` is deliberately absent: `\P{L}` is admitted, measured. */
private const val LISTED_ESCAPES = "AGZzaepQE"

/** The characters a `+` may not follow, which is how the gate spells "possessive quantifier". */
private const val QUANTIFIABLE = "?+}*"

/** The two characters after `(?` that the gate refuses, covering three different Java constructs. */
private val GROUP_PREFIXES = setOf('<', '>')

/**
 * Whether the character before [index] is a backslash — the gate's own test, and deliberately not an
 * escape-parity one: `\\[a[b` spells an escaped backslash followed by a class, and the gate still skips that
 * `[` because a backslash precedes it. Reproducing the quirk is what keeps this predicate exact.
 */
private fun String.escapedAt(index: Int): Boolean = index > 0 && this[index - 1] == '\\'

/** Whether another unescaped `[` follows [index] with no `]` between them. */
private fun String.opensAgainBefore(index: Int): Boolean {
    var scan = index + 1
    while (scan < length && this[scan] != ']') {
        if (this[scan] == '[' && !escapedAt(scan)) {
            return true
        }
        scan++
    }
    return false
}
