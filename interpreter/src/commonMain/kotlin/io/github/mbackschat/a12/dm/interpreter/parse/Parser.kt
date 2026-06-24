package io.github.mbackschat.a12.dm.interpreter.parse

import io.github.mbackschat.a12.dm.interpreter.UnsupportedConstructException
import io.github.mbackschat.a12.dm.interpreter.dec
import io.github.mbackschat.a12.dm.interpreter.ast.ArithOp
import io.github.mbackschat.a12.dm.interpreter.ast.CmpOp
import io.github.mbackschat.a12.dm.interpreter.ast.Cond
import io.github.mbackschat.a12.dm.interpreter.ast.Expr
import io.github.mbackschat.a12.dm.interpreter.ast.ValueListQuantOp

/**
 * A recursive-descent parser turning condition TEXT into the interpreter [Cond] AST. It lives in
 * commonMain, so it parses on JVM and JS alike.
 *
 * This is NOT yet the full condition grammar — the production parser choice (kernel parse-tree-api vs
 * ANTLR-from-grammar) stays open (proposal §11) — but the text→AST pipeline closes end to end. Paths in
 * condition text are
 * RELATIVE to the rule's group; [base] resolves them to absolute (base "/Order": `Quantity` →
 * "/Order/Quantity", `Items* / Count` → Star("/Order/Items/Count")).
 *
 * Grammar (low→high precedence): `or := and ("Or" and)*` · `and := primary ("And" primary)*` ·
 * `primary := "(" or ")" | expr (cmpOp expr)? | predicateCall`.
 */
fun parseCondition(text: String, base: String): Cond = ConditionParser(text, base).parse()

/** Parse a value expression (a computation's right-hand side), paths relative to [base]. */
fun parseExpression(text: String, base: String): Expr = ConditionParser(text, base).parseTopExpr()

private class ConditionParser(private val src: String, private val base: String) {

    private var pos = 0

    fun parse(): Cond {
        val c = parseOr()
        skipWs()
        require(pos >= src.length) { "parser: trailing input at $pos in \"$src\"" }
        return c
    }

    fun parseTopExpr(): Expr {
        val e = parseExpr()
        skipWs()
        require(pos >= src.length) { "parser: trailing input at $pos in \"$src\"" }
        return e
    }

    private fun parseOr(): Cond {
        var left = parseAnd()
        while (matchKeyword("Or")) left = Cond.Or(left, parseAnd())
        return left
    }

    private fun parseAnd(): Cond {
        var left = parsePrimary()
        while (matchKeyword("And")) left = Cond.And(left, parsePrimary())
        return left
    }

    private fun parsePrimary(): Cond {
        skipWs()
        if (peek() == '(') {
            next()
            val c = parseOr()
            skipWs(); expect(')')
            return c
        }
        matchValueListQuant()?.let { return it }
        val left = parseExpr()
        val op = tryCmpOp()
        if (op != null) return Cond.Cmp(op, left, parseExpr())
        val pat = matchOneOf(PATTERN_OPS)
        if (pat != null) return Cond.Pattern(left, parseExpr(), expectMatch = pat == "PatternMatched")
        val tol = matchOneOf(TOLERANCE_OPS.keys)
        if (tol != null) return Cond.Tolerance(left, parseExpr(), TOLERANCE_OPS.getValue(tol))
        if (left !is Expr.Func || left.name !in PREDICATES) {
            // Not a comparison, a pattern test, or a known boolean predicate — e.g. `CustomCondition X`.
            throw UnsupportedConstructException("not a supported condition shape: $left")
        }
        return Cond.Pred(left.name, left.args)
    }

    // Arithmetic precedence (low→high): additive (+ -) over multiplicative (* /) over power (^) over the atom.
    // Sub-expressions group with CURLY braces { } (kernel: not round/square brackets); `^` is NOT nestable
    // without braces (the kernel rejects `a ^ b ^ c`, MVK_TOO_MANY_POW_FOR_CALC).
    private fun parseExpr(): Expr = parseAdditive()

    private fun parseAdditive(): Expr {
        var left = parseMultiplicative()
        while (true) {
            skipWs()
            val op = when (peek()) { '+' -> ArithOp.ADD; '-' -> ArithOp.SUB; else -> break }
            next()
            left = Expr.Arith(op, left, parseMultiplicative())
        }
        return left
    }

