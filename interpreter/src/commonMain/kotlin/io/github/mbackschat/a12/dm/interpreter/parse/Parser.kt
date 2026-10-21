package io.github.mbackschat.a12.dm.interpreter.parse

import io.github.mbackschat.a12.dm.interpreter.javaTrimmed
import io.github.mbackschat.a12.dm.interpreter.UnsupportedConstructException
import io.github.mbackschat.a12.dm.interpreter.dec
import io.github.mbackschat.a12.dm.interpreter.ast.ArithOp
import io.github.mbackschat.a12.dm.interpreter.ast.CmpOp
import io.github.mbackschat.a12.dm.interpreter.ast.Cond
import io.github.mbackschat.a12.dm.interpreter.ast.Expr
import io.github.mbackschat.a12.dm.interpreter.ast.ValueListQuantOp
import io.github.mbackschat.a12.dm.interpreter.model.ConditionLanguage

/**
 * A recursive-descent parser turning condition TEXT into the interpreter [Cond] AST. [ConditionLanguage] selects
 * the model's stored English or German keyword vocabulary; display locales are independent. It lives in commonMain,
 * so it parses on JVM and JS alike.
 *
 * This is a hand-written clean-room parser with no kernel parse-tree dependency, so it remains native-safe and
 * JS-compilable. [io.github.mbackschat.a12.dm.adapter.laws.ParserCorpusSweepTest] owns corpus conformance rather
 * than duplicating a volatile coverage claim here. Paths in condition text are RELATIVE to the rule's group;
 * [base] resolves them to absolute (base "/Order": `Quantity` → "/Order/Quantity`, `Items* / Count` →
 * Star("/Order/Items/Count")).
 *
 * Grammar (low→high precedence): `or := and ("Or" and)*` · `and := primary ("And" primary)*` ·
 * `primary := "(" or ")" | expr (cmpOp expr)? | predicateCall`. Keyword case variants (And/and/AND, …),
 * `;;` comments, `..Name` turning-point paths, and the `For` semantic-index suffix on bare args are handled.
 */
fun parseCondition(text: String, base: String): Cond =
    parseCondition(text, base, DEFAULT_RESOLVER, ConditionLanguage.EN)

fun parseCondition(text: String, base: String, resolver: RefResolver): Cond =
    parseCondition(text, base, resolver, ConditionLanguage.EN)

fun parseCondition(text: String, base: String, language: ConditionLanguage): Cond =
    parseCondition(text, base, DEFAULT_RESOLVER, language)

fun parseCondition(
    text: String,
    base: String,
    resolver: RefResolver,
    language: ConditionLanguage,
): Cond = ConditionParser(text, base, resolver, language).parse().lowerArithmetic()

/**
 * Parse a RULE's condition: the grammar's start production `preamble? bedingung`, whose optional preamble is the
 * scale-gate waiver `@SuppressWarning(MVK_INVALID_COMPARE_DEC_PLACES)` (`@UnterdrueckeWarnung` in a German model;
 * KERNEL-FINDINGS KF75). The waiver lifts a static gate only, so it is consumed and changes no evaluation. Only a
 * rule's own condition starts this way: a computation's preconditions do not, so they stay on [parseCondition].
 */
fun parseRuleCondition(
    text: String,
    base: String,
    resolver: RefResolver,
    language: ConditionLanguage,
): Cond = ConditionParser(text, base, resolver, language).parseRule().lowerArithmetic()

/** Parse a value expression (a computation's right-hand side), paths relative to [base]. */
fun parseExpression(text: String, base: String): Expr =
    parseExpression(text, base, DEFAULT_RESOLVER, ConditionLanguage.EN)

fun parseExpression(text: String, base: String, resolver: RefResolver): Expr =
    parseExpression(text, base, resolver, ConditionLanguage.EN)

fun parseExpression(text: String, base: String, language: ConditionLanguage): Expr =
    parseExpression(text, base, DEFAULT_RESOLVER, language)

fun parseExpression(
    text: String,
    base: String,
    resolver: RefResolver,
    language: ConditionLanguage,
): Expr = ConditionParser(text, base, resolver, language).parseTopExpr().lowerArithmetic()

