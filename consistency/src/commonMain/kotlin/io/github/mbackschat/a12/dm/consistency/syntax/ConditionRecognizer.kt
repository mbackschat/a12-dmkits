package io.github.mbackschat.a12.dm.consistency.syntax

/** Recognition outcomes distinguish a required terminal from an unresolved choice (KF493). */
enum class SyntaxFailureKind { MATCH, DECISION, LEXICAL }

internal data class Recognition(
    val failure: ShapeFailure?,
    val calls: List<CallShape>,
    val comparisons: List<ComparisonShape>,
    val fieldReads: List<FieldValueRead>,
    val completedSteps: Map<Int, Int>,
    val unfinishedAt: Int?,
)

/** Model-independent recognition; only completed structures become scanner inputs. */
internal class ConditionRecognizer(private val source: String, language: ConditionLanguage) {
    private val tokens = tokenize(source)
    private val keywords = CONDITION_KEYWORDS.getValue(language)
    private val spellings = CONDITION_OPERATOR_KEYWORDS.getValue(language)
    private val identities = spellings.entries.associate { (id, spelling) -> spelling to id }
    private val calls = mutableListOf<CallShape>()
    private val comparisons = mutableListOf<ComparisonShape>()
    private val fieldReads = mutableListOf<FieldValueRead>()
    private val completedSteps = mutableMapOf<Int, Int>()
    private val openings = mutableListOf<Int>()
    private var cursor = 0
    private var filterActive = false

    fun recognize(entry: SyntaxEntry): Recognition = recognize {
        when (entry) {
            SyntaxEntry.CONDITION -> { preamble(); condition() }
            SyntaxEntry.OPERATION -> operation()
        }
    }

    internal fun recognizeEntity(): Recognition = recognize { entity() }

    private fun recognize(body: () -> Unit): Recognition {
        val failure = try {
            body()
            if (peek() != null) fail(SyntaxFailureKind.MATCH)
            null
        } catch (stop: RecognitionStop) {
            stop.failure
        }
        return Recognition(failure, calls, comparisons, fieldReads, completedSteps, openings.lastOrNull())
    }

    private fun peek(ahead: Int = 0): Token? {
        val token = tokens.getOrNull(cursor + ahead) ?: return null
        when (token.kind) {
            TokenKind.ILLEGAL_CHARACTER, TokenKind.UNTERMINATED_STRING, TokenKind.UNTERMINATED_QUOTED_NAME ->
                throw RecognitionStop(failure(SyntaxFailureKind.LEXICAL, token))
            else -> return token
        }
    }

    private fun failure(kind: SyntaxFailureKind, token: Token?): ShapeFailure {
        val start = token?.start ?: source.length
        val scope = spellings.getValue("FromScope")
        val end = if (token?.text == "@" && source.startsWith(scope, start)) start + scope.length else token?.end ?: start
        return ShapeFailure(start, token == null && kind == SyntaxFailureKind.MATCH,
            source.substring(start, end), kind, end)
    }

    private fun fail(kind: SyntaxFailureKind): Nothing = throw RecognitionStop(failure(kind, peek()))
    private fun take(): Token = peek()?.also { cursor++ } ?: fail(SyntaxFailureKind.MATCH)
    private fun isText(text: String): Boolean = peek()?.text == text
    private fun consume(text: String): Boolean = if (isText(text)) { take(); true } else false
    private fun require(text: String) {
        val start = peek()?.start
        if (!consume(text)) fail(SyntaxFailureKind.MATCH)
        when (text) {
            "(" -> openings += checkNotNull(start)
            ")", "]", "}" -> if (openings.isNotEmpty()) openings.removeAt(openings.lastIndex)
        }
    }
    private fun open(text: String): Boolean {
        val start = peek()?.start ?: return false
        if (!consume(text)) return false
        openings += start
        return true
    }
    private fun kind(token: Token?): KeywordKind? = token?.takeIf { it.kind == TokenKind.NAME }?.let { keywords[it.text] }
    private fun identity(token: Token?): String? = token?.takeIf { it.kind == TokenKind.NAME }?.let { identities[it.text] }
    private fun comments() { while (peek()?.kind == TokenKind.COMMENT) take() }
    private fun name(token: Token?): Boolean = token != null && when (token.kind) {
        TokenKind.QUOTED_NAME, TokenKind.NUMBER -> true
        TokenKind.NAME -> kind(token) == null
        else -> false
    }
    private fun requireName() { if (!name(peek())) fail(SyntaxFailureKind.MATCH); take() }
    private fun connective(): Boolean = kind(peek()) != null && peek()?.text in CONNECTIVES

