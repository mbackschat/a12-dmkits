package io.github.mbackschat.a12.dm.consistency.syntax

import io.github.mbackschat.a12.dm.consistency.asciiLowercase

/** A12's `datumExtractOpName`, by catalog id: the operations a `Date`/`Time` component may call. */
internal val DATE_EXTRACT_OPERATIONS = setOf(
    OperatorId.YEAR_FROM_DATE, OperatorId.QUARTER_FROM_DATE, OperatorId.MONTH_FROM_DATE, OperatorId.DAY_FROM_DATE,
    OperatorId.HOURS_FROM_TIME, OperatorId.MINUTES_FROM_TIME, OperatorId.SECONDS_FROM_TIME, OperatorId.DATE_FROM_DATE_TIME, OperatorId.TIME_FROM_DATE_TIME,
)

/** Model-independent recognition; only completed structures become scanner inputs. */
internal class ConditionRecognizer(private val source: String, language: ConditionLanguage) {
    private val tokens = tokenize(source)
    private val keywords = CONDITION_KEYWORDS.getValue(language)
    private val spellings = CONDITION_OPERATOR_KEYWORDS.getValue(language)
    private val identities = CONDITION_OPERATOR_IDENTITIES.getValue(language)
    private val calls = mutableListOf<CallShape>()
    private val comparisons = mutableListOf<ComparisonShape>()
    private val operations = mutableListOf<OperationShape>()
    private val fieldReads = mutableListOf<FieldValueRead>()
    private val completedSteps = mutableMapOf<Int, Int>()
    private val sites = mutableListOf<SyntaxSite>()
    private val entities = mutableListOf<RecognizedEntity>()
    private val openings = mutableListOf<Int>()
    private var cursor = 0
    private var filterActive = false
    private var tree: ConditionNode? = null