/**
 * Resolves a **bare, relative, single-segment** reference (`[Name]` / a bare `Name` arg) to its absolute
 * field/group path. The default absolutizes against the rule's group ([base]/[name]) — the historical
 * behavior, and correct for a same-group ref. A **model-aware** resolver (supplied by [ParsedModel]) adds the
 * kernel's `fieldRefByShortNameAllowed` fallback: when `base/name` is not a declared entity AND the flag is
 * on, resolve to a UNIQUE model-wide field short name (KERNEL-FINDINGS §10; IF119). Only bare single-segment
 * refs reach here — an absolute (`/x`), a `..`-relative, a multi-segment (`a/b`), or a starred (`Name*`) ref
 * is resolved structurally and never short-name-substituted, matching the kernel (`!isAbsolutPath()`, fields
 * only, no wildcard).
 */
fun interface RefResolver {
    fun resolve(base: String, name: String): String
}

/** The declaring-group-only resolver — `base/name`, the pre-IF119 behavior; used when no model is supplied. */
val DEFAULT_RESOLVER: RefResolver = RefResolver { base, name -> "$base/$name" }

private class ConditionParser(
    private val src: String,
    private val base: String,
    private val resolver: RefResolver,
    private val language: ConditionLanguage,
) {

    private var pos = 0

    fun parse(): Cond {
        val c = parseOr()
        skipWs()
        require(pos >= src.length) { "parser: trailing input at $pos in \"$src\"" }
        return c
    }

    fun parseRule(): Cond {
        skipWs()
        if (peek() == '@') {
            next()
            require(matchKeyword("SuppressWarning")) {
                "parser: expected the localized @SuppressWarning keyword in \"$src\""
            }
            skipWsAndExpect('(')
            // A12 admits exactly this one name, padding included (KF75); any other is refused before evaluation.
            require(readIdent() == SCALE_GATE_WAIVER) {
                "parser: only $SCALE_GATE_WAIVER can be waived, in \"$src\""
            }
            skipWsAndExpect(')')
        }
        return parse()
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
                skipWs()   // whitespace after `[` is hidden in ANTLR — `[ /abs]` / `[ ../rel]` are the space-free path
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
                c == '-' || A12Lexis.isDigit(c) -> readNumber()
                else -> {
                    val quoted = c == '\''
                    val authoredName = readIdent()
                    require(authoredName.isNotEmpty()) { "parser: unexpected '$c' at $pos in \"$src\"" }
                    val keyword = if (quoted) null else language.canonicalKeyword(authoredName)
                    when (keyword) {
                        "True" -> Expr.BoolLit(true)
                        "False" -> Expr.BoolLit(false)
                        in CONSTANTS -> Expr.Const(checkNotNull(keyword))
                        // `RuleGroup` (the kernel's RegelKontext) is the rule's OWN group — the [base] itself, not a
                        // child field/group. See [readRef] for the full rationale (IF63); this is the BARE-argument
                        // form (`GroupFilled(RuleGroup)`), which never reaches [readRef]. It carries an
                        // `entitySpec` suffix like any other entity reference (`RuleGroup For "a"` selects the
                        // rule's group by semantic index), and applies it here because the shared call site
                        // around [readRef] is the one it bypasses.
                        "RuleGroup" -> entitySuffix(Expr.Field(base))
                        else -> {
                            skipWs()
                            if (peek() == '(') {
                                require(quoted || !language.isForeignKeyword(authoredName)) {
                                    "parser: '$authoredName' is not a function keyword in ${language.code}"
                                }
                                Expr.Func(keyword ?: authoredName, readArgs(allowEmpty = keyword == "Time"))
                            } else {
                                entitySuffix(pathFrom(authoredName))
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * The `entitySpec` suffix the owner grammar allows after ANY field entity reference — an optional
     * `-> Category` followed by an optional `For <index>` — in BOTH a `[...]` value ref AND a bare
     * predicate/aggregate argument (`FieldValueAsString(Product For MaxNumber)`, string-concat operands). The
     * `For` key is a literal (`For "value"`) or another field (`For keyField`). A starred source may carry a
     * category, index and a later `Having`; the prepared audit checks the index/star placement (KF351, KF475).
     */
    private fun entitySuffix(e: Expr): Expr {
        val source = e as? Expr.EntitySource ?: return e
        val category = if (matchArrow()) {
            skipWs()
            readIdent()
        } else null
        val index = if (matchKeyword("For")) {
            skipWs()
            if (peek() == '"') {
                Expr.EntityIndex.Literal(readString().v)
            } else {
                val key = readRef()
                Expr.EntityIndex.Field(key.path, key.outer)
            }
        } else null
        if (category == null && index == null) return e
        return when {
            source is Expr.Field && category != null && index == null ->
                Expr.EnumCategory(source.path, category, source.outer)
            source is Expr.Field && category == null && index != null -> source.semanticIndex(index)
            else -> Expr.EntitySpec(source, category, index)
        }
    }

    private fun Expr.Field.semanticIndex(index: Expr.EntityIndex): Expr.SemanticIndex = when (index) {
        is Expr.EntityIndex.Literal -> Expr.SemanticIndex(path, indexValue = index.value, outer = outer)
        is Expr.EntityIndex.Field -> Expr.SemanticIndex(
            path,
            indexFieldPath = index.path,
            outer = outer,
            indexFieldOuter = index.outer,
        )
    }

    /**
     * Read a field/value reference at the current position, resolving its path to absolute. The forms the
     * kernel — and the rulekit renderer — emit: a leading `/` is **absolute** (used as-is, IF24); a leading
     * run of `..` is **parent-relative** (each `..` walks one group level up from the rule's group [base],
     * IF25); otherwise it is **relative** to [base]. The last `..` in the run may be a **bare `..Name`** —
     * the grammar's `relativeUp` turning-point form (`relUp? '..' idName?`): `Name` merely NAMES the ancestor
     * landed on (the kernel validates it equals that group's short name; the target is set purely by the
     * up-count), so it is ignored for resolution — `..Name/rest` ≡ `../rest`, `..Name` ≡ the ancestor group.
     *
     * Returns [Expr.EntitySource], not [Expr]: every form above is a [Expr.Field] or a [Expr.Star], so declaring
     * the shared shape lets a consumer that needs only `path`/`outer` — the semantic-index key in [entitySuffix] —
     * read them without a cast the compiler cannot check. A key may carry a star, and narrowing that site to
     * [Expr.Field] by hand threw `ClassCastException` on the starred shape (`StarredSemanticIndexKeyParseTest`).
     */
    private fun readRef(outer: Boolean = false): Expr.EntitySource {
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
     *
     * **Whitespace after a `..` is insignificant** (the kernel's ANTLR lexer hidden channel, IF75/IF91): `.. /seg`
     * ≡ `../seg` (a descent) and `.. Name` ≡ `..Name` (a discarded turning-point label). Skipping it here
     * disambiguates a following descent `/` from a turning-point label after the space (IF91).
     */
    private fun readParentUps(): Int {
        var ups = 0
        while (peek() == '.' && pos + 1 < src.length && src[pos + 1] == '.') {
            pos += 2                                                              // consume ".." (one level up)
            ups++
            skipWs()                                                             // ws after ".." is hidden-channel (IF91)
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
        while (A12Lexis.isNamePart(peek())) next()
    }

    /**
     * Build a Field/Star from a first segment, reading optional `*` and further `/segment`s. A relative
     * path resolves against the rule's group [base] walked up [ups] levels (0 = the group itself); an
     * [absolute] path (a leading `/`) is used as-is — the kernel allows all three forms in conditions.
     */
    private fun pathFrom(firstSeg: String, absolute: Boolean = false, ups: Int = 0,
                         outer: Boolean = false): Expr.EntitySource {
        val segs = mutableListOf(firstSeg)
        var firstStar = if (skipWsAndMatch('*')) 1 else -1   // 1-based index of the FIRST `*`-marked segment
        while (skipWsAndMatch('/')) {
            segs.add(readIdent())                    // readIdent skips leading whitespace before the next segment
            if (skipWsAndMatch('*') && firstStar < 0) firstStar = segs.size
        }
        val joined = segs.joinToString("/")
        // A BARE, relative, single-segment, unstarred ref (`[Name]` / bare `Name` arg) goes through the
        // resolver — which may resolve it model-wide by unique short name (IF119); every other shape
        // (absolute, `..`-relative, multi-segment, starred) is absolutized structurally, matching the
        // kernel's short-name fallback gate (`!isAbsolutPath()`, single leaf, no wildcard).
        val full = when {
            absolute -> "/$joined"
            ups == 0 && segs.size == 1 && firstStar < 0 -> resolver.resolve(base, joined)
            else -> "${walkUp(base, ups)}/$joined"
        }
        if (firstStar < 0) return Expr.Field(full, outer = outer)
        // The segments STRICTLY ABOVE the first starred group — the levels an iterating row binds (IF93/IF94);
        // the starred level itself (and everything below, incl. later stars) always iterates fully.
        val bindDepth = full.trim('/').split('/').size - segs.size + firstStar - 1
        return Expr.Star(full, outer = outer, bindDepth = bindDepth)
    }

    /**
     * Skip whitespace and consume [c] if it is the next significant char; else restore the position and return
     * false. Lets a path cross whitespace around its `/` separator and `*` wildcard — the kernel's ANTLR lexer
     * puts whitespace on a HIDDEN channel, so `Group* / Field` ≡ the space-free form (the gold standard). This
     * is unambiguous because a BARE-identifier path is never an arithmetic operand (the grammar's arithmetic
     * atoms `einfacheOperation` are bracketed `[entity]`, constants, or named functions — never a bare ident),
     * so a `/`/`*` after a path segment is a separator/wildcard, not division/multiplication; the arithmetic
     * `parseMultiplicative` only ever sees a `/` or `*` after a NON-path atom. See [PathWhitespaceParseTest].
     */
    private fun skipWsAndMatch(c: Char): Boolean {
        val save = pos
        skipWs()
        if (peek() == c) { next(); return true }
        pos = save
        return false
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
     * not one of these names. Each operand goes through [parseArg], so a trailing `<star> Having <cond>` filter
     * rides a starred operand on EITHER side — the kernel's `entityListe : entitySpec*` with an optional `Mit`
     * per `entitySpec` (grammar); the kernel accepts + evaluates it (IG48), so it must parse (else the corpus
     * idiom would silently degrade — the IG42 class).
     */
    private fun matchValueListQuant(): Cond? {
        val save = pos
        val op = readCanonicalKeyword()?.let(ValueListQuantOp::byName)
        skipWs()
        if (op == null || peek() != '(') { pos = save; return null }
        next()   // consume '('
        val fields = mutableListOf(parseArg())
        skipWs()
        while (peek() == ',') { next(); fields.add(parseArg()); skipWs() }
        require(matchKeyword("In")) { "parser: $op expects 'In' between its field list and its value list in \"$src\"" }
        val values = mutableListOf(parseArg())
        skipWs()
        while (peek() == ',') { next(); values.add(parseArg()); skipWs() }
        expect(')')
        return Cond.ValueListQuant(op, fields, values)
    }

    private fun readArgs(allowEmpty: Boolean = false): List<Expr> {
        expect('(')
        skipWs()
        if (allowEmpty && peek() == ')') {
            next()
            return emptyList()
        }
        val args = mutableListOf(parseArg())
        skipWs()
        // An infix grammar literal separating arg0 from the rest: `In` for `NumberOfValueInFields(value In f1, …)`
        // / `AtLeastOneDateRangeOverlaps(range In rangeList)`. The kernel's `inOp` is `'In' | 'IN' | 'in'`, so
        // matchKeyword("In") accepts all three language-independent variants. For every other function the next
        // token is `,`/`)`.
        if (matchKeyword("In")) { args.add(parseArg()); skipWs() }
        // A second such infix literal, `To` (DE `Zu`) — ValueNotConsistent's field/value pair
        // (`ValueNotConsistent(field To value, dependent1, …)`, kernel
        // `EntityListAndValueListConditionName.ValueNotConsistent`). The construct itself stays unmodeled
        // (KernelConstructParityTest/KeywordReverseParityTest both exclude it deliberately); reading the
        // infix carries a call using it past this grammar boundary, so parsePrimary's "not a supported
        // condition shape" check is what refuses it — an UnsupportedConstructException, which classifies
        // the rule as unmodeled rather than as malformed text.
        if (matchKeyword("To")) { args.add(parseArg()); skipWs() }
        while (peek() == ',') { next(); args.add(parseArg()); skipWs() }
        // `@From <group>` (localized `@Von`) — RepetitionNotUnique's optional reference-group selector. The
        // group ref (a relative name/path, `RuleGroup`, `..`, or absolute) is captured raw as a trailing
        // `@From:<ref>` marker arg; the consumer (DmInterpreter) splits it off the key fields and resolves it.
        if (peek() == '@') {
            next()
            require(matchKeyword("From")) {
                "parser: expected the localized @From keyword in \"$src\""
            }
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
        return when (e) {
            is Expr.Star -> e.copy(having = parseOr())
            is Expr.EntitySpec -> {
                val star = e.source as? Expr.Star
                    ?: throw UnsupportedConstructException("Having applies to a starred operand")
                e.copy(source = star.copy(having = parseOr()))
            }
            else -> throw UnsupportedConstructException("Having applies to a starred operand")
        }
    }

    /** Read a `@From` reference-group token raw — up to the closing `)` (a group path/name, possibly with `/`). */
    private fun readGroupRef(): String {
        val start = pos
        while (peek() != ')' && peek() != '\u0000') next()
        // The condition lexer's whitespace is five ASCII characters, so Java's trim is exact on admitted text.
        return src.substring(start, pos).javaTrimmed()
    }

    private fun readNumber(): Expr.NumLit {
        val start = pos
        var separated = false
        if (peek() == '-') {
            next()
            separated = skipNumberSpace()
        }
        while (A12Lexis.isDigit(peek())) next()
        val integerEnd = pos
        val beforePoint = skipNumberSpace()
        if (peek() == '.') {
            next()
            separated = skipNumberSpace() || beforePoint || separated
            while (A12Lexis.isDigit(peek())) next()
        } else {
            pos = integerEnd
        }
        val literal = src.substring(start, pos)
        return Expr.NumLit(dec(if (separated) literal.filterNot(A12Lexis::isSpace) else literal))
    }

    // ConformanceFrontierTest measures whitespace between numeric tokens, but rejects comments there.
    private fun skipNumberSpace(): Boolean {
        val start = pos
        while (true) {
            when (peek()) {
                ' ', '\t', '\n', '\r', '\u000C' -> next()
                else -> return pos != start
            }
        }
    }

    private fun readString(): Expr.StrLit {
        expect('"')
        val start = pos
        while (true) {
            require(pos < src.length) { "parser: unterminated string in \"$src\"" }
            if (peek() == '"') {
                if (src.getOrNull(pos + 1) != '"') break
                pos += 2
            } else next()
        }
        val s = src.substring(start, pos)
        expect('"')
        // AuthoredStringNewlineDiffTest separates literal decoding from Document ingestion.
        return Expr.StrLit(s.replace("\"\"", "\"").replace("\r\n", "\n"))
    }

    private fun readIdent(): String {
        skipWs()
        if (peek() == '\'') {
            next()
            val start = pos
            while (peek() != '\'') {
                require(pos < src.length) { "parser: unterminated quoted path segment in \"$src\"" }
                next()
            }
            val segment = src.substring(start, pos)
            expect('\'')
            require(segment.isNotEmpty()) { "parser: empty quoted path segment in \"$src\"" }
            return segment
        }
        val start = pos
        while (A12Lexis.isNamePart(peek())) next()
        return src.substring(start, pos)
    }

    /** Consume the `->` arrow (the EnumCategory operator) if it is next; otherwise leave the position untouched.
     *  The arrow is the same symbol in every condition language (the catalog's RENDERING gotcha). */
    private fun matchArrow(): Boolean {
        skipWs()
        if (peek() == '-' && pos + 1 < src.length && src[pos + 1] == '>') { next(); next(); return true }
        return false
    }

    /** Consume the localized form of [kw] if it is next; otherwise leave the position untouched. */
    private fun matchKeyword(kw: String): Boolean {
        val save = pos
        skipWs()
        if (peek() == '\'') {
            pos = save
            return false
        }
        val id = readIdent()
        if (language.accepts(id, kw)) return true
        pos = save
        return false
    }

    /** Consume the next identifier if it is one of [kws]; return it, else null (position untouched). */
    private fun matchOneOf(kws: Set<String>): String? {
        val save = pos
        val id = readCanonicalKeyword()
        if (id in kws) return id
        pos = save
        return null
    }

    private fun readCanonicalKeyword(): String? {
        skipWs()
        if (peek() == '\'') return null
        return language.canonicalKeyword(readIdent())
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
     *  condition/operation per `teilBedingung`/`operation`). Numeric-token boundaries use the whitespace-only scanner instead (ConformanceFrontierTest);
     *  comments there are significant and rejected. */
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
    private fun skipWsAndExpect(c: Char) {
        skipWs()
        expect(c)
    }

    private fun expect(c: Char) {
        require(peek() == c) { "parser: expected '$c' at $pos in \"$src\"" }
        next()
    }

    private companion object {
        const val SCALE_GATE_WAIVER = "MVK_INVALID_COMPARE_DEC_PLACES"
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
         *  fires iff `|R19(a) − R19(b)| > N`. `readIdent` reads the whole keyword, so `Range10` can't collide with `Range1`. */
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
