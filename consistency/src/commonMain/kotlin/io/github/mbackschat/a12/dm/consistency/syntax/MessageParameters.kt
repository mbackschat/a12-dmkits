package io.github.mbackschat.a12.dm.consistency.syntax

/**
 * The PLAIN entity parameters an error message names — `$Name$` and `$Name.value$`, and nothing else.
 *
 * A12's message grammar is a third DSL beside the condition and operation ones, and it is wide: a category
 * suffix, a base-year terminal with a signed offset, a semantic-index key, a row specifier, its own quoting
 * rule. Each of those has its own gate and its own class ([KERNEL-FINDINGS KF198], [KF335]), so this reader
 * deliberately recognises the one form whose class is measured and yields NOTHING for the rest. Silence is
 * an under-report, which is safe; handing a caller a name lifted out of a form whose gate it does not know
 * would make it report the wrong class.
 *
 * **An unterminated parameter refuses the text WHOLE, so this yields nothing for any of it** — not even for
 * the parameters it had already closed. `see $Premium$ and $Adjuster` draws `WRONG_NUMBER_OF_DOLLARS` and no
 * membership class for `Premium` (measured), so returning the names collected before the thread was lost
 * would report on a text A12 answered otherwise. `$$` outside a parameter is A12's escape, and
 * [closingDollar] is why no branch here has to know that.
 */
internal fun plainMessageParameters(text: String): List<String> {
    val names = mutableListOf<String>()
    var index = 0
    while (index < text.length) {
        if (text[index] != '$') {
            index++
            continue
        }
        // No branch for the `$$` escape: it needs none, because the next `$` closes the parameter, so an
        // escaped dollar opens and closes an EMPTY one and [plainNameOrNull] yields nothing for it. Written
        // as an explicit branch first; the mutant that disabled it could not be killed by any input.
        val close = closingDollar(text, index + 1) ?: return emptyList()
        plainNameOrNull(text.substring(index + 1, close))?.let { names += it }
        index = close + 1
    }
    return names
}

/**
 * The `$` that closes the parameter opened before [from], or null when it is never closed.
 *
 * **The escape does not reach in here: inside a parameter the first `$` ends it**, so two parameters may sit
 * back to back sharing a dollar pair. Measured — `see $Premium$$Adjuster$` draws the membership class for
 * BOTH names, which reading the pair as an escape could not produce, since `Premium$$Adjuster` resolves to
 * nothing and would have drawn `INVALID_ENTITY` instead.
 */
private fun closingDollar(text: String, from: Int): Int? {
    val close = text.indexOf('$', from)
    return if (close < 0) null else close
}

/**
 * The entity path [parameter] names, or null when it is a form this reader does not claim to understand.
 *
 * The `.value` suffix is stripped because it is a suffix and never part of a name — A12's own exemption list
 * settles that, since `value` is not among the names a field may end with unquoted ([KF198]). Quotes are
 * stripped per segment, which is how a field whose name spells a terminal is written.
 *
 * **A name that spells a CONDITION keyword is an ordinary reference here**, bare or quoted: `$Date$` and
 * `$'Date'$` over a field named `Date` both draw the membership class, so the message grammar does not
 * resolve a parameter against the condition lexer's terminals. An earlier version excluded such names to be
 * safe; the measurement shows the exclusion only hid real findings, so it is gone.
 */
private fun plainNameOrNull(parameter: String): String? {
    val name = parameter.removeSuffix(VALUE_SUFFIX)
    if (name.isEmpty()) {
        return null
    }
    val segments = name.split('/')
    if (segments.any { !it.isPlainSegment() }) {
        return null
    }
    return segments.joinToString("/") { it.trim('\'') }
}

/** A bare or single-quoted name — anything else carries a form with a gate of its own. */
private fun String.isPlainSegment(): Boolean {
    val bare = trim('\'')
    return bare.isNotEmpty() &&
        bare.first().isNameStart() &&
        bare.all { it.isNameStart() || it.isDigit() }
}

private fun Char.isNameStart(): Boolean = isLetter() || this == '_'

private const val VALUE_SUFFIX = ".value"
