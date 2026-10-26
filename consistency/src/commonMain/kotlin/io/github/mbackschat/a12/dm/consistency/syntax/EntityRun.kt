package io.github.mbackschat.a12.dm.consistency.syntax

/**
 * How far A12's `entity` production reaches from one token, and whether it CLOSES there.
 *
 * [end] is the index just past the last token the production consumed. [closes] is false where the production
 * began and then could not finish: a `/` or `.` with no step after it, or a `$` or `/` with no step at all. The
 * parser then fails at the token under [end].
 */
internal class EntityRun(val end: Int, val closes: Boolean)

/**
 * Read the flat path run used by the reference scanner.
 *
 * The scanner retains names from malformed text for editing; [ConditionRecognizer] owns syntax acceptance
 * and supplies the completed boundaries that cap unfinished walks (KF491). This projection deliberately
 * retains a climb after a named step, because the reference family owns that path-grid refusal.
 */
internal fun entityRunAt(tokens: List<Token>, from: Int, startsSegment: (Token) -> Boolean): EntityRun {
    var at = from
    if (tokens.getOrNull(at)?.text == OUTER_MARKER) {
        at++
    }
    if (tokens.getOrNull(at)?.text == SEPARATOR) {
        at++
    }
    at = stepEnd(tokens, at, startsSegment) ?: return EntityRun(at, closes = false)
    while (tokens.getOrNull(at)?.text == SEPARATOR) {
        at = stepEnd(tokens, at + 1, startsSegment) ?: return EntityRun(at + 1, closes = false)
    }
    return if (tokens.getOrNull(at)?.text == NAME_SEPARATOR) EntityRun(at, closes = false) else EntityRun(at, closes = true)
}

/**
 * The end of one step at [at]: a named segment, or a climb with the turning-point name or the star
 * `relativeUp` lets it carry (`relUp? '..' idName? '*'?`). Null where no step begins.
 *
 * A climb may carry the repetition wildcard and A12 parses it: `GroupFilled(..*)` is refused with the
 * SEMANTIC class `MVK_INVALID_WILDCARD_REL`, not a syntax one (`rulekit.validate.laws.StarredNonRepeatableReachLawsTest`).
 */
private fun stepEnd(tokens: List<Token>, at: Int, startsSegment: (Token) -> Boolean): Int? {
    val token = tokens.getOrNull(at) ?: return null
    if (token.text == UP) {
        val next = tokens.getOrNull(at + 1)
        return when {
            next != null && startsSegment(next) -> segmentEnd(tokens, at + 1)
            next?.text == WILDCARD -> at + 2
            else -> at + 1
        }
    }
    return if (startsSegment(token)) segmentEnd(tokens, at) else null
}

/**
 * The end of the segment starting at [at]: a name, its dotted sub-names, and the `*` a repetition may carry.
 * A `.` with neither a name nor a star after it is left unconsumed, which is where [entityRunAt] stops.
 */
private fun segmentEnd(tokens: List<Token>, at: Int): Int {
    var end = at + 1
    while (tokens.getOrNull(end)?.text == NAME_SEPARATOR) {
        val next = tokens.getOrNull(end + 1) ?: break
        if (next.isSegmentName() || next.text == WILDCARD) {
            end += 2
        } else {
            break
        }
    }
    if (tokens.getOrNull(end)?.text == WILDCARD) {
        end++
    }
    return end
}

private fun Token.isSegmentName(): Boolean =
    kind == TokenKind.NAME || kind == TokenKind.QUOTED_NAME || kind == TokenKind.NUMBER

private const val SEPARATOR = "/"
private const val NAME_SEPARATOR = "."
private const val UP = ".."
private const val WILDCARD = "*"
