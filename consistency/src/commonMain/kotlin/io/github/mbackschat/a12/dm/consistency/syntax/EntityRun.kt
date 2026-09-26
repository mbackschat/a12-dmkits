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
 * The run of A12's `entity` production beginning at [from], read as the grammar reads it.
 *
 * The shape is `entity: ('/'? idNameRef | relativeUp) ('/' idNameRef)*`, with `idNameRef: idName ('.' '*')? '*'?`
 * and `idName: nameOhnePunkt ('.' Name)*` (`GrammarOnly_ConditionParser.g4`, source-read, kernel `30.8.1`). So
 * steps are joined by exactly one `/`, and a name directly after a step ENDS the production rather than
 * continuing it.
 *
 * **Why both readers share it.** A12 raises a reference's class when its production closes, before the
 * parser matches the token after it, because the error strategy does not look ahead (`AbstractDefaultErrorStrategy.sync`
 * does nothing, and no error is recovered from). So in `FieldFilled(BillingAddress*PostalCode)` the entity
 * `BillingAddress*` closes and draws `MVK_INVALID_WILDCARD` before `PostalCode` is refused, and in
 * `FieldFilled(Lines/)` the entity never closes and only the parse error is drawn
 * (`adapter.laws.CheckerNearMissSweepTest`). The shape parser needs the failure point and the reference
 * scanner needs the close point. They are the same walk, and two walks that drifted would give one text two
 * different references.
 *
 * One departure is deliberate. A climb after a named step (`A/../B`) is refused by the grammar, which admits
 * `..` only at the start. It is read here as a step, because the reference family owns that refusal and
 * reports it with the class measured on the path grid.
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

private const val OUTER_MARKER = "$"
private const val SEPARATOR = "/"
private const val NAME_SEPARATOR = "."
private const val UP = ".."
private const val WILDCARD = "*"
