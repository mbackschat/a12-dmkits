package io.github.mbackschat.a12.dm.consistency.syntax

internal enum class MessageGroupKeyword { RULE, ROOT, RETIRED }

internal sealed interface MessageGroupArgument {
    data class Path(val written: String, val trailing: Boolean) : MessageGroupArgument
    data class Keyword(val kind: MessageGroupKeyword, val trailing: Boolean) : MessageGroupArgument
    data class Refused(val code: String) : MessageGroupArgument
    data object Unmeasured : MessageGroupArgument
}

/**
 * Reads the message grammar's group argument, stopping where its first fault or semantic check answers.
 *
 * A12's GrammarOnly_ErrorTextParameterParser chooses keyword versus name from the following token,
 * checks relativeUp before descending, and checks the completed argument before EOF. KF214's
 * MessageGroupEventAgreementTest distinguishes these events from whole-text lexer/syntax priority.
 * Tokenization shares the name/quote terminals with conditions; token validity is checked only on access.
 */
internal fun messageGroupArgument(
    argument: String,
    language: ConditionLanguage,
    checkParent: (String) -> String?,
): MessageGroupArgument = MessageGroupReader(tokenize(argument), language, checkParent).read()

private enum class MessageWord { NAME, RULE, ROOT, LINE, USB, FOR, BASE_YEAR }

