package io.github.mbackschat.a12.dm.consistency.syntax

internal data class MessageEntity(val written: String, val ruleGroup: Boolean = false)
internal data class MessageKey(val literal: String? = null, val entity: MessageEntity? = null)
internal enum class MessageSuffix { NONE, VALUE, CATEGORY, FORM_LINE }

internal sealed interface MessageParameter {
    data class Field(val entity: MessageEntity, val suffix: MessageSuffix, val category: String?, val key: MessageKey?) : MessageParameter
    data class Group(val entity: MessageEntity, val key: MessageKey?) : MessageParameter
    data class Index(val entity: MessageEntity, val suffix: MessageSuffix, val category: String?) : MessageParameter
    data object BaseYear : MessageParameter
    data object RootRow : MessageParameter
    data object Line : MessageParameter
    data object User : MessageParameter
}

internal sealed interface MessageParameterRead {
    data class Parsed(val parameter: MessageParameter) : MessageParameterRead
    data class Refused(val code: String) : MessageParameterRead
    data object Unmeasured : MessageParameterRead
}

internal sealed interface MessageCompletion {
    data object Accepted : MessageCompletion
    data object Declined : MessageCompletion
    data class Refused(val code: String) : MessageCompletion
}

/** Semantic callbacks occur at the grammar's action boundaries, not after a whole-parameter parse (KF501). */
internal interface MessageParameterActions {
    fun climb(steps: Int): String? = null
    fun parent(written: String): String? = null
    fun name(written: String, groupPosition: Boolean, indexPosition: Boolean): String? = null
    fun completed(parameter: MessageParameter): MessageCompletion = MessageCompletion.Accepted
}

/** One token cursor for every message-parameter alternative; the first reached event ends the read. */
internal fun readMessageParameter(
    text: String,
    language: ConditionLanguage,
    actions: MessageParameterActions,
): MessageParameterRead = MessageParameterReader(messageTokens(text), language, actions).read()

private class MessageReadFailure(val code: String?) : RuntimeException()
private enum class Word { NAME, RULE, ROOT, LINE, USER, FOR, BASE_YEAR, VALUE, FORM_LINE, INDEX }