    fun recognize(entry: SyntaxEntry): Recognition = recognize {
        when (entry) {
            SyntaxEntry.CONDITION -> { preamble(); tree = condition() }
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
        failure?.let { sites += SyntaxSite.Failure(it) }
        return Recognition(failure, calls, comparisons, operations, fieldReads, completedSteps, openings.lastOrNull(), sites, entities,
            tree.takeIf { failure == null })
    }

    private fun completed(kind: SyntaxSite.Kind, start: Int, end: Int = tokens[cursor - 1].end, record: Boolean = true) {
        // A demanded lexical failure precedes the action whose production has just been consumed (KF311).
        peek()
        if (record) sites += SyntaxSite.Completion(kind, start, end)
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
    private fun identity(token: Token?): OperatorId? = token?.takeIf { it.kind == TokenKind.NAME }?.let { identities[it.text] }
    private fun comments() { while (peek()?.kind == TokenKind.COMMENT) take() }
    private fun name(token: Token?): Boolean = token != null && when (token.kind) {
        TokenKind.QUOTED_NAME, TokenKind.NUMBER -> true
        TokenKind.NAME -> kind(token) == null
        else -> false
    }
    private fun requireName() { if (!name(peek())) fail(SyntaxFailureKind.MATCH); take() }
    private fun connective(): Boolean = kind(peek()) != null && peek()?.text in CONNECTIVES

    private fun preamble() {
        val start = peek()?.start ?: return
        if (!consume("@")) return
        if (kind(peek()) == null || peek()?.text !in setOf("SuppressWarning", "UnterdrueckeWarnung"))
            fail(SyntaxFailureKind.MATCH)
        take(); require("(")
        requireName()
        while (consume(",")) requireName()
        require(")")
        completed(SyntaxSite.Kind.PREAMBLE, start)
    }

    private fun condition(): ConditionNode {
        comments()
        var first: Token? = null
        val parts = mutableListOf(conditionPart())
        val connectives = mutableListOf<ConnectiveKind>()
        while (true) {
            comments()
            if (!connective()) return parts.singleOrNull() ?: ConditionNode.Junction(parts, connectives)
            val current = take()
            comments()
            parts += conditionPart()
            connectives += when (current.text.asciiLowercase()) {
                "and", "und" -> ConnectiveKind.AND
                else -> ConnectiveKind.OR
            }
            val previous = first
            if (previous == null) first = current else {
                peek()
                sites += SyntaxSite.Connective(previous, current, tokens[cursor - 1].end)
            }
        }
    }

    private fun conditionPart(): ConditionNode {
        if (open("(")) { val node = condition(); require(")"); return node }
        val token = peek() ?: fail(SyntaxFailureKind.DECISION)
        val id = identity(token)
        if (id == OperatorId.CUSTOM_CONDITION) { take(); requireName(); return ConditionNode.Custom }
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
            return ConditionNode.Call(call())
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
        val shape = ComparisonShape(tokens.subList(left, operatorAt), operator, tokens.subList(right, cursor))
        comparisons += shape
        peek()
        sites += SyntaxSite.Comparison(shape)
        return ConditionNode.Comparison(shape)
    }

    private fun operation(): OperationShape {
        comments()
        val result = arithmetic(0)
        comments()
        return result
    }

    private fun arithmetic(minimum: Int): OperationShape {
        var left = primary()
        while (true) {
            val precedence = when (peek()?.text) {
                "+", "-" -> 1
                "*", "/" -> 2
                "^" -> 3
                else -> return left
            }
            if (precedence < minimum) return left
            val operator = take()
            val right = arithmetic(precedence + 1)
            left = complete(OperationShape.Arithmetic(left, operator, right))
        }
    }

    private fun complete(shape: OperationShape): OperationShape {
        // KF497: demanding the next token precedes the completed operation's action.
        peek()
        operations += shape
        sites += SyntaxSite.Operation(shape)
        return shape
    }

    private fun primary(): OperationShape {
        val token = peek() ?: fail(SyntaxFailureKind.DECISION)
        return when {
            open("{") -> {
                val child = operation()
                require("}")
                complete(OperationShape.Group(token.start, tokens[cursor - 1].end, child))
            }
            open("[") -> {
                val from = cursor
                entity()
                val spec = tokens.subList(from, cursor)
                require("]")
                val end = tokens[cursor - 1].end
                // KF496: an immediate lexical defect prevents the completed-read action.
                peek()
                // The bracket's own entity is the last one recorded, a key recording its own first.
                val own = entities.lastOrNull()
                val keyedByLiteral = own != null && own.indexed && !own.filtered &&
                    spec.last().kind == TokenKind.STRING
                val read = fieldValueRead(spec, token.start, end, filterActive, keywords, keyedByLiteral)
                fieldReads += read
                sites += SyntaxSite.FieldRead(read)
                complete(OperationShape.Read(read))
            }
            consume("-") || token.kind == TokenKind.NUMBER -> {
                digits(decimal = true); complete(OperationShape.Literal(token.start, tokens[cursor - 1].end))
            }
            token.kind == TokenKind.STRING || kind(token) == KeywordKind.CONSTANT -> {
                constantToken(); complete(OperationShape.Literal(token.start, token.end))
            }
            kind(token) == KeywordKind.FUNCTION ||
                kind(token) != null && token.text == "AnzahlAngegebenerVordrucke" -> {
                val call = call()
                complete(OperationShape.Call(call, call.operands.map { operations.spanning(it) }))
            }
            else -> fail(SyntaxFailureKind.DECISION)
        }
    }

    private fun constantToken() {
        val token = take()
        completed(SyntaxSite.Kind.CONSTANT, token.start, token.end)
    }

    private fun digits(decimal: Boolean = false) {
        val token = peek()
        if (token?.kind != TokenKind.NUMBER || token.text.any { it !in '0'..'9' }) fail(SyntaxFailureKind.MATCH)
        take()
        if (decimal && consume(".")) digits()
    }

    private fun call(): CallShape {
        val operator = take()
        val id = identity(operator)
        if (operator.text in FORM_CONSTRUCTS && operator.text != "AnzahlAngegebenerVordrucke")
            completed(SyntaxSite.Kind.FORM_NAME, operator.start, operator.end)
        require("(")
        val operands = mutableListOf<List<Token>>()
        var lastOperandStart = cursor
        if (id != OperatorId.TIME || !isText(")")) {
            // A12's grammar has two validity conditions and nothing between them: a `Date` construction alone, or
            // a field and a type name (`ValidityConditionOperandLawsTest.whatTheGrammarAdmitsAsAValidityCondition`).
            val signature = when {
                id !in VALIDITY -> SIGNATURES[id]
                identity(peek()) == OperatorId.DATE -> listOf(Slot.DATE)
                else -> listOf(Slot.ENTITY, Slot.STRING)
            }
            var position = 0
            var values = false
            var listKind: ValueKind? = null
            // A value list is one kind throughout: the grammar offers a string list, a number list, a list of
            // field reads or an entity list as separate alternatives (`wertListenSpezifier`,
            // `wertListenSpezifier2`), so a member of another kind is a parse error ([KERNEL-FINDINGS KF613]).
            fun value() {
                val kind = when {
                    peek()?.kind == TokenKind.STRING -> ValueKind.TEXT
                    id !in MEMBERSHIP -> ValueKind.ENTITY
                    isText("[") -> ValueKind.READ
                    else -> ValueKind.NUMBER
                }
                if (listKind != null && kind != listKind) fail(SyntaxFailureKind.MATCH)
                listKind = kind
                when (kind) {
                    ValueKind.TEXT -> take()
                    ValueKind.ENTITY -> entity()
                    ValueKind.READ -> primary()
                    ValueKind.NUMBER -> number()
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
                    id == OperatorId.NUMBER_OF_VALUE_IN_FIELDS && position == 0 -> {
                        constant()
                        if (kind(peek()) == null || peek()?.text !in IN_WORDS) fail(SyntaxFailureKind.MATCH)
                        take(); entity()
                    }
                    else -> when (slot) {
                        Slot.OPERATION -> operation()
                        Slot.DIGITS -> digits()
                        Slot.DAY -> {
                            if (identity(peek()) !in DAY_MARKERS) fail(SyntaxFailureKind.MATCH)
                            take()
                        }
                        Slot.ENTITY -> entity()
                        Slot.RANGE -> if (identity(peek()) == OperatorId.BASE_YEAR) constantToken() else entity()
                        Slot.DATE, Slot.DATE_OR_VALUE_AS_DATE -> {
                            date(valueAsDate = slot == Slot.DATE_OR_VALUE_AS_DATE)
                            peek()
                            sites += SyntaxSite.DateArgument(operator.text, tokens.subList(from, cursor), filterActive)
                        }
                        Slot.COMPONENT -> component()
                        Slot.STRING -> { if (peek()?.kind != TokenKind.STRING) fail(SyntaxFailureKind.MATCH); take() }
                        null -> argument(reference = operator.text !in FORM_CONSTRUCTS)
                    }
                }
                operands += tokens.subList(from, cursor)
                position++
                if (signature != null && position == signature.size && id !in REPEATED) break
            } while (consume(","))
            val required = when (id) { OperatorId.DATE -> 2; in OPTIONAL_TAIL -> 1; else -> signature?.size ?: 0 }
            if (position < required) require(",")
            if (id in LIST_MEMBERSHIP && !values) fail(SyntaxFailureKind.MATCH)
        }
        if (id == OperatorId.REPETITION_NOT_UNIQUE && scopeFollows()) {
            take(); take(); entity()
            operands[operands.lastIndex] = tokens.subList(lastOperandStart, cursor)
        }
        require(")")
        return CallShape(operator.text, operator.start, tokens[cursor - 1].end, operands, source).also {
            calls += it
            completed(SyntaxSite.Kind.CALL, it.at, it.end)
        }
    }

    /**
     * A number in a membership value list, whose grammar admits strings, numbers and field reads only
     * (`wertListenSpezifier2`), so a constant keyword such as `True` is a parse error there ([KERNEL-FINDINGS KF612]).
     */
    private fun number() {
        when {
            consume("-") -> digits(decimal = true)
            peek()?.kind == TokenKind.NUMBER -> digits(decimal = true)
            else -> fail(SyntaxFailureKind.DECISION)
        }
    }

    private fun constant() {
        when {
            consume("-") -> digits(decimal = true)
            peek()?.kind == TokenKind.NUMBER -> digits(decimal = true)
            peek()?.kind == TokenKind.STRING || kind(peek()) == KeywordKind.CONSTANT -> constantToken()
            else -> fail(SyntaxFailureKind.DECISION)
        }
    }

    /**
     * A12's `datumAngabe`, or with [valueAsDate] its `datumAngabe2`, the date slot that also takes `ValueAsDate`:
     * both arguments of a difference, the date of a shift, and `DateTime`'s date. In an extractor or in
     * `DateTime`'s time it is a parse error
     * ([KERNEL-FINDINGS KF643], `adapter.laws.CheckerDateTimeConstructionAgreementTest`).
     */
    private fun date(valueAsDate: Boolean) {
        val id = identity(peek())
        when {
            name(peek()) || peek()?.text in PATH_STARTS -> entity()
            peek()?.kind == TokenKind.STRING || id in DATE_CONSTANTS -> constantToken()
            id in DATE_FUNCTIONS && (id != OperatorId.VALUE_AS_DATE || valueAsDate) -> call()
            else -> fail(SyntaxFailureKind.DECISION)
        }
    }

    /**
     * A `Date`/`Time` component, which A12's `datumParam` admits only as an entity, a quoted constant or one of
     * the nine `datumExtractOp` calls; any other call is a parse error there
     * (`adapter.laws.CheckerConstructorComponentAgreementTest`).
     */
    private fun component() {
        when {
            name(peek()) || peek()?.text in PATH_STARTS -> entity()
            peek()?.kind == TokenKind.STRING -> take()
            kind(peek()) == KeywordKind.FUNCTION && identity(peek()) in DATE_EXTRACT_OPERATIONS -> call()
            else -> fail(SyntaxFailureKind.DECISION)
        }
    }

    private fun argument(reference: Boolean = true) {
        val token = peek()
        if (name(token) || token?.text in PATH_STARTS || token?.text in RULE_CONTEXT_SPELLINGS) entity(reference)
        else operation()
        if (kind(peek()) != null && peek()?.text in LIST_WORDS) { take(); argument(reference) }
    }

    private fun entity(reference: Boolean = true, indexKey: Boolean = false) {
        val pathStart = cursor
        val firstToken = peek() ?: fail(SyntaxFailureKind.DECISION)
        val start = firstToken.start
        val outer = consume("$")
        var walked = consume("/") && outer
        fun finishStep() {
            if (walked) {
                completedSteps[start] = tokens[cursor - 1].end
                completed(SyntaxSite.Kind.PATH_STEP, start, record = reference)
            }
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
        if (!walked) completed(SyntaxSite.Kind.PATH_STEP, start, record = reference)
        val path = tokens.subList(pathStart, cursor)
        // `entitySpec` takes each modifier at most once and in this order: `-> Category`, `For key`, `Having
        // filter` (GrammarOnly_ConditionParser.g4). A second `For` or a later `->` is therefore never this
        // entity's: it belongs to an entity key, which takes it in its own production, or is unexpected
        // (KERNEL-FINDINGS KF568).
        val category = if (consume("->")) {
            if (!name(peek())) fail(if (walked) SyntaxFailureKind.MATCH else SyntaxFailureKind.DECISION)
            take()
        } else null
        var indexed = false
        var filtered = false
        if (identity(peek()) == OperatorId.SEMANTIC_INDEX) {
            val indexStart = peek()!!.start
            take()
            if (peek()?.kind == TokenKind.STRING) take() else entity(indexKey = true)
            completed(SyntaxSite.Kind.INDEX, indexStart)
            indexed = true
        }
        if (identity(peek()) == OperatorId.HAVING) {
            val keyword = take()
            peek()
            val entry = SyntaxSite.FilterEntry(start, keyword.end, path, indexed, indexKey)
            sites += entry
            filterActive = true
            condition()
            peek()
            sites += SyntaxSite.FilterExit(entry, tokens[cursor - 1].end)
            filtered = true
            // KF486: finishing a nested filter clears the flag instead of restoring its parent.
            filterActive = false
        }
        completed(SyntaxSite.Kind.ENTITY, start, record = reference)
        if (reference) entities += RecognizedEntity(sites.last() as SyntaxSite.Completion,
            path, indexKey, filterActive, indexed, filtered, category)
    }

    private fun scopeFollows(): Boolean = isText("@") && peek(1)?.text == spellings["FromScope"]?.removePrefix("@")

    private class RecognitionStop(val failure: ShapeFailure) : RuntimeException()
    /** [RANGE] is a range endpoint's source: an entity or the base-year terminal (`datumBereichAngabe`). */
    private enum class Slot { ENTITY, RANGE, OPERATION, DIGITS, DAY, DATE, DATE_OR_VALUE_AS_DATE, COMPONENT, STRING }

    /** The one kind a value list holds. */
    private enum class ValueKind { TEXT, NUMBER, READ, ENTITY }

    private companion object {
        val CONNECTIVES = setOf("And", "AND", "and", "Or", "OR", "or", "Und", "UND", "und", "Oder", "ODER", "oder")
        val COMPARISONS = setOf("==", "!=", "<", ">", "<=", ">=")
        val PATH_STARTS = setOf("/", "..", "$")
        val LIST_WORDS = setOf("In", "IN", "in", "To", "TO", "to", "Zu", "ZU", "zu")
        val VALIDITY = setOf(OperatorId.VALID, OperatorId.VALID_TYPE, OperatorId.INVALID, OperatorId.INVALID_TYPE)
        val IN_WORDS = setOf("In", "IN", "in")
        val MEMBERSHIP = setOf(OperatorId.FIELD_VALUE_INCLUDED_IN_VALUE_LIST, OperatorId.FIELD_VALUE_NOT_INCLUDED_IN_VALUE_LIST)
        val LIST_MEMBERSHIP = setOf(OperatorId.AT_LEAST_ONE_FIELD_VALUE_INCLUDED_IN_VALUE_LIST, OperatorId.NO_FIELD_VALUE_INCLUDED_IN_VALUE_LIST,
            OperatorId.NOT_ALL_FIELD_VALUES_INCLUDED_IN_VALUE_LIST, OperatorId.AT_LEAST_ONE_DATE_RANGE_OVERLAPS)
        val ENTITY_LISTS = setOf(OperatorId.FIELD_FILLED, OperatorId.FIELD_NOT_FILLED, OperatorId.ALL_FIELDS_FILLED, OperatorId.NO_FIELD_FILLED,
            OperatorId.AT_LEAST_ONE_FIELD_FILLED, OperatorId.NOT_ALL_FIELDS_FILLED, OperatorId.FIELDS_NOT_COLLECTIVELY_FILLED, OperatorId.NOT_EXACTLY_ONE_FIELD_FILLED,
            OperatorId.MORE_THAN_ONE_FIELD_FILLED, OperatorId.GROUP_FILLED, OperatorId.GROUP_NOT_FILLED, OperatorId.ALL_GROUPS_FILLED, OperatorId.NO_GROUP_FILLED,
            OperatorId.AT_LEAST_ONE_GROUP_FILLED, OperatorId.NOT_ALL_GROUPS_FILLED, OperatorId.GROUPS_NOT_COLLECTIVELY_FILLED, OperatorId.FIELD_VALUES_NOT_UNIQUE,
            OperatorId.SUM, OperatorId.MAX_VALUE, OperatorId.MIN_VALUE, OperatorId.FIRST_FILLED_VALUE, OperatorId.SUM_OF_PRODUCTS, OperatorId.NUMBER_OF_FILLED_FIELDS,
            OperatorId.NUMBER_OF_FILLED_GROUPS, OperatorId.NUMBER_OF_DIFFERENT_VALUES, OperatorId.NUMBER_OF_VALUE_IN_FIELDS, OperatorId.DATE_RANGES_OVERLAP)
        val REPEATED = setOf(OperatorId.MAX, OperatorId.MIN) + ENTITY_LISTS
        val OPTIONAL_TAIL = setOf(OperatorId.ROUND_DOWN_VALUE, OperatorId.ROUND_UP_VALUE, OperatorId.ROUND_ACCOUNTING_VALUE, OperatorId.ROUND_DOWN, OperatorId.ROUND_UP,
            OperatorId.ROUND_ACCOUNTING, OperatorId.TIME, OperatorId.DATE)
        val DAY_MARKERS = setOf(OperatorId.FIRST_DAY, OperatorId.LAST_DAY)
        val DATE_CONSTANTS = setOf(OperatorId.TODAY, OperatorId.NOW, OperatorId.BASE_YEAR)
        val DATE_FUNCTIONS = setOf(OperatorId.DATE, OperatorId.TIME, OperatorId.DATE_TIME, OperatorId.DATE_RANGE, OperatorId.ADD_YEARS, OperatorId.ADD_MONTHS, OperatorId.ADD_DAYS,
            OperatorId.ADD_HOURS, OperatorId.ADD_MINUTES, OperatorId.ADD_SECONDS, OperatorId.START_OF_DATE_RANGE, OperatorId.END_OF_DATE_RANGE,
            OperatorId.VALUE_AS_DATE) + DATE_EXTRACT_OPERATIONS
        val SIGNATURES = buildMap<OperatorId, List<Slot>> {
            for (id in ENTITY_LISTS) put(id, listOf(Slot.ENTITY))
            for (id in listOf(OperatorId.LENGTH, OperatorId.ABS_VALUE, OperatorId.FIELD_VALUE_AS_NUMBER, OperatorId.FIELD_VALUE_AS_STRING, OperatorId.CURRENT_REPETITION))
                put(id, listOf(Slot.ENTITY))
            for (id in listOf(OperatorId.START_OF_DATE_RANGE, OperatorId.END_OF_DATE_RANGE)) put(id, listOf(Slot.RANGE))
            // The range construct takes two field specs, not the date slot (`datumBereichKonstrukt`, KF613).
            put(OperatorId.DATE_RANGE, listOf(Slot.ENTITY, Slot.ENTITY))
            for (id in listOf(OperatorId.DIFFERENCE_IN_DAYS, OperatorId.DIFFERENCE_IN_MONTHS, OperatorId.DIFFERENCE_IN_YEARS, OperatorId.DIFFERENCE_IN_HOURS,
                    OperatorId.DIFFERENCE_IN_MINUTES, OperatorId.DIFFERENCE_IN_SECONDS))
                put(id, listOf(Slot.DATE_OR_VALUE_AS_DATE, Slot.DATE_OR_VALUE_AS_DATE))
            put(OperatorId.DATE_TIME, listOf(Slot.DATE_OR_VALUE_AS_DATE, Slot.DATE))
            for (id in DATE_EXTRACT_OPERATIONS) put(id, listOf(Slot.DATE))
            put(OperatorId.TIME, List(3) { Slot.COMPONENT })
            put(OperatorId.DATE, List(4) { Slot.COMPONENT })
            for (id in listOf(OperatorId.ROUND_DOWN_VALUE, OperatorId.ROUND_UP_VALUE, OperatorId.ROUND_ACCOUNTING_VALUE))
                put(id, listOf(Slot.ENTITY, Slot.DIGITS))
            for (id in listOf(OperatorId.ROUND_DOWN, OperatorId.ROUND_UP, OperatorId.ROUND_ACCOUNTING))
                put(id, listOf(Slot.OPERATION, Slot.DIGITS))
            for (id in listOf(OperatorId.RANGE_AS_NUMBER, OperatorId.RANGE_AS_STRING))
                put(id, listOf(Slot.ENTITY, Slot.DIGITS, Slot.DIGITS))
            for (id in listOf(OperatorId.ABS, OperatorId.MAX, OperatorId.MIN)) put(id, listOf(Slot.OPERATION))
            put(OperatorId.VALUE_AS_DATE, listOf(Slot.ENTITY, Slot.DAY))
            for (id in listOf(OperatorId.ADD_YEARS, OperatorId.ADD_MONTHS, OperatorId.ADD_DAYS, OperatorId.ADD_HOURS, OperatorId.ADD_MINUTES, OperatorId.ADD_SECONDS))
                put(id, listOf(Slot.DATE_OR_VALUE_AS_DATE, Slot.OPERATION))
        }
    }
}
