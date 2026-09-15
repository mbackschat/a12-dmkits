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
/**
 * The message grammar's own TERMINALS, which are not entity names however plain they look.
 *
 * `$BaseYear$` is admitted on a model that declares a base year and draws `NO_BASE_YEAR` on one that does
 * not (`RuleMessageParameterLawsTest.theBaseYearTerminalTakesAnUnboundedSignedOffset`) — never a reference
 * class — so handing it to a reader that reports unresolvable addresses would over-reject outright. Every
 * other form on this grammar's terminal list (`#Zeile`, a signed offset, a category suffix) carries a
 * character no plain segment admits and is already refused below.
 *
 * **Both language spellings are excluded regardless of the model's own language**, because an exclusion can
 * only under-report: only the English spelling is measured, and narrowing the set to the model's language
 * would be a claim about the German message grammar that nothing here has run.
 */
private val MESSAGE_TERMINALS = setOf("BaseYear", "Referenzjahr")

internal fun plainMessageParameters(text: String): List<String> =
    messageParameterBodies(text).mapNotNull { plainParameterName(it) }

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

/**
 * The entity path [parameter] names, or null when it is a form this reader does not claim to understand.
 *
 * The `.value` suffix is stripped because it is a suffix and never part of a name — A12's own exemption list
 * settles that, since `value` is not among the names a field may end with unquoted ([KF198]). Quotes are
 * stripped per segment, which is how a field whose name spells a terminal is written. A leading `/` is kept
 * and the path stays ABSOLUTE: `$/Policy/Premium$` draws the membership class like any other parameter
 * (measured), so admitting only relative ones would drop a real finding.
 *
 * **A `..` step is NOT admitted**, because A12 answers one with `ERROR_TEXT_PARAMETER_TOO_MANY_PARENT_GROUPS`
 * from a rule in a root group and no measurement covers a deeper carrier — a checker guessing that a legal
 * `..` resolves like a condition's would be reporting on a depth nobody has run.
 *
 * **No mutant can kill the plain-segment gate, and the reason is A12's name grammar rather than this code.**
 * Every non-plain form — a category suffix, a semantic-index key, a row specifier — currently resolves to
 * nothing anyway, and the one shape that could resolve, a quoted name carrying a space, cannot exist: A12
 * refuses the FIELD with `MVK_PATH_INVALID` (`MessageParameterMembershipLawsTest.aFieldNameMayNotCarryASpace`).
 * The gate stays because that is an external fact about A12, not a property of this reader, and without it
 * the contract above would hold only for as long as the name grammar does.
 *
 * **A name that spells a CONDITION keyword is an ordinary reference here**, bare or quoted: `$Date$` and
 * `$'Date'$` over a field named `Date` both draw the membership class, so the message grammar does not
 * resolve a parameter against the condition lexer's terminals. An earlier version excluded such names to be
 * safe; the measurement shows the exclusion only hid real findings, so it is gone.
 */
internal fun plainParameterName(parameter: String): String? {
    // No guard for an empty name: [isPlainSegment] refuses an empty segment, so `$$` and `$.value$` fall
    // out below. Written as its own branch first; the mutant that disabled it could not be killed.
    val name = parameter.removeSuffix(VALUE_SUFFIX)
    val absolute = name.startsWith('/')
    val segments = name.removePrefix("/").split('/')
    // A `..` step is admitted anywhere, the last position included: a path ending in one names the GROUP it
    // lands on and draws an ordinary membership refusal there, while one that climbs past the root group
    // draws a class of its own — exactly the split resolution already models (measured).
    if (segments.any { it != UP && !it.isPlainSegment() }) {
        return null
    }
    val joined = segments.joinToString("/") { it.trim('\'') }
    if (!absolute && joined in MESSAGE_TERMINALS) {
        return null
    }
    return if (absolute) "/$joined" else joined
}

/** A bare or single-quoted name — anything else carries a form with a gate of its own. */
private fun String.isPlainSegment(): Boolean {
    val bare = trim('\'')
    return bare.isNotEmpty() &&
        bare.first().isNameStart() &&
        bare.all { it.isNameStart() || it.isDigit() }
}

private fun Char.isNameStart(): Boolean = isLetter() || this == '_'

private const val UP = ".."

private const val VALUE_SUFFIX = ".value"