    private fun preamble() {
        if (!consume("@")) return
        if (kind(peek()) == null || peek()?.text !in setOf("SuppressWarning", "UnterdrueckeWarnung"))
            fail(SyntaxFailureKind.MATCH)
        take(); require("(")
        requireName()
        while (consume(",")) requireName()
        require(")")
    }

    private fun condition() {
        comments()
        conditionPart()
        while (true) {
            comments()
            if (!connective()) return
            take()
            comments()
            conditionPart()
        }
    }

    private fun conditionPart() {
        if (open("(")) { condition(); require(")"); return }
        val token = peek() ?: fail(SyntaxFailureKind.DECISION)
        val id = identity(token)
        if (id == "CustomCondition") { take(); requireName(); return }
        if (kind(token) == KeywordKind.PREDICATE ||
            kind(token) != null && token.text in FORM_CONSTRUCTS ||
            kind(token) == KeywordKind.UNCATALOGED && peek(1)?.text == "(") {
            // The validity forms share their opening; no form has completed at a bare keyword (KF493).
            if (id in VALIDITY) {
                if (peek(1)?.text != "(") { take(); fail(SyntaxFailureKind.DECISION) }
                if (peek(1)?.text == "(" && peek(2) == null) {
                    take(); take(); fail(SyntaxFailureKind.DECISION)
                }
            }
            call()
            return
        }
        val left = cursor
        operation()
        val operatorAt = cursor
        val operator = peek() ?: fail(SyntaxFailureKind.DECISION)
        if (operator.text !in COMPARISONS && (kind(operator) != KeywordKind.OPERATOR || connective())) {
            fail(SyntaxFailureKind.DECISION)
        }
        take()
        val right = cursor
        operation()
        comparisons += ComparisonShape(tokens.subList(left, operatorAt), operator, tokens.subList(right, cursor))
    }

    private fun operation() {
        comments()
        primary()
        while (peek()?.text in ARITHMETIC) { take(); primary() }
        comments()
    }

    private fun primary() {
        val token = peek() ?: fail(SyntaxFailureKind.DECISION)
        when {
            open("{") -> { operation(); require("}") }
            open("[") -> {
                val from = cursor
                entity()
                val spec = tokens.subList(from, cursor)
                require("]")
                val end = tokens[cursor - 1].end
                // KF496: an immediate lexical defect prevents the completed-read action.
                peek()
                fieldReads += fieldValueRead(spec, token.start, end, filterActive, keywords)
            }
            consume("-") -> digits(decimal = true)
            token.kind == TokenKind.NUMBER -> digits(decimal = true)
            token.kind == TokenKind.STRING || kind(token) == KeywordKind.CONSTANT -> take()
            kind(token) == KeywordKind.FUNCTION ||
                kind(token) != null && token.text == "AnzahlAngegebenerVordrucke" -> call()
            else -> fail(SyntaxFailureKind.DECISION)
        }
    }

    private fun digits(decimal: Boolean = false) {
        val token = peek()
        if (token?.kind != TokenKind.NUMBER || token.text.any { it !in '0'..'9' }) fail(SyntaxFailureKind.MATCH)
        take()
        if (decimal && consume(".")) digits()
    }

    private fun call() {
        val operator = take()
        val id = identity(operator)
        require("(")
        val operands = mutableListOf<List<Token>>()
        var lastOperandStart = cursor
        if (id != "Time" || !isText(")")) {
            val signature = if (id in VALIDITY && kind(peek()) == KeywordKind.FUNCTION)
                listOf(Slot.DATE) else SIGNATURES[id]
            var position = 0
            var values = false
            var valueKind: TokenKind? = null
            fun value() {
                val token = peek()
                if (valueKind == TokenKind.STRING) {
                    if (token?.kind != TokenKind.STRING) fail(SyntaxFailureKind.MATCH)
                    take()
                } else {
                    valueKind = token?.kind
                    when {
                        token?.kind == TokenKind.STRING -> take()
                        id in MEMBERSHIP -> if (isText("[")) primary() else constant()
                        else -> entity()
                    }
                }
            }
            do {
                val from = cursor
                lastOperandStart = from
                val slot = signature?.getOrNull(position) ?: signature?.lastOrNull()?.takeIf { id in REPEATED }
                when {
                    id in MEMBERSHIP && position > 0 || values -> value()
                    id in MEMBERSHIP -> entity()
                    id in LIST_MEMBERSHIP -> {
                        entity()
                        if (kind(peek()) != null && peek()?.text in IN_WORDS) {
                            take(); values = true; value()
                        }
                    }
                    id == "NumberOfValueInFields" && position == 0 -> {
                        constant()
                        if (kind(peek()) == null || peek()?.text !in IN_WORDS) fail(SyntaxFailureKind.MATCH)
                        take(); entity()
                    }
                    else -> when (slot) {
                    Slot.OPERATION -> operation()
                    Slot.DIGITS -> digits()
                    Slot.DAY -> {
                        if (identity(peek()) !in setOf("FirstDay", "LastDay")) fail(SyntaxFailureKind.MATCH)
                        take()
                    }
                    Slot.ENTITY -> entity()
                    Slot.DATE -> date()
                    Slot.COMPONENT -> component()
                    null -> argument()
                    }
                }
                operands += tokens.subList(from, cursor)
                position++
                if (signature != null && position == signature.size && id !in REPEATED) break
            } while (consume(","))
            val required = when (id) { "Date" -> 2; in OPTIONAL_TAIL -> 1; else -> signature?.size ?: 0 }
            if (position < required) require(",")
            if (id in LIST_MEMBERSHIP && !values) fail(SyntaxFailureKind.MATCH)
        }
        if (id == "RepetitionNotUnique" && scopeFollows()) {
            take(); take(); entity()
            operands[operands.lastIndex] = tokens.subList(lastOperandStart, cursor)
        }
        require(")")
        calls += CallShape(operator.text, operator.start, tokens[cursor - 1].end, operands, source)
    }