    private fun parseMultiplicative(): Expr {
        var left = parsePower()
        while (true) {
            skipWs()
            val op = when (peek()) { '*' -> ArithOp.MUL; '/' -> ArithOp.DIV; else -> break }
            next()
            left = Expr.Arith(op, left, parsePower())
        }
        return left
    }

    /** A single optional `^` after an atom (power binds tightest; not nestable without braces — see [parseExpr]). */
    private fun parsePower(): Expr {
        val base = parseAtom()
        skipWs()
        if (peek() != '^') return base
        next()
        return Expr.Arith(ArithOp.POW, base, parseAtom())
    }

    private fun parseAtom(): Expr {
        skipWs()
        return when (val c = peek()) {
            '{' -> { next(); val e = parseExpr(); skipWs(); expect('}'); e }
            '[' -> {
                next()
                val absolute = peek() == '/'      // an absolute reference [/Group/Field], used as-is (no base)
                if (absolute) next()
                val p = pathFrom(readIdent(), absolute)
                skipWs()
                // `[field For "value"]` — a SemanticIndex lookup by the group's index value.
                if (matchKeyword("For")) {
                    skipWs()
                    val idx = readString().v
                    skipWs(); expect(']')
                    Expr.SemanticIndex((p as Expr.Field).path, idx)
                } else {
                    expect(']'); p
                }
            }
            '"' -> readString()
            else -> when {
                c == '-' || c.isDigit() -> readNumber()
                else -> {
                    val name = readIdent()
                    require(name.isNotEmpty()) { "parser: unexpected '$c' at $pos in \"$src\"" }
                    when (name) {
                        "True" -> Expr.BoolLit(true)
                        "False" -> Expr.BoolLit(false)
                        in CONSTANTS -> Expr.Const(name)
                        else -> {
                            skipWs()
                            if (peek() == '(') Expr.Func(name, readArgs()) else pathFrom(name)
                        }
                    }
                }
            }
        }
    }

    /**
     * Build a Field/Star from a first segment, reading optional `*` and further `/segment`s. A relative
     * path resolves against the rule's group [base]; an [absolute] path (a leading `/` inside the brackets)
     * is used as-is — the kernel allows both in conditions.
     */
    private fun pathFrom(firstSeg: String, absolute: Boolean = false): Expr {
        val segs = mutableListOf(firstSeg)
        var starred = false
        if (peek() == '*') { next(); starred = true }
        while (peek() == '/') {
            next()
            segs.add(readIdent())
            if (peek() == '*') { next(); starred = true }
        }
        val joined = segs.joinToString("/")
        val full = if (absolute) "/$joined" else "$base/$joined"
        return if (starred) Expr.Star(full) else Expr.Field(full)
    }

    /**
     * The value-list QUANTIFIERS (shape B: `op(f1, f2 In v1, v2)`) — recognized up front and parsed directly
     * into [Cond.ValueListQuant], because the generic expr→`Cond.Pred` flow would discard the `In` boundary
     * between the field list and the value list. Restores the position and returns null if the next token is
     * not one of these names.
     */
    private fun matchValueListQuant(): Cond? {
        val save = pos
        val op = ValueListQuantOp.byName(readIdent())
        skipWs()
        if (op == null || peek() != '(') { pos = save; return null }
        next()   // consume '('
        val fields = mutableListOf(parseExpr())
        skipWs()
        while (peek() == ',') { next(); fields.add(parseExpr()); skipWs() }
        require(matchKeyword("In")) { "parser: $op expects 'In' between its field list and its value list in \"$src\"" }
        val values = mutableListOf(parseExpr())
        skipWs()
        while (peek() == ',') { next(); values.add(parseExpr()); skipWs() }
        expect(')')
        return Cond.ValueListQuant(op, fields, values)
    }

    private fun readArgs(): List<Expr> {
        expect('(')
        val args = mutableListOf(parseExpr())
        skipWs()
        // An infix grammar literal separating arg0 from the rest: `In` for `NumberOfValueInFields(value In f1, …)`,
        // `IN` for `AtLeastOneDateRangeOverlaps(range IN rangeList)` (the kernel grammar has distinct In/IN/in
        // tokens — operator-specific). For every other function the next token is `,`/`)`.
        if (matchKeyword("In") || matchKeyword("IN")) { args.add(parseExpr()); skipWs() }
        while (peek() == ',') { next(); args.add(parseExpr()); skipWs() }
        // `@From <group>` (localized `@Von`) — RepetitionNotUnique's optional reference-group selector. The
        // group ref (a relative name/path, `RuleGroup`, `..`, or absolute) is captured raw as a trailing
        // `@From:<ref>` marker arg; the consumer (DmInterpreter) splits it off the key fields and resolves it.
        if (peek() == '@') {
            next()                                  // '@'
            readIdent()                             // From / Von (consumed; not validated)
            skipWs()
            args.add(Expr.StrLit("@From:" + readGroupRef()))
            skipWs()
        }
        expect(')')
        return args
    }

