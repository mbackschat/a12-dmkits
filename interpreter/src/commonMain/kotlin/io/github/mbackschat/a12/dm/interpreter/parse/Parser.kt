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
 * Parser source is a **hand-written completion** (INTERPRETER-SPEC §11, decided WI-P): kernel-free (no
 * parse-tree-api dependency), so it stays native-safe and JS-compilable, and the EUPL question on generating
 * from the kernel `.g4` is moot. It accepts the legal stored DSL the real kernel emits — verified against the
 * catalog + fuzz (100%) and the `a12-kernel-mm` + A12 Tools corpora (100%) by
 * [io.github.mbackschat.a12.dm.adapter.laws.ParserCorpusSweepTest]. Paths in condition text are RELATIVE to
 * the rule's group; [base] resolves them to absolute (base "/Order": `Quantity` → "/Order/Quantity",
 * `Items* / Count` → Star("/Order/Items/Count")).
 *
 * Grammar (low→high precedence): `or := and ("Or" and)*` · `and := primary ("And" primary)*` ·
 * `primary := "(" or ")" | expr (cmpOp expr)? | predicateCall`. Keyword case variants (And/and/AND, …),
 * `;;` comments, `..Name` turning-point paths, and the `For` semantic-index suffix on bare args are handled.
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
        // `CustomCondition <name>` — delegates to a consumer-supplied custom condition resolved by name.
        if (matchKeyword("CustomCondition")) { skipWs(); return Cond.Custom(readIdent()) }
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
                // an optional `$` marks the outer-iteration correlation (KERNEL-FINDINGS §"$ correlation"):
                // `[$field]` reads the field in the ENCLOSING iteration row when inside a `Having` filter.
                val outer = if (peek() == '$') { next(); true } else false
                val p = readRef(outer)            // [/abs] · [../parent-rel] · [relative] — readRef resolves all
                val e = entitySuffix(p)           // optional `-> Category` / `For <index>` (the entitySpec suffix)
                skipWs(); expect(']')
                e
            }
            '"' -> readString()
            // A bare ABSOLUTE (`/A/x`) or PARENT-RELATIVE (`../x`) path as a predicate / aggregate argument —
            // `FieldFilled(/Pol/Cap0)`, `AllFieldsFilled(../A, ../B)`, `Sum(/A*/v)` — the forms the kernel and
            // the rulekit DSL renderer emit. (The same forms inside `[...]` value refs are handled above.) The
            // `entitySpec` suffix (`-> Category` / `For <index>`) can also ride a BARE arg — e.g.
            // `FieldValueAsString(Product For MaxNumber)` — so apply it here too.
            '/', '.' -> entitySuffix(readRef())
            // a bare `$path` — the `$` outer-iteration correlation on a predicate/aggregate arg
            // (`CurrentRepetition($/G/H)`, `NoFieldFilled($a, $b)`, a value-list `$Group*/field`).
            '$' -> { next(); entitySuffix(readRef(outer = true)) }
            else -> when {
                c == '-' || c.isDigit() -> readNumber()
                else -> {
                    val name = readIdent()
                    require(name.isNotEmpty()) { "parser: unexpected '$c' at $pos in \"$src\"" }
                    when (name) {
                        "True" -> Expr.BoolLit(true)
                        "False" -> Expr.BoolLit(false)
                        in CONSTANTS -> Expr.Const(name)
                        // `RuleGroup` (the kernel's RegelKontext) is the rule's OWN group — the [base] itself, not a
                        // child field/group. See [readRef] for the full rationale (IF63); this is the BARE-argument
                        // form (`GroupFilled(RuleGroup)`), which never reaches [readRef].
                        "RuleGroup" -> Expr.Field(base)
                        else -> {
                            skipWs()
                            if (peek() == '(') Expr.Func(name, readArgs()) else entitySuffix(pathFrom(name))
                        }
                    }
                }
            }
        }
    }

    /**
     * The `entitySpec` suffix the kernel grammar allows after ANY entity reference — an `-> Category`
     * (EnumCategory) or a `For <index>` (SemanticIndex) — in BOTH a `[...]` value ref AND a bare
     * predicate/aggregate argument (`FieldValueAsString(Product For MaxNumber)`, string-concat operands). The
     * `For` key is a literal (`For "value"`) or another field (`For keyField`). Applies to a just-read [Expr.Field];
     * any other expression (a `Star`, literal, function) has no such suffix and is returned unchanged.
     */
    private fun entitySuffix(e: Expr): Expr {
        if (e !is Expr.Field) return e
        if (matchArrow()) {
            skipWs()
            return Expr.EnumCategory(e.path, readIdent(), outer = e.outer)
        }
        if (matchKeyword("For")) {
            skipWs()
            return if (peek() == '"') {
                Expr.SemanticIndex(e.path, indexValue = readString().v)
            } else {
                Expr.SemanticIndex(e.path, indexFieldPath = (readRef() as Expr.Field).path)
            }
        }
        return e
    }

    /**
     * Read a field/value reference at the current position, resolving its path to absolute. The forms the
     * kernel — and the rulekit renderer — emit: a leading `/` is **absolute** (used as-is, IF24); a leading
     * run of `..` is **parent-relative** (each `..` walks one group level up from the rule's group [base],
     * IF25); otherwise it is **relative** to [base]. The last `..` in the run may be a **bare `..Name`** —
     * the grammar's `relativeUp` turning-point form (`relUp? '..' idName?`): `Name` merely NAMES the ancestor
     * landed on (the kernel validates it equals that group's short name; the target is set purely by the
     * up-count), so it is ignored for resolution — `..Name/rest` ≡ `../rest`, `..Name` ≡ the ancestor group.
     */
    private fun readRef(outer: Boolean = false): Expr {
        // `RuleGroup` (the kernel's RegelKontext) is the rule's OWN group — the [base] itself. It is a reserved
        // group-context keyword (KERNEL: `REGEL_KONTEXT`), the only such self-reference in the condition grammar,
        // and never a path prefix (a star on it is authoring-rejected `MVK_INVALID_WILDCARD_FOR_RULEGROUP`, and it
        // takes no descent segments), so it resolves to `Expr.Field(base)`. Without this it would parse as a
        // nonexistent child group `base/RuleGroup`, making `GroupFilled(RuleGroup)` always false and silently
        // suppressing the rule (IF63; `matchKeyword` enforces a word boundary, so a field named `RuleGroupX` is safe).
        if (matchKeyword("RuleGroup")) return Expr.Field(base, outer = outer)
        if (peek() == '/') { next(); return pathFrom(readIdent(), absolute = true, outer = outer) }
        val ups = readParentUps()   // MUST precede readIdent — the `..` prefix comes before the first segment
        if (ups > 0 && peek() != '/') {
            // `..Name` / bare `..` with NO descent — the reference IS the ancestor group walkUp(base, ups).
            return Expr.Field(walkUp(base, ups), outer = outer)
        }
        if (ups > 0) next()   // consume the descent '/' separating the up-run from the first segment
        return pathFrom(readIdent(), ups = ups, outer = outer)
    }

    /**
     * Consume a leading run of `..` tokens (each = one group level up) and return how many — 0 if none (IF25).
     * A `..` is either `../` (a `relUp` step — another `..` or the descent `/segment` follows) or a **bare
     * `..Name`** terminal (the `relativeUp` turning point): its optional `Name` label is consumed and DISCARDED
     * (it names the ancestor for validation but never changes the target — see [readRef]). The position is left
     * at the descent `/` (unconsumed) or past the run at a bare ancestor-group reference.
     */
    private fun readParentUps(): Int {
        var ups = 0
        while (peek() == '.' && pos + 1 < src.length && src[pos + 1] == '.') {
            pos += 2                                                              // consume ".." (one level up)
            ups++
            when {
                peek() != '/' -> { skipTurningPointLabel(); break }               // bare "..Name"/".." terminal
                pos + 2 < src.length && src[pos + 1] == '.' && src[pos + 2] == '.' -> next()   // "/.." — another up
                else -> break                                                     // "/segment" — descent boundary
            }
        }
        return ups
    }

    /** Consume (and discard) a `..Name` turning-point label — an identifier read RAW (no whitespace/comment skip,
     *  a path token is contiguous), stopping at the descent `/` or any non-identifier char. */
    private fun skipTurningPointLabel() {
        while (peek().isLetterOrDigit() || peek() == '_') next()
    }

    /**
     * Build a Field/Star from a first segment, reading optional `*` and further `/segment`s. A relative
     * path resolves against the rule's group [base] walked up [ups] levels (0 = the group itself); an
     * [absolute] path (a leading `/`) is used as-is — the kernel allows all three forms in conditions.
     */
    private fun pathFrom(firstSeg: String, absolute: Boolean = false, ups: Int = 0, outer: Boolean = false): Expr {
        val segs = mutableListOf(firstSeg)
        var starred = false
        if (peek() == '*') { next(); starred = true }
        while (peek() == '/') {
            next()
            segs.add(readIdent())
            if (peek() == '*') { next(); starred = true }
        }
        val joined = segs.joinToString("/")
        val full = if (absolute) "/$joined" else "${walkUp(base, ups)}/$joined"
        return if (starred) Expr.Star(full, outer = outer) else Expr.Field(full, outer = outer)
    }

    /** Walk [path] up [ups] group levels (drop that many trailing segments) — the base for a `../` ref (IG1). */
    private fun walkUp(path: String, ups: Int): String {
        if (ups == 0) return path
        val segs = path.split("/").toMutableList()      // "/Order/Lines" -> ["", "Order", "Lines"]
        require(ups <= segs.size - 1) { "parser: '..' walks above the root from base \"$base\" in \"$src\"" }
        repeat(ups) { segs.removeAt(segs.lastIndex) }
        return segs.joinToString("/")
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
        val args = mutableListOf(parseArg())
        skipWs()
        // An infix grammar literal separating arg0 from the rest: `In` for `NumberOfValueInFields(value In f1, …)`
        // / `AtLeastOneDateRangeOverlaps(range In rangeList)`. The kernel's `inOp` is `'In' | 'IN' | 'in'`, so
        // matchKeyword("In") accepts all three via KEYWORD_VARIANTS. For every other function the next token is `,`/`)`.
        if (matchKeyword("In")) { args.add(parseArg()); skipWs() }
        while (peek() == ',') { next(); args.add(parseArg()); skipWs() }
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

    /**
     * One function/predicate argument, with an optional trailing `<star> Having <condition>` per-row filter. The
     * `Having` attaches to WHATEVER starred operand precedes it — not only `arg0` — so it rides an aggregate's
     * single star (`Sum(Items* / Count Having …)`), a quantifier's star, AND a rep-list arg of a boolean predicate
     * (`DateRangesOverlap(X, Items* / Y Having …)`, `AtLeastOneDateRangeOverlaps(X In Items* / Y Having …)`), which
     * the kernel accepts and the interpreter previously rejected ("expected ')'"). A `Having` after a non-star
     * operand is an authoring error (a filter needs rows to filter). The `outer` (`$`) flag is preserved.
     */
    private fun parseArg(): Expr {
        val e = parseExpr()
        skipWs()
        if (!matchKeyword("Having")) return e
        val star = e as? Expr.Star ?: throw UnsupportedConstructException("Having applies to a starred operand")
        return Expr.Star(star.path, parseOr(), star.outer)
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

    /** Consume the `->` arrow (the EnumCategory operator) if it is next; otherwise leave the position untouched.
     *  The arrow is the same symbol in every condition language (the catalog's RENDERING gotcha). */
    private fun matchArrow(): Boolean {
        skipWs()
        if (peek() == '-' && pos + 1 < src.length && src[pos + 1] == '>') { next(); next(); return true }
        return false
    }

    /** Consume the keyword if it is the next identifier; otherwise leave the position untouched. A keyword with
     *  enumerated CASE VARIANTS ([KEYWORD_VARIANTS]) matches any of its variants — nothing else (no case-folding). */
    private fun matchKeyword(kw: String): Boolean {
        val save = pos
        val id = readIdent()
        val accepted = KEYWORD_VARIANTS[kw]
        if (if (accepted == null) id == kw else id in accepted) return true
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
    /** Skip insignificant input: all whitespace (the kernel lexer's `WS` is `channel(HIDDEN)`) and `;;` line
     *  comments (the kernel's `Comment` token — `;; …` to end of line, valid as `before`/`after` of a
     *  condition/operation per `teilBedingung`/`operation`). Stripping here covers every parse position, so a
     *  commented condition/operation parses to its body on all entry points (validateFull/validatePart/compute). */
    private fun skipWs() {
        while (pos < src.length) {
            val c = src[pos]
            when {
                c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000C' -> pos++
                c == ';' && pos + 1 < src.length && src[pos + 1] == ';' -> {
                    pos += 2
                    while (pos < src.length && src[pos] != '\n' && src[pos] != '\r') pos++
                }
                else -> return
            }
        }
    }
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

        /**
         * Keyword CASE VARIANTS the kernel enumerates (`LexerTerminals_en`: the UND/ODER/IN token families) —
         * the kernel accepts EXACTLY these spellings per keyword, so `aNd`/`oR`/`In`-miscased stay REJECTED
         * (not blanket case-folding). Real stored conditions mix all three (the A12 Tools workspaces freely use
         * `and`, `AND`, `Or`). Keywords absent here (`FieldFilled`, `Having`, `For`, …) match their single
         * spelling only. The `to`/`To`/`TO` (ZU) family is intentionally absent: its only user is the
         * `ValueNotConsistent` separator, which the interpreter does not parse (not in the catalog, not in the
         * real corpora), so it has no match site — adding it would be dead.
         */
        val KEYWORD_VARIANTS: Map<String, Set<String>> = mapOf(
            "And" to setOf("And", "and", "AND"),
            "Or" to setOf("Or", "or", "OR"),
            "In" to setOf("In", "in", "IN"),
        )
    }
}