    private fun constant() {
        when {
            consume("-") -> digits(decimal = true)
            peek()?.kind == TokenKind.NUMBER -> digits(decimal = true)
            peek()?.kind == TokenKind.STRING || kind(peek()) == KeywordKind.CONSTANT -> take()
            else -> fail(SyntaxFailureKind.DECISION)
        }
    }

    private fun date() {
        when {
            name(peek()) || peek()?.text in PATH_STARTS -> entity()
            peek()?.kind == TokenKind.STRING || identity(peek()) in setOf("Today", "Now", "BaseYear") -> take()
            identity(peek()) in DATE_FUNCTIONS -> call()
            else -> fail(SyntaxFailureKind.DECISION)
        }
    }

    private fun component() {
        when {
            name(peek()) || peek()?.text in PATH_STARTS -> entity()
            peek()?.kind == TokenKind.STRING -> take()
            kind(peek()) == KeywordKind.FUNCTION -> call()
            else -> fail(SyntaxFailureKind.DECISION)
        }
    }

    private fun argument() {
        val token = peek()
        if (name(token) || token?.text in PATH_STARTS || token?.text in RULE_CONTEXT_SPELLINGS) entity()
        else operation()
        if (kind(peek()) != null && peek()?.text in LIST_WORDS) { take(); argument() }
    }

    private fun entity() {
        val firstToken = peek() ?: fail(SyntaxFailureKind.DECISION)
        val start = firstToken.start
        val outer = consume("$")
        var walked = consume("/") && outer
        fun finishStep() {
            if (walked) completedSteps[start] = tokens[cursor - 1].end
        }
        fun step(first: Boolean) {
            if (consume("..")) {
                walked = true
                if (name(peek())) take()
                consume("*")
                finishStep()
                return
            }
            if (!name(peek()) && peek()?.text !in RULE_CONTEXT_SPELLINGS) {
                if (!walked && !first && isText("*")) {
                    throw RecognitionStop(failure(SyntaxFailureKind.DECISION, firstToken))
                }
                fail(if (walked) SyntaxFailureKind.MATCH else SyntaxFailureKind.DECISION)
            }
            val segment = take()
            if (first && outer && segment.text !in RULE_CONTEXT_SPELLINGS) walked = true
            while (consume(".")) {
                if (consume("*")) {
                    if (isText("/")) walked = true
                    break
                }
                if (!name(peek()) || peek()?.kind == TokenKind.NUMBER)
                    fail(if (walked) SyntaxFailureKind.MATCH else SyntaxFailureKind.DECISION)
                take()
            }
            if (consume("*")) walked = true
            finishStep()
        }
        step(first = true)
        while (consume("/")) step(first = false)
        if (!walked && isText("@") && !scopeFollows()) {
            throw RecognitionStop(failure(SyntaxFailureKind.DECISION, firstToken))
        }
        completedSteps[start] = tokens[cursor - 1].end
        while (true) {
            when {
                identity(peek()) == "SemanticIndex" -> {
                    take()
                    if (peek()?.kind == TokenKind.STRING) take() else entity()
                }
                identity(peek()) == "Having" -> {
                    take(); filterActive = true; condition()
                    // KF486: finishing a nested filter clears the flag instead of restoring its parent.
                    filterActive = false
                }
                else -> break
            }
        }
        if (consume("->")) {
            if (!name(peek())) fail(if (walked) SyntaxFailureKind.MATCH else SyntaxFailureKind.DECISION)
            take()
        }
    }

    private fun scopeFollows(): Boolean = isText("@") && peek(1)?.text == spellings["FromScope"]?.removePrefix("@")