    /** Read a `@From` reference-group token raw — up to the closing `)` (a group path/name, possibly with `/`). */
    private fun readGroupRef(): String {
        val start = pos
        while (peek() != ')' && peek() != ' ') next()
        return src.substring(start, pos).trim()
    }

    private fun readNumber(): Expr.NumLit {
        val start = pos
        if (peek() == '-') next()
        while (peek().isDigit() || peek() == '.') next()
        return Expr.NumLit(dec(src.substring(start, pos)))
    }

    private fun readString(): Expr.StrLit {
        expect('"')
        val start = pos
        while (peek() != '"') {
            require(pos < src.length) { "parser: unterminated string in \"$src\"" }
            next()
        }
        val s = src.substring(start, pos)
        expect('"')
        return Expr.StrLit(s)
    }

    private fun readIdent(): String {
        skipWs()
        val start = pos
        while (peek().isLetterOrDigit() || peek() == '_') next()
        return src.substring(start, pos)
    }

    /** Consume the keyword if it is the next identifier; otherwise leave the position untouched. */
    private fun matchKeyword(kw: String): Boolean {
        val save = pos
        if (readIdent() == kw) return true
        pos = save
        return false
    }

    /** Consume the next identifier if it is one of [kws]; return it, else null (position untouched). */
    private fun matchOneOf(kws: Set<String>): String? {
        val save = pos
        val id = readIdent()
        if (id in kws) return id
        pos = save
        return null
    }

    private fun tryCmpOp(): CmpOp? {
        skipWs()
        return when (peek()) {
            '<' -> { next(); if (peek() == '=') { next(); CmpOp.LE } else CmpOp.LT }
            '>' -> { next(); if (peek() == '=') { next(); CmpOp.GE } else CmpOp.GT }
            '=' -> { next(); expect('='); CmpOp.EQ }
            '!' -> { next(); expect('='); CmpOp.NE }
            else -> null
        }
    }

    private fun peek(): Char = if (pos < src.length) src[pos] else '\u0000'
    private fun next(): Char = src[pos++]
    private fun skipWs() { while (pos < src.length && src[pos] == ' ') pos++ }
    private fun expect(c: Char) {
        require(peek() == c) { "parser: expected '$c' at $pos in \"$src\"" }
        next()
    }

    private companion object {
        val PREDICATES = setOf(
            "FieldFilled", "FieldNotFilled", "AllFieldsFilled", "GroupFilled", "GroupNotFilled",
            "FieldValueIncludedInValueList", "FieldValueNotIncludedInValueList",
            "NoFieldFilled", "AtLeastOneFieldFilled", "MoreThanOneFieldFilled",
            "NotAllFieldsFilled", "NotExactlyOneFieldFilled", "FieldsNotCollectivelyFilled",
            "AllGroupsFilled", "NoGroupFilled", "AtLeastOneGroupFilled",
            "NotAllGroupsFilled", "GroupsNotCollectivelyFilled",
            "DateRangesOverlap", "AtLeastOneDateRangeOverlaps", "FieldValuesNotUnique",
            "Valid", "Invalid", "RepetitionNotUnique",
        )
        val PATTERN_OPS = setOf("PatternMatched", "PatternViolated")

        /** The fixed-band tolerance comparisons (infix keyword → its band): `a DiffersWithToleranceRangeN b`
         *  fires iff `|a − b| > N`. `readIdent` reads the whole keyword, so `Range10` can't collide with `Range1`. */
        val TOLERANCE_OPS = mapOf(
            "DiffersWithToleranceRange1" to dec("1"), "DiffersWithToleranceRange2" to dec("2"),
            "DiffersWithToleranceRange5" to dec("5"), "DiffersWithToleranceRange10" to dec("10"),
        )

        /** No-operand constant keywords — emitted as `Expr.Const` (a bare ident, not a field path). The
         *  date-constants resolve to a value; `FirstDay`/`LastDay` are day-specification markers consumed
         *  structurally by `ValueAsDate` (never evaluated standalone). */
        val CONSTANTS = setOf("Today", "Now", "BaseYear", "FirstDay", "LastDay")
    }
}
