package io.github.mbackschat.a12.dm.consistency.syntax

/**
 * The raw text inside each `$…$`, before any form is recognised — empty for an UNTERMINATED text.
 *
 * Shared so every reader of this grammar inherits the whole-text refusal rather than restating it: A12
 * answers `see $Premium$ and $Adjuster` with `WRONG_NUMBER_OF_DOLLARS` and nothing about `Premium`, so a
 * reader that kept the parameters it had already closed would report on a text A12 judged otherwise.
 */
internal fun messageParameterBodies(text: String): List<String> = scanParameters(text) ?: emptyList()

/**
 * Whether this text opens a parameter it never closes, which A12 answers `WRONG_NUMBER_OF_DOLLARS`.
 *
 * The same scan the bodies come from, so the refusal and the silence it causes cannot drift apart: what
 * makes [messageParameterBodies] yield nothing for a whole text is exactly what makes this true.
 */
internal fun messageParametersAreUnterminated(text: String): Boolean = scanParameters(text) == null

/** The bodies, or null where a parameter is never closed — the one distinction both callers above need. */
private fun scanParameters(text: String): List<String>? {
    val bodies = mutableListOf<String>()
    var index = 0
    while (index < text.length) {
        if (text[index] != '$') {
            index++
            continue
        }
        // No branch for the `$$` escape: it needs none, because the next `$` closes the parameter, so an
        // escaped dollar opens and closes an EMPTY one and every form reader yields nothing for it. Written
        // as an explicit branch first; the mutant that disabled it could not be killed by any input.
        val close = closingDollar(text, index + 1) ?: return null
        bodies += text.substring(index + 1, close)
        index = close + 1
    }
    return bodies
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