    private class RecognitionStop(val failure: ShapeFailure) : RuntimeException()
    private enum class Slot { ENTITY, OPERATION, DIGITS, DAY, DATE, COMPONENT }

    private companion object {
        val CONNECTIVES = setOf("And", "AND", "and", "Or", "OR", "or", "Und", "UND", "und", "Oder", "ODER", "oder")
        val COMPARISONS = setOf("==", "!=", "<", ">", "<=", ">=")
        val ARITHMETIC = setOf("+", "-", "*", "/", "^")
        val PATH_STARTS = setOf("/", "..", "$")
        val LIST_WORDS = setOf("In", "IN", "in", "To", "TO", "to", "Zu", "ZU", "zu")
        val VALIDITY = setOf("Valid", "ValidType", "Invalid", "InvalidType")
        val IN_WORDS = setOf("In", "IN", "in")
        val MEMBERSHIP = setOf("FieldValueIncludedInValueList", "FieldValueNotIncludedInValueList")
        val LIST_MEMBERSHIP = setOf("AtLeastOneFieldValueIncludedInValueList", "NoFieldValueIncludedInValueList",
            "NotAllFieldValuesIncludedInValueList", "AtLeastOneDateRangeOverlaps")
        val ENTITY_LISTS = setOf("FieldFilled", "FieldNotFilled", "AllFieldsFilled", "NoFieldFilled",
            "AtLeastOneFieldFilled", "NotAllFieldsFilled", "FieldsNotCollectivelyFilled", "NotExactlyOneFieldFilled",
            "MoreThanOneFieldFilled", "GroupFilled", "GroupNotFilled", "AllGroupsFilled", "NoGroupFilled",
            "AtLeastOneGroupFilled", "NotAllGroupsFilled", "GroupsNotCollectivelyFilled", "FieldValuesNotUnique",
            "Sum", "MaxValue", "MinValue", "FirstFilledValue", "SumOfProducts", "NumberOfFilledFields",
            "NumberOfFilledGroups", "NumberOfDifferentValues", "NumberOfValueInFields", "DateRangesOverlap")
        val REPEATED = setOf("Max", "Min") + ENTITY_LISTS
        val OPTIONAL_TAIL = setOf("RoundDownValue", "RoundUpValue", "RoundAccountingValue", "RoundDown", "RoundUp",
            "RoundAccounting", "Time", "Date")
        val DATE_FUNCTIONS = setOf("Date", "Time", "DateTime", "DateRange", "AddYears", "AddMonths", "AddDays",
            "AddHours", "AddMinutes", "AddSeconds", "StartOfDateRange", "EndOfDateRange", "DateFromDateTime",
            "TimeFromDateTime", "ValueAsDate", "YearFromDate", "MonthFromDate", "DayFromDate", "QuarterFromDate",
            "HoursFromTime", "MinutesFromTime", "SecondsFromTime")
        val SIGNATURES = buildMap<String, List<Slot>> {
            for (id in ENTITY_LISTS) put(id, listOf(Slot.ENTITY))
            for (id in listOf("Length", "AbsValue", "FieldValueAsNumber", "FieldValueAsString", "CurrentRepetition",
                    "StartOfDateRange", "EndOfDateRange")) put(id, listOf(Slot.ENTITY))
            for (id in listOf("DifferenceInDays", "DifferenceInMonths", "DifferenceInYears", "DifferenceInHours",
                    "DifferenceInMinutes", "DifferenceInSeconds", "DateTime", "DateRange"))
                put(id, listOf(Slot.DATE, Slot.DATE))
            for (id in listOf("YearFromDate", "MonthFromDate", "DayFromDate", "QuarterFromDate", "HoursFromTime",
                    "MinutesFromTime", "SecondsFromTime", "DateFromDateTime", "TimeFromDateTime"))
                put(id, listOf(Slot.DATE))
            put("Time", List(3) { Slot.COMPONENT })
            put("Date", List(4) { Slot.COMPONENT })
            for (id in listOf("RoundDownValue", "RoundUpValue", "RoundAccountingValue"))
                put(id, listOf(Slot.ENTITY, Slot.DIGITS))
            for (id in listOf("RoundDown", "RoundUp", "RoundAccounting"))
                put(id, listOf(Slot.OPERATION, Slot.DIGITS))
            for (id in listOf("RangeAsNumber", "RangeAsString"))
                put(id, listOf(Slot.ENTITY, Slot.DIGITS, Slot.DIGITS))
            for (id in listOf("Abs", "Max", "Min")) put(id, listOf(Slot.OPERATION))
            put("ValueAsDate", listOf(Slot.ENTITY, Slot.DAY))
            for (id in listOf("AddYears", "AddMonths", "AddDays", "AddHours", "AddMinutes", "AddSeconds"))
                put(id, listOf(Slot.DATE, Slot.OPERATION))
        }
    }
}