private class MessageGroupReader(
    private val tokens: List<Token>,
    private val language: ConditionLanguage,
    private val checkParent: (String) -> String?,
) {
    private var at = 0
    private var reachedLexerFault = false

    fun read(): MessageGroupArgument {
        val result = argument()
        return if (reachedLexerFault) refused(LEXER) else result
    }

    private fun argument(): MessageGroupArgument {
        val first = peek() ?: return refused(INCOMPLETE)
        return when (word(first)) {
            MessageWord.RULE -> {
                advance()
                finish(MessageGroupKeyword.RULE)
            }
            MessageWord.ROOT, MessageWord.LINE, MessageWord.USB -> {
                val next = peek(1)
                when {
                    next?.text in listOf("/", ".") -> path()
                    next?.let(::word) == MessageWord.FOR -> MessageGroupArgument.Unmeasured
                    next == null -> {
                        advance()
                        finish(if (word(first) == MessageWord.ROOT) MessageGroupKeyword.ROOT else MessageGroupKeyword.RETIRED)
                    }
                    word(first) == MessageWord.LINE && next.text in listOf("+", "-") -> offset()
                    else -> refused(UNEXPECTED)
                }
            }
            MessageWord.FOR, MessageWord.BASE_YEAR -> refused(UNEXPECTED)
            MessageWord.NAME -> path()
        }
    }

    private fun offset(): MessageGroupArgument {
        advance()
        advance()
        val digits = peek() ?: return refused("MVK_EXPECTED_TOKEN_NOT_FOUND")
        if (digits.kind != TokenKind.NUMBER) return refused(UNEXPECTED)
        advance()
        return finish(MessageGroupKeyword.RETIRED)
    }

    private fun path(): MessageGroupArgument {
        if (peek()?.text == "..") {
            do {
                advance()
                if (peek()?.text != "/" || peek(1)?.text != "..") break
                advance()
            } while (true)
            if (isName(peek())) name()?.let { return it }
            // relativeUp's optional label cannot consume a reserved word. The grammar decides that
            // before CheckRelativeUpImpl runs; the word/turning-point rows distinguish it from lookup.
            val next = peek()
            if (next?.text == "." || next?.let(::word) in listOf(MessageWord.RULE, MessageWord.BASE_YEAR)) {
                return refused(UNEXPECTED)
            }
            if (reachedLexerFault) return refused(LEXER)
            checkParent(written())?.let { return refused(it) }
        } else {
            if (peek()?.text == "/") advance()
            name()?.let { return it }
        }
        while (peek()?.text == "/") {
            advance()
            name()?.let { return it }
        }
        if (outsideMeasuredTail()) return MessageGroupArgument.Unmeasured
        return MessageGroupArgument.Path(written(), peek() != null)
    }

    private fun name(): MessageGroupArgument? {
        val token = peek() ?: return refused(INCOMPLETE)
        if (word(token) in listOf(MessageWord.RULE, MessageWord.FOR, MessageWord.BASE_YEAR)) return refused(UNEXPECTED)
        if (outsideMeasuredTail()) return MessageGroupArgument.Unmeasured
        if (!isName(token)) return refused(UNEXPECTED)
        advance()
        while (peek()?.text == ".") {
            advance()
            val suffix = peek()
            // Dotted-name suffix alternatives have a separate quoting-exempt boundary in idName;
            // KF214 does not establish the broader dotted-name grammar.
            if (suffix == null || suffix.kind !in listOf(TokenKind.NAME, TokenKind.QUOTED_NAME) ||
                word(suffix) != MessageWord.NAME) return MessageGroupArgument.Unmeasured
            advance()
        }
        return null
    }

    private fun finish(keyword: MessageGroupKeyword): MessageGroupArgument =
        if (outsideMeasuredTail()) MessageGroupArgument.Unmeasured else MessageGroupArgument.Keyword(keyword, peek() != null)

    private fun outsideMeasuredTail(): Boolean = peek()?.let {
        word(it) == MessageWord.FOR || it.kind in listOf(TokenKind.STRING, TokenKind.UNTERMINATED_STRING, TokenKind.COMMENT) ||
            it.text == "#"
    } == true

    private fun isName(token: Token?): Boolean = token != null && when (token.kind) {
        TokenKind.QUOTED_NAME, TokenKind.NUMBER -> true
        TokenKind.NAME -> word(token) in listOf(MessageWord.NAME, MessageWord.ROOT, MessageWord.LINE, MessageWord.USB)
        else -> false
    }

    private fun word(token: Token): MessageWord {
        if (token.kind != TokenKind.NAME) return MessageWord.NAME
        return when (token.text) {
            if (language == ConditionLanguage.EN) "RuleGroup" else "RegelKontext" -> MessageWord.RULE
            if (language == ConditionLanguage.EN) "RootGroup" else "LfdNr" -> MessageWord.ROOT
            if (language == ConditionLanguage.EN) "For" else "Fuer" -> MessageWord.FOR
            if (language == ConditionLanguage.EN) "BaseYear" else "Referenzjahr" -> MessageWord.BASE_YEAR
            "Zeile" -> MessageWord.LINE
            "Usb" -> MessageWord.USB
            else -> MessageWord.NAME
        }
    }

    private fun written(): String = tokens.take(at).joinToString("") {
        if (it.kind == TokenKind.QUOTED_NAME) "'${it.text}'" else it.text
    }

    private fun advance() {
        at++
        // ANTLR's consume fetches the next token before running a production's semantic action.
        peek()
    }

    private fun peek(ahead: Int = 0): Token? = tokens.getOrNull(at + ahead)?.also { token ->
        val invalid = when (token.kind) {
            TokenKind.UNTERMINATED_QUOTED_NAME -> true
            TokenKind.ILLEGAL_CHARACTER -> token.text != "#"
            TokenKind.PUNCTUATION, TokenKind.OPERATOR_SYMBOL -> token.text !in MESSAGE_SYMBOLS
            else -> false
        }
        if (invalid) reachedLexerFault = true
    }
}

private fun refused(code: String) = MessageGroupArgument.Refused(code)
private const val LEXER = "MVK_LEXER_STANDARD_ERROR"
private const val UNEXPECTED = "MVK_UNEXPECTED_TOKEN"
private const val INCOMPLETE = "MVK_INCOMPLETE_INPUT"
private val MESSAGE_SYMBOLS = setOf("/", "+", "-", ".", "..", "(", ")", "#", "->")