private class MessageParameterReader(
    private val tokens: List<Token>,
    private val language: ConditionLanguage,
    private val actions: MessageParameterActions,
) {
    private var at = 0
    private var predicting = false
    private val groupPosition = tokens.firstOrNull()?.text == "#"

    fun read(): MessageParameterRead = try {
        val parameter = when {
            take("#") -> group()
            word(peek()) == Word.BASE_YEAR -> { consume(); offset(); MessageParameter.BaseYear }
            word(peek()) == Word.INDEX && peek(1)?.text == "(" -> index()
            else -> field()
        }
        when (val completion = actions.completed(parameter)) {
            MessageCompletion.Accepted -> Unit
            MessageCompletion.Declined -> throw MessageReadFailure(null)
            is MessageCompletion.Refused -> fail(completion.code)
        }
        if (peek() != null) fail(UNEXPECTED)
        MessageParameterRead.Parsed(parameter)
    } catch (failure: MessageReadFailure) {
        failure.code?.let { MessageParameterRead.Refused(it) } ?: MessageParameterRead.Unmeasured
    }

    private fun group(): MessageParameter {
        val kind = word(peek())
        if (kind in listOf(Word.ROOT, Word.LINE, Word.USER)) {
            val next = peek(1)
            if (next == null || kind == Word.LINE && next.text in listOf("+", "-")) {
                consume()
                return when (kind) {
                    Word.ROOT -> MessageParameter.RootRow
                    Word.LINE -> { offset(); MessageParameter.Line }
                    else -> MessageParameter.User
                }
            }
            if (next.text !in listOf("/", ".") && word(next) != Word.FOR) fail(UNEXPECTED)
        }
        predictEntity(checkFollower = false)
        val entity = entity(indexPosition = false)
        return MessageParameter.Group(entity, optionalKey())
    }

    private fun field(): MessageParameter.Field {
        predictEntity()
        val entity = entity(indexPosition = false)
        val (suffix, category) = suffix()
        return MessageParameter.Field(entity, suffix, category, optionalKey())
    }

    private fun index(): MessageParameter.Index {
        consume()
        require("(")
        val entity = entity(indexPosition = true)
        require(")")
        val (suffix, category) = suffix()
        return MessageParameter.Index(entity, suffix, category)
    }

    private fun offset() {
        if (peek()?.text !in listOf("+", "-")) return
        consume()
        val digits = peek() ?: fail(EXPECTED)
        if (digits.kind != TokenKind.NUMBER) fail(UNEXPECTED)
        consume()
    }

    private fun suffix(): Pair<MessageSuffix, String?> {
        if (take("->")) {
            val name = peek()?.takeIf { isName(it) }?.let { consume().text }
            return MessageSuffix.CATEGORY to name
        }
        if (take(".")) {
            val suffix = when (word(peek())) {
                Word.VALUE -> MessageSuffix.VALUE
                Word.FORM_LINE -> MessageSuffix.FORM_LINE
                else -> fail(UNEXPECTED)
            }
            consume()
            return suffix to null
        }
        return MessageSuffix.NONE to null
    }

    private fun optionalKey(): MessageKey? {
        if (word(peek()) != Word.FOR) return null
        consume()
        return key()
    }

    private fun key(): MessageKey {
        if (peek()?.kind == TokenKind.STRING) return MessageKey(literal = consume().text.decodeAuthoredStringLiteral())
        predictEntity(checkFollower = false)
        val entity = entity(indexPosition = false)
        if (word(peek()) == Word.FOR) {
            consume()
            key()
            fail("ILLEGAL_SEMANTIC_INDEX")
        }
        return MessageKey(entity = entity)
    }

    /**
     * An ordinary path must be syntactically complete before name actions start. Only the top-level
     * field choice also predicts past a form/value suffix. Group/key positions select a parent walk
     * immediately, so its action may precede a later slash fault (KF501 F1a/F1f/F2a). The field choice
     * still predicts that path first (PathSegmentWalkLawsTest).
     */
    private fun predictEntity(checkFollower: Boolean = true) {
        if (!checkFollower && peek()?.text == "..") return
        val saved = at
        predicting = true
        entity(indexPosition = false)
        val suffix = if (checkFollower && peek()?.text == ".") suffix().first else MessageSuffix.NONE
        val next = peek()
        // KF501's form alternatives end at the suffix; selecting them cannot consume a field's key.
        if (word(next) == Word.FOR && suffix == MessageSuffix.FORM_LINE) fail(UNEXPECTED)
        if (checkFollower && next != null && next.text !in listOf("->", ".", ")") && word(next) != Word.FOR) fail(UNEXPECTED)
        at = saved
        predicting = false
    }

    private fun entity(indexPosition: Boolean): MessageEntity {
        if (word(peek()) == Word.RULE) return MessageEntity(consume().text, ruleGroup = true)
        var written: String
        if (peek()?.text == "..") {
            var steps = 0
            // A run of '../' belongs to the prefix only when another UP follows, or when the
            // final UP has no name to complete it. The parent-grid distinguishes that extra step.
            while (peek()?.text == ".." && peek(1)?.text == "/" &&
                (peek(2)?.text == ".." || steps > 0 && !isName(peek(2)))) {
                consume(); consume(); steps++
            }
            if (steps > 0) action { actions.climb(steps + 1) }
            if (!take("..")) fail(if (peek() == null) EXPECTED else UNEXPECTED)
            val turning = if (isName(peek())) name(indexPosition, turning = true) else null
            written = List(steps + 1) { ".." }.joinToString("/") + turning.orEmpty()
            action { actions.parent(written) }
        } else {
            val absolute = take("/")
            written = (if (absolute) "/" else "") + name(indexPosition)
            action { actions.name(written, groupPosition, indexPosition) }
        }
        while (take("/")) {
            written += "/" + name(indexPosition)
            action { actions.name(written, groupPosition, indexPosition) }
        }
        return MessageEntity(written)
    }

    private fun name(indexPosition: Boolean, turning: Boolean = false): String {
        val first = peek() ?: fail(INCOMPLETE)
        if (!isName(first)) fail(UNEXPECTED)
        consume()
        var written = quoted(first)
        while (peek()?.text == ".") {
            val next = peek(1)
            if (!groupPosition && !turning && !indexPosition && word(next) in
                listOf(Word.VALUE, Word.FORM_LINE) && peek(2)?.text != ".") break
            // Preserve the explicit bounded decline in MessageGroupDottedBoundaryAgreementTest.
            if (next == null || next.kind !in listOf(TokenKind.NAME, TokenKind.QUOTED_NAME) || word(next) != Word.NAME)
                throw MessageReadFailure(null)
            consume()
            written += "." + quoted(consume())
        }
        // A12 checks the turning point's first token; trailing dotted components are not its group name.
        return if (turning) quoted(first) else written
    }

    private inline fun action(check: () -> String?) {
        if (!predicting) check()?.let { fail(it) }
    }

    private fun isName(token: Token?): Boolean = token != null && when (token.kind) {
        TokenKind.NAME -> word(token) !in listOf(Word.RULE, Word.FOR, Word.BASE_YEAR)
        TokenKind.QUOTED_NAME, TokenKind.NUMBER -> true
        else -> false
    }

    private fun word(token: Token?): Word {
        if (token?.kind != TokenKind.NAME) return Word.NAME
        return when (token.text) {
            if (language == ConditionLanguage.EN) "RuleGroup" else "RegelKontext" -> Word.RULE
            if (language == ConditionLanguage.EN) "RootGroup" else "LfdNr" -> Word.ROOT
            if (language == ConditionLanguage.EN) "For" else "Fuer" -> Word.FOR
            if (language == ConditionLanguage.EN) "BaseYear" else "Referenzjahr" -> Word.BASE_YEAR
            if (language == ConditionLanguage.EN) "value" else "Wert" -> Word.VALUE
            "Vordruckzeile" -> Word.FORM_LINE
            "index" -> Word.INDEX
            "Zeile" -> Word.LINE
            "Usb" -> Word.USER
            else -> Word.NAME
        }
    }

    private fun peek(ahead: Int = 0): Token? = tokens.getOrNull(at + ahead)?.also {
        if (it.kind == TokenKind.ILLEGAL_CHARACTER) fail(LEXER)
    }
    private fun consume(): Token = (peek() ?: fail(INCOMPLETE)).also { at++; peek() }
    private fun take(text: String): Boolean = if (peek()?.text == text) { consume(); true } else false
    private fun require(text: String) { if (!take(text)) fail(if (peek() == null) EXPECTED else UNEXPECTED) }
    private fun fail(code: String): Nothing = throw MessageReadFailure(code)
}

