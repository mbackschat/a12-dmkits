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
 * **`$$` is an escaped dollar and an odd count is A12's own refusal.** A text with an unterminated parameter
 * draws `WRONG_NUMBER_OF_DOLLARS` (measured), so this yields nothing there rather than guessing where the
 * parameter ended.
 */
internal fun plainMessageParameters(text: String, keywords: Set<String>): List<String> {
    val names = mutableListOf<String>()
    var index = 0
    while (index < text.length) {
        if (text[index] != '$') {
            index++
            continue
        }
        if (index + 1 < text.length && text[index + 1] == '$') {
            index += 2
            continue
        }
        val close = closingDollar(text, index + 1) ?: return emptyList()
        plainNameOrNull(text.substring(index + 1, close), keywords)?.let { names += it }
        index = close + 1
    }
    return names
}

/** The next unescaped `$` at or after [from], or null when the parameter is never closed. */
private fun closingDollar(text: String, from: Int): Int? {
    var index = from
    while (index < text.length) {
        if (text[index] == '$') {
            if (index + 1 < text.length && text[index + 1] == '$') {
                index += 2
                continue
            }
            return index
        }
        index++
    }
    return null
}

/**
 * The entity path [parameter] names, or null when it is a form this reader does not claim to understand.
 *
 * The `.value` suffix is stripped because it is a suffix and never part of a name — A12's own exemption list
 * settles that, since `value` is not among the names a field may end with unquoted ([KF198]). Quotes are
 * stripped per segment, which is how a field whose name spells a terminal is written. A single-segment name
 * that is a keyword of the model's condition language is left alone: the message grammar's terminal set is
 * NARROWER than the condition's, so a name in that set may be a terminal here, and this reader has no
 * measurement telling the two apart.
 */
private fun plainNameOrNull(parameter: String, keywords: Set<String>): String? {
    val name = parameter.removeSuffix(VALUE_SUFFIX)
    if (name.isEmpty()) {
        return null
    }
    val segments = name.split('/')
    if (segments.any { !it.isPlainSegment() }) {
        return null
    }
    val unquoted = segments.joinToString("/") { it.trim('\'') }
    if (segments.size == 1 && unquoted in keywords) {
        return null
    }
    return unquoted
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
