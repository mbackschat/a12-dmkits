package io.github.mbackschat.a12.dm.consistency.syntax

internal sealed interface MessageGroupArgument {
    data class Path(val written: String, val trailing: Boolean) : MessageGroupArgument
    data class Refused(val code: String) : MessageGroupArgument
    data object Unmeasured : MessageGroupArgument
}

/**
 * Separates the measured group-argument path from tokens left after it (KF214, SPEC-2026-09-30-01).
 *
 * Names and quotes share A12's condition lexer terminals; punctuation and up-step placement have their
 * own message grammar (BaseErrorTextParameterLexer, GrammarOnly_ErrorTextParameterParser). The boundary
 * rows in MessageGroupArgumentAgreementTest prevent the broader condition grammar deciding their class.
 */
internal fun messageGroupArgument(argument: String, isStandaloneKeyword: (String) -> Boolean): MessageGroupArgument {
    val tokens = tokenize(argument)
    if (tokens.any { token -> when (token.kind) {
        TokenKind.UNTERMINATED_QUOTED_NAME -> true
        TokenKind.ILLEGAL_CHARACTER -> token.text != "#"
        TokenKind.PUNCTUATION -> token.text !in MESSAGE_PUNCTUATION
        TokenKind.OPERATOR_SYMBOL -> token.text == "*" || token.text == "^"
        else -> false
    } }) {
        return MessageGroupArgument.Refused("MVK_LEXER_STANDARD_ERROR")
    }
    if (tokens.any { it.kind in listOf(TokenKind.STRING, TokenKind.UNTERMINATED_STRING, TokenKind.COMMENT) ||
        it.text in listOf("#", "For", "Fuer") || it.kind == TokenKind.OPERATOR_SYMBOL }) {
        return MessageGroupArgument.Unmeasured
    }
    val first = tokens.firstOrNull()
    if (tokens.size > 1 && first?.kind == TokenKind.NAME && isStandaloneKeyword(first.text)) {
        return MessageGroupArgument.Unmeasured
    }
    val run = entityRunAt(tokens, 0) {
        it.kind == TokenKind.NAME || it.kind == TokenKind.QUOTED_NAME || it.kind == TokenKind.NUMBER
    }
    // Unlike the condition reference scanner, the message entity grammar permits up-steps only before
    // its first name, without an initial slash (the labelled and absolute up-step controls in KF214).
    var mayClimb = first?.text != "/"
    for (token in tokens.take(run.end)) {
        when (token.kind) {
            TokenKind.NAME, TokenKind.QUOTED_NAME, TokenKind.NUMBER -> mayClimb = false
            TokenKind.PUNCTUATION -> if (token.text == ".." && !mayClimb) {
                return MessageGroupArgument.Refused("MVK_UNEXPECTED_TOKEN")
            }
            else -> Unit
        }
    }
    if (!run.closes) {
        // EntityRun also scans dotted condition names; their incomplete message spelling is outside the
        // slash-separator grid in MessageGroupArgumentAgreementTest, unlike the measured `...` up-step.
        if (tokens.getOrNull(run.end)?.text == "." && tokens.getOrNull(run.end - 1)?.text != "..") {
            return MessageGroupArgument.Unmeasured
        }
        return MessageGroupArgument.Refused(if (run.end == tokens.size) "MVK_INCOMPLETE_INPUT" else "MVK_UNEXPECTED_TOKEN")
    }
    val remaining = tokens.drop(run.end)
    val written = tokens.take(run.end).joinToString("") {
        if (it.kind == TokenKind.QUOTED_NAME) "'${it.text}'" else it.text
    }
    return MessageGroupArgument.Path(written, remaining.isNotEmpty())
}

private val MESSAGE_PUNCTUATION = setOf("/", "-", ".", "..", "(", ")", "#", "->")