private fun quoted(token: Token): String = if (token.kind == TokenKind.QUOTED_NAME) "'${token.text}'" else token.text
private const val LEXER = "MVK_LEXER_STANDARD_ERROR"
private const val UNEXPECTED = "MVK_UNEXPECTED_TOKEN"
private const val INCOMPLETE = "MVK_INCOMPLETE_INPUT"
private const val EXPECTED = "MVK_EXPECTED_TOKEN_NOT_FOUND"

/** Message names have the owner's restricted alphabet; condition tokens also admit characters this grammar rejects. */
private fun messageTokens(text: String): List<Token> = buildList {
    var at = 0
    fun letter(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in "_ÄÜÖäüöß:"
    fun digit(c: Char) = c in '0'..'9'
    while (at < text.length) {
        val start = at
        val c = text[at]
        if (c in " \t\r\n\u000c") { at++; continue }
        if (letter(c) || digit(c)) {
            while (at < text.length && (letter(text[at]) || digit(text[at]))) at++
            val value = text.substring(start, at)
            add(Token(if (value.all(::digit)) TokenKind.NUMBER else TokenKind.NAME, value, start, at))
        } else if (c == '\'' || c == '"') {
            at++
            val value = StringBuilder()
            var closed = false
            while (at < text.length) {
                if (text[at] == c) {
                    at++
                    if (c == '"' && at < text.length && text[at] == c) { value.append(c); at++; continue }
                    closed = true
                    break
                }
                value.append(text[at++])
            }
            val valid = closed && (c == '"' || value.isNotEmpty() && letter(value[0]) && value.all { letter(it) || digit(it) })
            add(Token(if (!valid) TokenKind.ILLEGAL_CHARACTER else if (c == '\'') TokenKind.QUOTED_NAME else TokenKind.STRING,
                if (c == '"') text.substring(start, at) else value.toString(), start, at))
        } else {
            val pair = text.substring(at, minOf(at + 2, text.length))
            val symbol = if (pair in listOf("..", "->")) pair else c.toString()
            at += symbol.length
            add(Token(if (symbol in listOf("..", "->", "/", ".", "+", "-", "#", "(", ")"))
                TokenKind.PUNCTUATION else TokenKind.ILLEGAL_CHARACTER, symbol, start, at))
        }
    }
}
